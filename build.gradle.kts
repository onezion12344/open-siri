buildscript {
    dependencies {
        // Force javapoet 1.13.0 across the plugin classpath.
        // Hilt 2.56's AggregateDepsTask worker calls ClassName.canonicalName()
        // (added in javapoet 1.13.0) but Gradle may resolve an older transitive.
        classpath("com.squareup:javapoet:1.13.0")
    }
}

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
