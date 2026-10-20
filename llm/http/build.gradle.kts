plugins {
    alias(libs.plugins.whittle.jvm.library)
}

dependencies {
    api(project(":llm:api"))
    api(libs.okhttp)
}
