plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.spotless) apply false
}

subprojects {
    apply(plugin = "com.diffplug.spotless")

    // okhttp 5.x ships Kotlin 2.2 metadata while the project compiles with
    // Kotlin 2.0: skip the metadata version check (consuming its API only).
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("**/*.kt")
            targetExclude("**/build/**")
            ktlint(libs.versions.ktlint.get())
                .editorConfigOverride(
                    // Uppercase names are idiomatic for @Composable functions.
                    mapOf("ktlint_function_naming_ignore_when_annotated_with" to "Composable"),
                )
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(libs.versions.ktlint.get())
        }
    }
}