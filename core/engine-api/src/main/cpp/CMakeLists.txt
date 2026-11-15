# Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program. If not, see <https://gnu.org>.

cmake_minimum_required(VERSION 3.22.1)
project("engine_api")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(COMMON_SECURITY_FLAGS "-O3 -flto=thin -fvisibility=hidden -ffunction-sections -fdata-sections -fsanitize=cfi -fstack-protector-strong -D_FORTIFY_SOURCE=3 -fno-exceptions -fno-rtti")

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${COMMON_SECURITY_FLAGS} -ffixed-x18 -fsanitize=shadow-call-stack")
else()
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${COMMON_SECURITY_FLAGS}")
endif()

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -flto=thin -fsanitize=cfi -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,relro,-z,now -Wl,-z,noexecstack")

add_library(engine_api SHARED native_security_arena.cpp)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(engine_api ${log-lib} ${android-lib})