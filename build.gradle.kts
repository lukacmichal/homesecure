// Verzie su zamerne tie, ktore uz mas v ~/.gradle/caches (AGP 8.7.3 + Kotlin
// 2.0.21 + Gradle 8.10.2), takze prvy build nemusi tahat novy toolchain.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
