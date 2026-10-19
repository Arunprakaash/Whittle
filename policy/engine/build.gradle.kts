plugins {
    alias(libs.plugins.mobileagent.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":tools:api"))
    api(project(":llm:api"))
}
