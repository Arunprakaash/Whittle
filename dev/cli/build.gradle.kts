plugins {
    alias(libs.plugins.mobileagent.jvm.library)
    application
}

application {
    mainClass.set("com.mobileagent.dev.cli.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

dependencies {
    implementation(project(":config:defaults"))
    implementation(project(":agent:runtime"))
    implementation(project(":llm:adapter-openai-compatible"))
    implementation(project(":llm:adapter-openai-responses"))
    implementation(libs.okhttp)
}
