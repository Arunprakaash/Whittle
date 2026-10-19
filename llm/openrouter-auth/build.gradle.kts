plugins {
    alias(libs.plugins.mobileagent.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:config"))
    api(project(":llm:api"))
    implementation(libs.okhttp)

    testImplementation(libs.okhttp.mockwebserver)
}
