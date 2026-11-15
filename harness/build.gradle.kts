plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    // litertlm-jvm 0.12.0 is compiled for Java 21 (class file v65) — must run on JDK 21+.
    jvmToolchain(21)
}

dependencies {
    // The exact same pure logic the app runs on-device — no source-dir hacks.
    implementation(project(":core"))
    implementation(libs.litertlm.jvm)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation(libs.jsoup)
    implementation("org.json:json:20240303")
    implementation("javax.inject:javax.inject:1")
}

application {
    mainClass.set("com.localchat.harness.MainKt")
}
