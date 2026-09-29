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
        // NewPipeExtractor is only published on JitPack.
        maven("https://jitpack.io") { content { includeGroupAndSubgroups("com.github") } }
    }
}

rootProject.name = "FireTube"
include(":app", ":core:extractor")
