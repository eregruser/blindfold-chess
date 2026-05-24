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
        // chesslib (com.github.bhlangonijr:chesslib) is only published via JitPack.
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "BlindfoldChess"
include(":app", ":engineassets")
