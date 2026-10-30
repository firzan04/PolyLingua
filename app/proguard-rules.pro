# Used only when building with -Ppolylingua.minify=true.

# JNI entry points and callbacks looked up by name from C++.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class com.polylingua.edge.nativebridge.** { *; }
-keep interface com.polylingua.edge.nativebridge.LlamaNative$TokenCallback { *; }

# WebRTC (reflection + JNI from libjingle_peerconnection_so.so)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# TarsosDSP (vendored) - reflection-free, but keep public API used via interfaces
-keep class be.tarsos.dsp.** { *; }
-dontwarn be.tarsos.dsp.**

# Apache Commons Compress optional codecs (xz, zstd, brotli, asm) are not on the classpath.
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.framework.**

# OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
