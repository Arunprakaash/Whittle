plugins {
    alias(libs.plugins.whittle.jvm.library)
}

dependencies {
    api(project(":tools:api"))
    implementation(libs.kotlinx.serialization.json)
}
