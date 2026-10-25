plugins {
    alias(libs.plugins.whittle.jvm.library)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(project(":llm:adapter-anthropic-messages"))
    implementation(project(":tools:device"))
    implementation(project(":tools:web"))
    implementation(project(":tools:workspace"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
