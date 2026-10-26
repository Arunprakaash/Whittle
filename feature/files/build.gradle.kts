plugins {
    alias(libs.plugins.whittle.multiplatform.compose.library)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The file browser, drawn the same on the phone, a Mac and in a browser: the workspace's folders and files, and what a
 * chat changed. It reads them through a [app.whittle.files.ui.FilesHost]: the phone's own files, or over a computer's link.
 */
kotlin {
    android {
        namespace = "app.whittle.files.ui"
        compileSdk {
            version = release(libs.versions.androidCompileSdk.get().toInt()) {
                minorApiLevel = libs.versions.androidCompileSdkMinor.get().toInt()
            }
        }
        minSdk = libs.versions.androidMinSdk.get().toInt()
        // The screen's words and images ship as Android resources there.
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":design:system"))
            api(libs.kotlinx.serialization.json)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "app.whittle.files.ui.resources"
}
