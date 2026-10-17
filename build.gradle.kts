buildscript {
    dependencies {
        // AGP 9's built-in Kotlin embeds KGP 2.2.10 by default; pin a newer Kotlin
        // Gradle Plugin explicitly (this is the documented way to override it).
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
