// TarsosDSP 2.5 "core" module (https://github.com/JorenSix/TarsosDSP), vendored verbatim.
// Licensed GPL-3.0 (see LICENSE in this directory). Pure Java: pitch detection (YIN, FFT-YIN,
// McLeod/MPM), WSOLA time-stretching, filters, onset detection. No javax.sound dependency.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-nowarn")
}
