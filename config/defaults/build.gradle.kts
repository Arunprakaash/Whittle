plugins {
    alias(libs.plugins.whittle.jvm.library)
}

dependencies {
    api(project(":core:config"))

    testImplementation(project(":llm:api"))
    testImplementation(project(":llm:openrouter-auth"))
    testImplementation(project(":policy:engine"))
    testImplementation(project(":agent:runtime"))
    testImplementation(project(":tools:presentation"))
    testImplementation(project(":tools:device"))
    testImplementation(project(":tools:web"))
    testImplementation(libs.okhttp)
}
