plugins {
    id("com.android.application") version "9.4.1" apply false
    // Kotlin support is built into AGP 9; these are the compiler plugins only.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}
