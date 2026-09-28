/*
 *     Copyright (C) 2022  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.swordfish.libretrodroid

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.coroutineScope
import com.swordfish.libretrodroid.KtUtils.awaitUninterruptibly
import com.swordfish.libretrodroid.gamepad.GamepadsManager
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.properties.Delegates
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class GLRetroView(
    context: Context,
    private val data: GLRetroViewData
) : AspectRatioGLSurfaceView(context), LifecycleObserver {

    var audioEnabled: Boolean by Delegates.observable(true) { _, _, value ->
        LibretroDroid.setAudioEnabled(value)
    }

    var frameSpeed: Int by Delegates.observable(1) { _, _, value ->
        LibretroDroid.setFrameSpeed(value)
    }

    // Native touch controls use this for slow motion. Keeping the pacing in
    // the renderer avoids pretending that frameSpeed=0 is a valid mode.
    var slowMotion: Boolean = false
    private var slowFrameToggle = false

    var shader: ShaderConfig by Delegates.observable(data.shader) { _, _, value ->
        LibretroDroid.setShaderConfig(buildShader(value))
    }

    private val openGLESVersion: Int

    private var isGameLoaded = false
    private var isAborted = false

    private val retroGLEventsSubject = MutableSharedFlow<GLRetroEvents>(1)
    private val retroGLIssuesErrors = MutableSharedFlow<Int>(1)

    private val rumbleEventsSubject = MutableSharedFlow<RumbleEvent>()
    private val firstFrameCallbacks = mutableListOf<Runnable>()
    @Volatile private var firstFrameRendered = false
    @Volatile private var isEmulationReady = false
    private var isCoreCreated = false
    private var isCoreResumed = false
    private var isCoreDestroyed = false

    private var lifecycle: Lifecycle? = null

    init {
        openGLESVersion = getGLESVersion(context)
        Log.i(TAG_LOG, "Libretro requested GLES $openGLESVersion (abi=${Build.SUPPORTED_ABIS?.joinToString()})")
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(openGLESVersion)
        // Some Android virtualized SurfaceView implementations reject the
        // buffer after AspectRatioGLSurfaceView resizes itself from the full
        // display to the core's native aspect ratio. Keep the surface at the
        // display size there; the renderer already letterboxes the texture.
        if (isLegacyVirtualizedRenderer()) {
            setResizeMode(AspectRatioGLSurfaceView.RESIZE_MODE_FILL)
        }
        setRenderer(Renderer())
        // The native core must advance continuously. Relying on the
        // GLSurfaceView default is fragile on some virtualized Android
        // renderers and can leave audio initialized while no frames run.
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        keepScreenOn = true
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onCreate(lifecycleOwner: LifecycleOwner) = catchExceptions {
        Log.i(TAG_LOG, "Libretro lifecycle ON_CREATE")
        lifecycle = lifecycleOwner.lifecycle
        val previousOwner = synchronized(coreLock) { nativeOwner }
        if (previousOwner != null && previousOwner !== this) {
            // LibretroDroid owns one process-wide native core. A replacement
            // view must stop the previous GL thread before create() resets the
            // singleton pointers; task replacement can overlap Activity lifecycles.
            previousOwner.isEmulationReady = false
            previousOwner.onPause()
            synchronized(coreLock) {
                if (nativeOwner === previousOwner && previousOwner.isCoreCreated
                    && !previousOwner.isCoreDestroyed) {
                    if (previousOwner.isCoreResumed) {
                        LibretroDroid.pause()
                        previousOwner.isCoreResumed = false
                    }
                    previousOwner.isCoreDestroyed = true
                    LibretroDroid.destroy()
                    nativeOwner = null
                }
            }
        }
        synchronized(coreLock) {
            check(nativeOwner == null || nativeOwner === this) {
                "A different GLRetroView still owns the process-wide Libretro core"
            }
            LibretroDroid.create(
                openGLESVersion,
                data.coreFilePath,
                data.systemDirectory,
                data.savesDirectory,
                data.variables,
                buildShader(data.shader),
                getDefaultRefreshRate(),
                data.preferLowLatencyAudio,
                data.gameVirtualFiles.isNotEmpty(),
                data.skipDuplicateFrames,
                getDeviceLanguage()
            )
            isCoreCreated = true
            nativeOwner = this
            LibretroDroid.setRumbleEnabled(data.rumbleEventsEnabled)
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() = catchExceptions {
        Log.i(TAG_LOG, "Libretro lifecycle ON_DESTROY")
        isEmulationReady = false
        val destroyOnGlThread = synchronized(coreLock) { isCoreResumed }
        if (destroyOnGlThread) {
            // The native core is a process-wide singleton and retro_run() is
            // called by GLSurfaceView's render thread. Queue teardown behind
            // the current frame so video callbacks cannot race native destroy.
            val completed = CountDownLatch(1)
            queueEvent {
                try {
                    destroyNativeCore()
                } finally {
                    completed.countDown()
                }
            }
            if (!completed.await(2, TimeUnit.SECONDS)) {
                Log.w(TAG_LOG, "GL thread did not acknowledge teardown; using serialized fallback")
                destroyNativeCore()
            }
        } else {
            // ON_PAUSE already stopped the renderer; the singleton is idle.
            destroyNativeCore()
        }
        onPause()
        lifecycle = null
    }

    private fun destroyNativeCore() = synchronized(coreLock) {
        if (nativeOwner !== this || !isCoreCreated || isCoreDestroyed) {
            isCoreDestroyed = true
            return@synchronized
        }
        if (isCoreResumed) {
            LibretroDroid.pause()
            isCoreResumed = false
        }
        isCoreDestroyed = true
        LibretroDroid.destroy()
        nativeOwner = null
    }

    private fun getDeviceLanguage() = Locale.getDefault().language

    private fun getDefaultRefreshRate(): Float {
        return (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.refreshRate
    }

    fun sendKeyEvent(action: Int, keyCode: Int, port: Int = 0) {
        queueEvent { LibretroDroid.onKeyEvent(port, action, keyCode) }
    }

    fun sendMotionEvent(source: Int, xAxis: Float, yAxis: Float, port: Int = 0) {
        queueEvent { LibretroDroid.onMotionEvent(port, source, xAxis, yAxis) }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        when (event?.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sendTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE -> {
                sendTouchEvent(event)
            }
            MotionEvent.ACTION_UP -> {
                sendMotionEvent(MOTION_SOURCE_POINTER, -1f, -1f)
            }
        }
        return true
    }

    private fun clamp(x: Float, min: Float, max: Float) = minOf(maxOf(x, min), max)

    private fun sendTouchEvent(event: MotionEvent) {
        val x = clamp(event.x / width, 0f, 1f)
        val y = clamp(event.y / height, 0f, 1f)
        sendMotionEvent(MOTION_SOURCE_POINTER, x, y)
    }

    fun serializeState(): ByteArray = runOnGLThread {
        LibretroDroid.serializeState()
    }
    fun setCheat(index : Int, enable : Boolean, code : String) = runOnGLThread {
        LibretroDroid.setCheat(index, enable, code)
    }

    fun unserializeState(data: ByteArray): Boolean = runOnGLThread {
        LibretroDroid.unserializeState(data)
    }

    fun serializeSRAM(): ByteArray = runOnGLThread {
        LibretroDroid.serializeSRAM()
    }

    fun unserializeSRAM(data: ByteArray): Boolean = runOnGLThread {
        LibretroDroid.unserializeSRAM(data)
    }

    fun reset() = runOnGLThread {
        LibretroDroid.reset()
    }

    fun getGLRetroEvents(): Flow<GLRetroEvents> {
        return retroGLEventsSubject
    }

    /** Runs once the core advances its first frame, even if the listener subscribes later. */
    fun onFirstFrame(callback: Runnable) {
        val alreadyRendered: Boolean
        synchronized(firstFrameCallbacks) {
            alreadyRendered = firstFrameRendered
            if (!alreadyRendered) firstFrameCallbacks.add(callback)
        }
        if (alreadyRendered) post { callback.run() }
    }

    private fun notifyFirstFrameRendered() {
        val callbacks: List<Runnable>
        synchronized(firstFrameCallbacks) {
            if (firstFrameRendered) return
            firstFrameRendered = true
            callbacks = firstFrameCallbacks.toList()
            firstFrameCallbacks.clear()
        }
        callbacks.forEach { callback -> post { callback.run() } }
    }

    fun getGLRetroErrors(): Flow<Int> {
        return retroGLIssuesErrors
    }

    fun getRumbleEvents(): Flow<RumbleEvent> {
        return rumbleEventsSubject
    }

    fun getControllers(): Array<Array<Controller>> {
        return LibretroDroid.getControllers()
    }

    fun setControllerType(port: Int, type: Int) {
        LibretroDroid.setControllerType(port, type)
    }

    fun getVariables(): Array<Variable> {
        return LibretroDroid.getVariables()
    }

    fun updateVariables(vararg variables: Variable) {
        variables.forEach {
            LibretroDroid.updateVariable(it)
        }
    }

    fun getAvailableDisks() = runOnGLThread { LibretroDroid.availableDisks() }
    fun getCurrentDisk() = runOnGLThread { LibretroDroid.currentDisk() }
    fun changeDisk(index: Int) = runOnGLThread { LibretroDroid.changeDisk(index) }

    private fun getGLESVersion(context: Context): Int {
        // BlueStacks exposes a GLES 3.2 context even on its Android 9 image.
        // Use the ES3 image path there as well: it uploads each frame with the
        // native RGB565 texture format instead of the legacy ES2 row fallback.
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (activityManager.deviceConfigurationInfo.reqGlEsVersion >= 0x30000) { 3 } else { 2 }
    }

    private fun isLegacyVirtualizedRenderer(): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return false
        val x86_64 = Build.SUPPORTED_ABIS?.any { it.equals("x86_64", ignoreCase = true) } == true
        if (!x86_64) return false
        val fingerprint = (Build.FINGERPRINT ?: "").lowercase(Locale.US)
        val hardware = (Build.HARDWARE ?: "").lowercase(Locale.US)
        return fingerprint.contains("p3sxxx") ||
            fingerprint.contains("generic") ||
            fingerprint.contains("emulator") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mappedKey = GamepadsManager.getGamepadKeyEvent(keyCode)
        val port = (event?.device?.controllerNumber ?: 0) - 1

        if (event != null && port >= 0 && keyCode in GamepadsManager.GAMEPAD_KEYS) {
            sendKeyEvent(KeyEvent.ACTION_DOWN, mappedKey, port)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val mappedKey = GamepadsManager.getGamepadKeyEvent(keyCode)
        val port = (event?.device?.controllerNumber ?: 0) - 1

        if (event != null && port >= 0 && keyCode in GamepadsManager.GAMEPAD_KEYS) {
            sendKeyEvent(KeyEvent.ACTION_UP, mappedKey, port)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        val port = (event?.device?.controllerNumber ?: 0) - 1
        if (port >= 0) {
            when (event?.source) {
                InputDevice.SOURCE_JOYSTICK -> {
                    sendMotionEvent(
                        MOTION_SOURCE_DPAD,
                        event.getAxisValue(MotionEvent.AXIS_HAT_X),
                        event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                        port
                    )
                    sendMotionEvent(
                        MOTION_SOURCE_ANALOG_LEFT,
                        event.getAxisValue(MotionEvent.AXIS_X),
                        event.getAxisValue(MotionEvent.AXIS_Y),
                        port
                    )
                    sendMotionEvent(
                        MOTION_SOURCE_ANALOG_RIGHT,
                        event.getAxisValue(MotionEvent.AXIS_Z),
                        event.getAxisValue(MotionEvent.AXIS_RZ),
                        port
                    )
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // These functions are called only after the GLSurfaceView has been created.
    private inner class RenderLifecycleObserver : LifecycleObserver {
        @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
        private fun resume() = catchExceptions {
            Log.i(TAG_LOG, "Libretro lifecycle RESUME")
            onResume()
            refreshAspectRatio()
            synchronized(coreLock) {
                if (nativeOwner === this@GLRetroView && !isCoreDestroyed) {
                    LibretroDroid.resume()
                    isCoreResumed = true
                    isEmulationReady = true
                }
            }
        }

        @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        private fun pause() = catchExceptions {
            isEmulationReady = false
            onPause()
            synchronized(coreLock) {
                if (nativeOwner === this@GLRetroView && isCoreCreated
                    && !isCoreDestroyed && isCoreResumed) {
                    LibretroDroid.pause()
                    isCoreResumed = false
                }
            }
        }
    }

    inner class Renderer : GLSurfaceView.Renderer {
        private var drawLogCount = 0

        override fun onDrawFrame(gl: GL10) = catchExceptions {
            if (drawLogCount < 3) Log.i(TAG_LOG, "Libretro draw frame #${++drawLogCount} ready=$isEmulationReady")
            var steppedCore = false
            synchronized(coreLock) {
                if (nativeOwner === this@GLRetroView && isEmulationReady && !isCoreDestroyed) {
                    if (!slowMotion) {
                        LibretroDroid.step(this@GLRetroView)
                        steppedCore = true
                    } else {
                        slowFrameToggle = !slowFrameToggle
                        if (slowFrameToggle) {
                            LibretroDroid.step(this@GLRetroView)
                            steppedCore = true
                        }
                    }
                    if (steppedCore) notifyFirstFrameRendered()
                }
            }
            if (steppedCore) {
                lifecycle?.coroutineScope?.launch {
                    retroGLEventsSubject.emit(GLRetroEvents.FrameRendered)
                }
            }
        }

        override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) = catchExceptions {
            Log.i(TAG_LOG, "Libretro surface changed ${width}x${height}")
            Thread.currentThread().priority = Thread.MAX_PRIORITY
            synchronized(coreLock) {
                if (nativeOwner === this@GLRetroView && !isCoreDestroyed) {
                    LibretroDroid.onSurfaceChanged(width, height)
                }
            }
        }


        override fun onSurfaceCreated(gl: GL10, config: EGLConfig) = catchExceptions {
            Log.i(TAG_LOG, "Libretro surface created")
            Thread.currentThread().priority = Thread.MAX_PRIORITY
            synchronized(coreLock) {
                if (nativeOwner === this@GLRetroView && !isCoreDestroyed) initializeCore()
            }
            if (nativeOwner === this@GLRetroView && !isCoreDestroyed) {
                lifecycle?.coroutineScope?.launch {
                    retroGLEventsSubject.emit(GLRetroEvents.SurfaceCreated)
                }
            }
        }
    }

    private fun refreshAspectRatio() {
        val aspectRatio = LibretroDroid.getAspectRatio()
        if (isLegacyVirtualizedRenderer()) return
        KtUtils.runOnUIThread { setAspectRatio(aspectRatio) }
    }

    // These functions are called from the GL thread.
    private fun initializeCore() = catchExceptions {
        if (isGameLoaded) return@catchExceptions
        Log.i(TAG_LOG, "Libretro core initialization started")
        when {
            data.gameFilePath != null -> loadGameFromPath(data.gameFilePath!!)
            data.gameFileBytes != null -> loadGameFromBytes(data.gameFileBytes!!)
            data.gameVirtualFiles.isNotEmpty() -> loadGameFromVirtualFiles(data.gameVirtualFiles)
        }
        data.saveRAMState?.let {
            LibretroDroid.unserializeSRAM(data.saveRAMState)
            data.saveRAMState = null
        }
        LibretroDroid.onSurfaceCreated()
        isGameLoaded = true
        Log.i(TAG_LOG, "Libretro core initialization complete")

        KtUtils.runOnUIThread {
            lifecycle?.addObserver(RenderLifecycleObserver())
        }
    }

    private fun loadGameFromVirtualFiles(virtualFiles: List<VirtualFile>) {
        val detachedVirtualFiles = virtualFiles
            .map { DetachedVirtualFile(it.virtualPath, it.fileDescriptor.detachFd()) }
        LibretroDroid.loadGameFromVirtualFiles(detachedVirtualFiles)
    }

    private fun loadGameFromBytes(gameFileBytes: ByteArray) {
        LibretroDroid.loadGameFromBytes(gameFileBytes)
    }

    private fun loadGameFromPath(gameFilePath: String) {
        LibretroDroid.loadGameFromPath(gameFilePath)
    }

    private fun catchExceptions(block: () -> Unit) {
        try {
            if (isAborted) return
            block()
        } catch (e: RetroException) {
            Log.e(TAG_LOG, "Libretro error code=${e.errorCode}", e)
            GlobalScope.launch {
                retroGLIssuesErrors.emit(e.errorCode)
            }
            isAborted = true
        } catch (e: Exception) {
            Log.e(TAG_LOG, "Error in GLRetroView", e)
            GlobalScope.launch {
                retroGLIssuesErrors.emit(LibretroDroid.ERROR_GENERIC)
            }
        }
    }

    private fun <T> runOnGLThread(block: () -> T): T {
        if (Thread.currentThread().name.startsWith("GLThread")) {
            return block()
        }

        val latch = CountDownLatch(1)
        var result: T? = null
        queueEvent {
            result = block()
            latch.countDown()
        }

        latch.awaitUninterruptibly()
        return result!!
    }

    private fun buildShader(config: ShaderConfig): GLRetroShader {
        return when (config) {
            is ShaderConfig.Default -> GLRetroShader(LibretroDroid.SHADER_DEFAULT)
            is ShaderConfig.CRT -> GLRetroShader(LibretroDroid.SHADER_CRT)
            is ShaderConfig.LCD -> GLRetroShader(LibretroDroid.SHADER_LCD)
            is ShaderConfig.Sharp -> GLRetroShader(LibretroDroid.SHADER_SHARP)
            is ShaderConfig.CUT -> GLRetroShader(
                LibretroDroid.SHADER_UPSCALE_CUT,
                buildParams(
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_SHARPNESS_MIN to config.sharpnessMin.toString(),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_SHARPNESS_MAX to config.sharpnessMax.toString(),
                )
            )
            is ShaderConfig.CUT2 -> GLRetroShader(
                LibretroDroid.SHADER_UPSCALE_CUT2,
                buildParams(
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_SHARPNESS_MAX to config.sharpnessMax.toString(),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_SHARPNESS_BIAS to config.sharpnessBias.toString(),
                )
            )
        }
    }

    private fun buildParams(vararg pairs: Pair<String, String?>): Map<String, String> {
        return pairs
            .filter { (key, value) -> value != null }
            .associate { (key, value) -> key to value!! }
    }

    /** This function gets called from the jni side.*/
    private fun sendRumbleEvent(port: Int, strengthWeak: Float, strengthStrong: Float) {
        lifecycle?.coroutineScope?.launch {
            rumbleEventsSubject.emit(RumbleEvent(port, strengthWeak, strengthStrong))
        }
    }

    sealed class GLRetroEvents {
        object FrameRendered: GLRetroEvents()
        object SurfaceCreated: GLRetroEvents()
    }

    companion object {
        private val TAG_LOG = GLRetroView::class.java.simpleName
        // LibretroDroid is a process-wide singleton; every GLRetroView must
        // serialize native calls and teardown against the same monitor.
        private val coreLock = Any()
        @Volatile private var nativeOwner: GLRetroView? = null

        const val MOTION_SOURCE_DPAD = LibretroDroid.MOTION_SOURCE_DPAD
        const val MOTION_SOURCE_ANALOG_LEFT = LibretroDroid.MOTION_SOURCE_ANALOG_LEFT
        const val MOTION_SOURCE_ANALOG_RIGHT = LibretroDroid.MOTION_SOURCE_ANALOG_RIGHT
        const val MOTION_SOURCE_POINTER = LibretroDroid.MOTION_SOURCE_POINTER

        const val ERROR_LOAD_LIBRARY = LibretroDroid.ERROR_LOAD_LIBRARY
        const val ERROR_LOAD_GAME = LibretroDroid.ERROR_LOAD_GAME
        const val ERROR_GL_NOT_COMPATIBLE = LibretroDroid.ERROR_GL_NOT_COMPATIBLE
        const val ERROR_SERIALIZATION = LibretroDroid.ERROR_SERIALIZATION
        const val ERROR_CHEAT = LibretroDroid.ERROR_CHEAT
        const val ERROR_GENERIC = LibretroDroid.ERROR_GENERIC
    }
}
