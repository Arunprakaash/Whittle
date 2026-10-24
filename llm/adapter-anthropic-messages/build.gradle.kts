plugins {
    alias(libs.plugins.whittle.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":llm:api"))
    implementation(libs.anthropic.java)

    testImplementation(libs.okhttp.mockwebserver)
}
