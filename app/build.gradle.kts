plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localchat"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.localchat"
        ndk { abiFilters += "arm64-v8a" }
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "0.02"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // The offline dataset and LiteRT model are already compressed; storing them verbatim
    // keeps the single sideload APK near 1.8 GB and avoids a slow, wasteful recompression.
    androidResources { noCompress += listOf("zip") }


    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Shared pure-Kotlin logic (search, RAG/retrieval, agent dialects, PDF planning).
    implementation(project(":core"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.litertlm)

    implementation(libs.compose.markdown)
    implementation(libs.compose.markdown.m3)
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.latex)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.html)
    implementation(libs.markwon.linkify)
    implementation(libs.markwon.inline.parser)
    implementation(libs.jsoup)
    implementation(libs.pdfbox.android)
    // On-device multilingual embeddings (EmbeddingGemma) for semantic RAG.
    implementation(libs.localagents.rag)

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
}
