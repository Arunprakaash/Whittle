plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

/** Whittle on a computer: the phone's chats in a Mac window or a browser tab, drawn by the same design system. */
kotlin {
    jvm("desktop") { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jvmTarget.get())) } }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser { commonWebpackConfig { outputFileName = "companion.js" } }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":feature:link"))
            implementation(libs.compose.mp.resources)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        // The desktop main thread is Swing's.
        named("desktopMain") {
            dependencies {
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
            }
        }
        wasmJsMain {
            dependencies { implementation(libs.ktor.client.js) }
            // The page serves the design system's font files beside itself.
            resources.srcDir(rootProject.file("design/system/src/androidMain/res/font"))
        }
        named("desktopTest") { dependencies { implementation(libs.kotlin.test.junit) } }
    }
}

compose.desktop {
    application {
        mainClass = "app.whittle.companion.MainKt"
    }
}

compose.resources {
    packageOfResClass = "app.whittle.companion.resources"
}
