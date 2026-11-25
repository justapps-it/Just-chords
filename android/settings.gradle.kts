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
        // NewPipeExtractor (audio of YouTube links) is published on JitPack only.
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\.(teamnewpipe|TeamNewPipe).*") } }
    }
}
rootProject.name = "JustChords"
include(":app")
