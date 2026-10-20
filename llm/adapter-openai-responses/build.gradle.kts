plugins {
    alias(libs.plugins.whittle.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":llm:api"))
    implementation(project(":llm:http"))

    testImplementation(libs.okhttp.mockwebserver)
}
