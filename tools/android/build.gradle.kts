plugins {
    alias(libs.plugins.whittle.android.library)
}

android {
    namespace = "app.whittle.tools.android"
}

dependencies {
    api(project(":tools:api"))
    implementation(libs.kotlinx.serialization.json)
}
