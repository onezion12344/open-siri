pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
        id("com.google.dagger.hilt.android") version "2.56" apply false
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OpenSiri"
include(":app")
