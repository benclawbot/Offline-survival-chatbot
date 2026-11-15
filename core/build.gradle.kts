plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure-Kotlin shared logic (search, retrieval/RAG, agent tool dialects, PDF planning) with NO
// Android / Compose / Hilt / Room dependencies. Consumed by both the Android `:app` and the
// desktop `:harness`, so the exact same logic runs on-device and in JVM tests.
kotlin {
    // Java 17 to match :app (D8/dex friendly); the JVM harness runs on a newer JDK and is fine.
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("javax.inject:javax.inject:1")
    implementation(libs.jsoup)
    // org.json is provided by the Android platform at runtime in :app (and as a real dep in the
    // harness/tests) — compileOnly here so its classes aren't dexed into the APK (would duplicate
    // the platform's android.org.json).
    compileOnly("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
}
