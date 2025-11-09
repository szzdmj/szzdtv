pluginManagement {
    repositories {
        google()
        mavenCentral()
        // maven("https://jitpack.io") // 仅在确需 GitHub JitPack 依赖时解注
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 内容过滤：减少无效探测
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroup("com.google.android")
                includeGroupByRegex("com\\.google\\..*")
            }
        }
        mavenCentral {
            content {
                excludeGroupByRegex("androidx\\..*")
                excludeGroup("com.google.android")
                excludeGroupByRegex("com\\.google\\..*")
            }
        }
    }
}

rootProject.name = "TvApp"
include(":app")
