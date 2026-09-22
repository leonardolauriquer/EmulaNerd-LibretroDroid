/*
 *     Copyright (C) 2019  Filippo Scognamiglio
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

#include <iostream>
#include <fstream>
#include <unistd.h>

#include "utils.h"
#include "../log.h"

namespace libretrodroid {

Utils::ReadResult Utils::readFileAsBytes(const std::string &filePath) {
    std::ifstream fileStream(filePath);
    fileStream.seekg(0, std::ios::end);
    size_t size = fileStream.tellg();
    char* bytes = new char[size];
    fileStream.seekg(0, std::ios::beg);
    fileStream.read(bytes, size);
    fileStream.close();

    return ReadResult { size, bytes };
}

Utils::ReadResult Utils::readFileAsBytes(const int fileDescriptor) {
    FILE* file = fdopen(fileDescriptor, "r");
    if (file == nullptr) {
        close(fileDescriptor);
        return ReadResult { 0, nullptr };
    }
    size_t size = getFileSize(file);

    char* bytes = new char[size];
    fread(bytes, sizeof(char), size, file);
    // fdopen() transfers ownership of the descriptor to FILE*. Closing the
    // raw descriptor here races with the later FILE* cleanup and triggers
    // Android fdsan on physical ARM devices. fclose() releases both safely.
    fclose(file);
    return ReadResult { size, bytes };
}

size_t Utils::getFileSize(FILE* file) {
    fseek(file, 0, SEEK_SET);
    fseek(file, 0, SEEK_END);
    size_t size = ftell(file);
    fseek(file, 0, SEEK_SET);
    return size;
}

const char* Utils::cloneToCString(const std::string &input) {
    char* result = new char[input.length() + 1];
    std::strcpy(result, input.c_str());
    return result;
}

} //namespace libretrodroid
