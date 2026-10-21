plugins {
    alias(libs.plugins.whittle.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:config"))
    api(project(":tools:api"))
    api(libs.kotlinx.serialization.json)
}
