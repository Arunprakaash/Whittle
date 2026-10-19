plugins {
    alias(libs.plugins.mobileagent.android.library)
}

android {
    namespace = "com.mobileagent.storage.secrets"
}

dependencies {
    api(project(":core:config"))
    api(project(":llm:api"))
    implementation(libs.tink.android)
    implementation(libs.androidx.datastore.preferences)
}
