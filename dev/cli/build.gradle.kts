plugins {
    alias(libs.plugins.whittle.jvm.library)
    application
}

application {
    mainClass.set("app.whittle.dev.cli.MainKt")
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
