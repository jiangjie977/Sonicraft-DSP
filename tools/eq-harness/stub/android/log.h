#pragma once
#include <cstdio>
#include <cstdarg>
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_INFO 4
inline void __android_log_print(int, const char*, const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    std::vprintf(fmt, ap);
    va_end(ap);
    std::printf("\n");
}