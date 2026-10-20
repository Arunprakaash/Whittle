// The app every exported Whittle app is made from: one screen that runs a built web app from its own assets.
// Plain Java with no libraries keeps it tiny; Whittle ships its unsigned release APK and, when the user installs
// an app, swaps in the app's files, name, package and icon, then signs it on the phone.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.whittle.shell"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = providers.gradleProperty("whittle.exportShell.packagePlaceholder").get()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["appLabel"] = providers.gradleProperty("whittle.exportShell.labelPlaceholder").get()
    }

    buildTypes {
        release {
            // Unsigned: Whittle signs each exported app with the user's own key.
            signingConfig = null
            // R8 drops the Kotlin library the build adds by default; this app is plain Java.
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    packaging {
        resources.excludes += listOf("kotlin/**", "META-INF/*.kotlin_module", "META-INF/version-control-info.textproto", "META-INF/com/android/build/gradle/app-metadata.properties")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }
}
