buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // classpath("androidx.navigation:navigation-safe-args-gradle-plugin:2.9.6")  // Removed
        // classpath("com.google.dagger:hilt-android-gradle-plugin:2.48")  // Removed - use version in plugins
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // id("androidx.navigation.safeargs.kotlin")  // Removed
    // id("kotlin-kapt")  // Removed - kapt is enabled by Kotlin plugin
    // id("kotlin-parcelize")  // Removed - parcelize is enabled by Kotlin plugin
    id("com.google.dagger.hilt.android") version "2.48"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
    id("com.google.devtools.ksp") version "1.9.22-1.0.17"
}

android {
    namespace = "com.liskovsoft.smartyoutubetv2"  // Updated namespace
    compileSdk = 34

    defaultConfig {
        applicationId = "com.liskovsoft.smartyoutubetv2"
        minSdk = 22
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            "-Xcontext-receivers"
        )
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        disable += setOf("MissingTranslation")
    }

    // 可选：为 Release 生成 Baseline Profile（之后添加）
    // experimentalProperties["android.experimental.r8.dex-startup-optimization"] = true
}

dependencies {
    // Media3 individual versions (BOM removed)
    implementation("androidx.media3:media3-exoplayer:1.4.0")
    implementation("androidx.media3:media3-ui:1.4.0")
    implementation("androidx.media3:media3-session:1.4.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.48")
    ksp("com.google.dagger:hilt-compiler:2.48")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // AndroidX 基础
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Leanback / TV
    implementation("androidx.leanback:leanback:1.1.0")
    implementation("androidx.leanback:leanback-paging:1.1.0") // 可选

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.6.2")

    // Media / ExoPlayer 生态
    implementation("androidx.media:media:1.6.0")

    // 预览进度条：与 Media3 版本适配 (commented out due to 401 error)
    // implementation("io.github.rubensousa:previewseekbar-media3:2.23.0") {
    //     exclude(group = "com.amazon.android")
    // }

    // 网络 & 数据
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // 图片加载（可选：Coil 或 Glide 二选一）
    implementation("io.coil-kt:coil:2.5.0")

    // UI / Material（TV 上谨慎使用过多动画）
    implementation("com.google.android.material:material:1.11.0")

    // 测试
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
