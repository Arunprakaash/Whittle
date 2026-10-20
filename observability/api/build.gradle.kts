plugins {
    alias(libs.plugins.whittle.jvm.library)
}

dependencies {
    api(project(":core:common"))
    api(project(":core:config"))
    api(project(":core:model"))
}
