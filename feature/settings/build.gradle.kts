plugins {
    alias(libs.plugins.whittle.multiplatform.compose.library)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Settings, drawn the same on the phone, a Mac and in a browser. They're the phone's: a computer changes them over its
 * link, through a [app.whittle.settings.ui.SettingsHost], and what only the phone can do stays there.
 */
kotlin {
    android {
        namespace = "app.whittle.settings.ui"
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
            api(project(":feature:files"))
            api(libs.kotlinx.serialization.json)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "app.whittle.settings.ui.resources"
}
