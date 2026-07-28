//
// Created by yaoqi.li on 2022/5/31.
// TODO 为后续实现关键日志输出作准备
//

#ifndef ANDROIDSC_LOG_H
#define ANDROIDSC_LOG_H

#endif //ANDROIDSC_LOG_H

#ifdef NATIVE_LOG
#   define  LD_TAG  "EVNative"
#   define  LOGV(...)  nativeLogger(ANDROID_LOG_VERBOSE, LD_TAG, __LINE__, __FILE__, __VA_ARGS__)
#   define  LOGD(...)  nativeLogger(ANDROID_LOG_DEBUG, LD_TAG, __LINE__, __FILE__, __VA_ARGS__)
#   define  LOGI(...)  nativeLogger(ANDROID_LOG_INFO, LD_TAG, __LINE__, __FILE__, __VA_ARGS__)
#   define  LOGW(...)  nativeLogger(ANDROID_LOG_WARN, LD_TAG, __LINE__, __FILE__, __VA_ARGS__)
#   define  LOGE(...)  nativeLogger(ANDROID_LOG_ERROR, LD_TAG, __LINE__, __FILE__, __VA_ARGS__)
#   define  LOGKEY(...) nativeLogger(ANDROID_LOG_INFO, LD_TAG, __LINE__, __FILE__, __VA_ARGS__)
#else
#   define  LD_TAG  "Controller "
#   define  LOGV(...)
#   define  LOGD(...)
#   define  LOGI(...)
#   define  LOGW(...)
#   define  LOGE(...)
#   define  LOGKEY(...)  nativeLogger(ANDROID_LOG_INFO, LD_TAG, -1, 0, __VA_ARGS__)
#endif //NATIVE_LOG