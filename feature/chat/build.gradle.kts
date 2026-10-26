plugins {
    alias(libs.plugins.whittle.multiplatform.compose.library)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The chat screen, drawn the same on the phone, a Mac and in a browser. It draws a [app.whittle.chat.ui.ChatScreenState]
 * and asks for changes as [app.whittle.chat.ui.ChatAction]s: the phone answers them itself, a computer over its link.
 */
kotlin {
    android {
        namespace = "app.whittle.chat.ui"
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
            implementation(libs.kotlinx.datetime)
        }
        androidMain.dependencies { implementation(libs.androidx.activity.compose) }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "app.whittle.chat.ui.resources"
}
