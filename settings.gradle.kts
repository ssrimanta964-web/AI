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
        // Vosk's Android library is published via JitPack, not Maven Central.
        // UNVERIFIED: confirm this is still how Vosk is distributed by
        // checking https://github.com/alphacep/vosk-api before relying on it.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PeterMinimal"
include(":app")
