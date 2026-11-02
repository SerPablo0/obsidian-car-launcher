pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor and its JSON library are only published through JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "ObsidianLauncher"
include(":app")
