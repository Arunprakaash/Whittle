plugins {
    alias(libs.plugins.whittle.multiplatform.compose.library)
    alias(libs.plugins.kotlin.serialization)
}

/** What the phone and a computer say to each other over the link: the phone's screens' state, and what the computer asks. */
kotlin {
    android {
        namespace = "app.whittle.link"
        compileSdk {
            version = release(libs.versions.androidCompileSdk.get().toInt()) {
                minorApiLevel = libs.versions.androidCompileSdkMinor.get().toInt()
            }
        }
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":feature:chat"))
            api(project(":feature:files"))
            api(project(":feature:settings"))
            api(libs.kotlinx.serialization.json)
        }
    }
}

