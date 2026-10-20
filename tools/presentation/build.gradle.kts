plugins {
    alias(libs.plugins.whittle.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:config"))
    api(libs.kotlinx.serialization.json)
}
