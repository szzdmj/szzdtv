pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // Resolve AndroidX/Media3 and io.github.* from official repos
        google()
        mavenCentral()

        // Only use JitPack for com.github.* if you truly need it
        exclusiveContent {
            forRepository {
                maven("https://jitpack.io")
            }
            filter {
                includeGroupByRegex("com\\.github\\..*")
            }
        }
    }
}
