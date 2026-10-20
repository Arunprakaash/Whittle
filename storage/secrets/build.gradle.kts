plugins {
    alias(libs.plugins.whittle.android.library)
}

android {
    namespace = "app.whittle.storage.secrets"
}

dependencies {
    api(project(":core:config"))
    api(project(":llm:api"))
    implementation(libs.tink.android)
    implementation(libs.androidx.datastore.preferences)
}
