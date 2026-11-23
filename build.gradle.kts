// Top-level build file. AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the
// org.jetbrains.kotlin.android plugin is intentionally NOT applied anywhere.
// Applying the Compose / serialization compiler plugins at Kotlin 2.4.21 also pins
// the Kotlin Gradle Plugin used by AGP's built-in Kotlin to that version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.chaquopy) apply false
}
