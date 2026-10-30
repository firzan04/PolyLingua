// Shared JNI helpers for PolyLingua Edge native engines.
#pragma once

#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#define PL_TAG "PolyLinguaNative"
#define PL_LOGI(...) __android_log_print(ANDROID_LOG_INFO, PL_TAG, __VA_ARGS__)
#define PL_LOGW(...) __android_log_print(ANDROID_LOG_WARN, PL_TAG, __VA_ARGS__)
#define PL_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, PL_TAG, __VA_ARGS__)

namespace polylingua {

// Converts a (possibly null) Java string to std::string (UTF-8).
inline std::string JStringToStd(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return std::string();
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Builds a java.lang.String from arbitrary standard UTF-8 bytes. NewStringUTF() expects
// *modified* UTF-8 and aborts under CheckJNI for 4-byte sequences (emoji, rare IPA), so the
// bytes are routed through new String(byte[], "UTF-8") instead.
inline jstring NewStringUtf8Safe(JNIEnv* env, const std::string& utf8) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(utf8.size()));
    if (bytes == nullptr) return nullptr;
    if (!utf8.empty()) {
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(utf8.size()),
                                reinterpret_cast<const jbyte*>(utf8.data()));
    }
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(stringClass, ctor, bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(stringClass);
    return result;
}

// Escapes a UTF-8 string for embedding inside a JSON string literal.
inline std::string JsonEscape(const std::string& in) {
    std::string out;
    out.reserve(in.size() + 8);
    for (unsigned char c : in) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

// Copies a Java float[] into a std::vector (n <= array length is enforced).
inline std::vector<float> CopyFloatArray(JNIEnv* env, jfloatArray arr, jint n) {
    std::vector<float> out;
    if (arr == nullptr) return out;
    jsize len = env->GetArrayLength(arr);
    if (n < 0 || n > len) n = len;
    out.resize(static_cast<size_t>(n));
    if (n > 0) env->GetFloatArrayRegion(arr, 0, n, out.data());
    return out;
}

// Returns the length of the longest prefix of `bytes` that ends on a complete UTF-8 code point.
inline size_t CompleteUtf8Prefix(const std::string& bytes) {
    const size_t n = bytes.size();
    if (n == 0) return 0;
    // Walk back over at most 3 continuation bytes to find the last lead byte.
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = static_cast<unsigned char>(bytes[i - 1]);
        if ((c & 0xC0) != 0x80) {
            size_t need = 1;
            if ((c & 0x80) == 0x00) need = 1;
            else if ((c & 0xE0) == 0xC0) need = 2;
            else if ((c & 0xF0) == 0xE0) need = 3;
            else if ((c & 0xF8) == 0xF0) need = 4;
            size_t have = n - (i - 1);
            return have >= need ? n : (i - 1);
        }
        --i;
        ++back;
    }
    return n;  // malformed tail: emit as-is rather than stalling forever
}

}  // namespace polylingua
