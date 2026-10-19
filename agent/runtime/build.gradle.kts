plugins {
    alias(libs.plugins.mobileagent.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:config"))
    api(project(":llm:api"))
    api(project(":tools:api"))
    api(project(":policy:engine"))
    api(project(":observability:api"))
}
