plugins {
    // 若需在根引入，请保留；实际逻辑主要在子模块
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

tasks.register("cleanAll") {
    group = "build"
    description = "Cleans all build folders."
    doLast {
        rootProject.allprojects.forEach { proj ->
            delete(proj.layout.buildDirectory)
        }
    }
}
