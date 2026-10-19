plugins {
    alias(libs.plugins.mobileagent.jvm.library)
}

dependencies {
    api(project(":core:config"))

    testImplementation(project(":llm:api"))
    testImplementation(project(":llm:openrouter-auth"))
    testImplementation(project(":policy:engine"))
    testImplementation(project(":agent:runtime"))
}
