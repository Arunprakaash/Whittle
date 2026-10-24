plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
    implementation(libs.xz)

    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    useJUnit()
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = libs.plugins.whittle.jvm.library.get().pluginId
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = libs.plugins.whittle.android.library.get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidComposeLibrary") {
            id = libs.plugins.whittle.android.compose.library.get().pluginId
            implementationClass = "AndroidComposeLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = libs.plugins.whittle.android.application.get().pluginId
            implementationClass = "AndroidApplicationConventionPlugin"
        }
    }
}
