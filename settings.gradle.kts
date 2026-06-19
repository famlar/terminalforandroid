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
        // Pty4J 发布在 JetBrains Space 仓库
        maven("https://packages.jetbrains.team/maven/p/pty4j/pty4j")
        maven("https://jetbrains.bintray.com/pty4j")
    }
}

rootProject.name = "SSHTerminal"
include(":app")
