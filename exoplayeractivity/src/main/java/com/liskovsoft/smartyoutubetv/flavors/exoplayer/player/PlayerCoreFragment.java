apply plugin: 'com.android.library'

android {
    namespace 'szzdtv.exoplayeractivity'
    compileSdk 34

    defaultConfig {
        minSdk 22
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }

    flavorDimensions "default"
    productFlavors {
        kids { dimension "default" }
        full { dimension "default" }
        lite { dimension "default" }
    }
}

// 相对路径常量，避免在 closure 中捕获脚本对象
def manifestRelative = 'src/main/AndroidManifest.xml'

// 使用 tasks.register，doLast 中通过 t.project 访问 projectDir（配置缓存友好）
tasks.register('generateEmptyManifest') { t ->
    outputs.file("$projectDir/${manifestRelative}")
    doLast {
        def manifestFile = new File(t.project.projectDir, manifestRelative)
        if (!manifestFile.exists()) {
            manifestFile.parentFile.mkdirs()
            // 不在 manifest 中写 package，AGP 会使用 android.namespace
            manifestFile.text = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
</manifest>'''
            println "Created empty manifest at ${manifestFile}"
        } else {
            println "Manifest already exists at ${manifestFile}"
        }
    }
}

// 以 configuration-cache 友好的方式把任务挂到 preBuild
tasks.matching { it.name == 'preBuild' }.configureEach { it.dependsOn tasks.named('generateEmptyManifest') }

dependencies {
    // Media3（核心 + UI + common）
    implementation 'androidx.media3:media3-exoplayer:1.1.1'
    implementation 'androidx.media3:media3-ui:1.1.1'
    implementation 'androidx.media3:media3-common:1.1.1'

    // 扩展模块（根据代码使用情况添加）
    implementation 'androidx.media3:media3-exoplayer-dash:1.1.1'
    implementation 'androidx.media3:media3-extractor:1.1.1'
    implementation 'androidx.media3:media3-exoplayer-hls:1.1.1'
    implementation 'androidx.media3:media3-exoplayer-smoothstreaming:1.1.1'
    implementation 'androidx.media3:media3-datasource-okhttp:1.1.1'

    // 常用 AndroidX 支持库
    implementation 'androidx.core:core-ktx:1.10.1'
    implementation 'androidx.appcompat:appcompat:1.6.1'

    // 如需 OkHttp（datasource-okhttp 可能需要）
    implementation 'com.squareup.okhttp3:okhttp:4.10.0'
}
