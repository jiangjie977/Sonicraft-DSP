#pragma once
// host-harness shim：宿主机无 NDK 头文件——parametric_eq.hpp
// 直接 include <android/log.h> 致 harness 编译断（harness 落后于 core 头文件演进）。
// 日志调用降为无操作宏，算法路径零影响；NDK 侧不受此文件影响（不在其 include 路径）。
#ifndef __android_log_print
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define __android_log_print(...) ((void)0)
#define __android_log_write(...) ((void)0)
#endif
