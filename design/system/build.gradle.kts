plugins {
    alias(libs.plugins.whittle.multiplatform.compose.library)
}

// The theme's colors, type, dimensions, motion and haptics are generated from the design tokens.
val generateDesignTokens = tasks.register<GenerateDesignTokensTask>("generateDesignTokens") {
    tokensFile.set(rootProject.layout.projectDirectory.file("docs/design/tokens/whittle.tokens.json"))
    packageName.set("app.whittle.design.tokens")
    outputDirectory.set(layout.buildDirectory.dir("generated/source/designTokens"))
}

// The beaver's frames and poses are generated from its sprite file, like the tokens.
val generateSprites = tasks.register<GenerateSpriteKotlinTask>("generateSprites") {
    spriteFile.set(rootProject.layout.projectDirectory.file("docs/design/sprites/beaver.sprite.json"))
    packageName.set("app.whittle.design.tokens")
    outputDirectory.set(layout.buildDirectory.dir("generated/source/sprites"))
}

kotlin {
    android {
        namespace = "app.whittle.design"
        compileSdk {
            version = release(libs.versions.androidCompileSdk.get().toInt()) {
                minorApiLevel = libs.versions.androidCompileSdkMinor.get().toInt()
            }
        }
        minSdk = libs.versions.androidMinSdk.get().toInt()
        // The fonts stay Android resources there, for their variable weights.
        androidResources { enable = true }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateDesignTokens.flatMap { it.outputDirectory })
            kotlin.srcDir(generateSprites.flatMap { it.outputDirectory })
        }
        // Desktop reads the same font files Android bundles.
        named("desktopMain") { resources.srcDir("src/androidMain/res/font") }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "app.whittle.design.resources"
}
