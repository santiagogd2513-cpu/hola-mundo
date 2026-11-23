// Las versiones de los plugins están en settings.gradle.kts (pluginManagement).
// Kotlin se declara aquí una sola vez para que :core y :app compartan el mismo plugin.
plugins {
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.android") apply false
}
