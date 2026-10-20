plugins {
    alias(libs.plugins.whittle.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":tools:api"))
    api(project(":core:config"))
    implementation(libs.kotlinx.serialization.json)
}
