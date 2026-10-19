plugins {
    alias(libs.plugins.mobileagent.jvm.library)
}

dependencies {
    api(project(":core:common"))
    api(project(":core:config"))
    api(project(":core:model"))
}
