plugins {
    alias(libs.plugins.mobileagent.jvm.library)
}

dependencies {
    api(project(":llm:api"))
    api(libs.okhttp)
}
