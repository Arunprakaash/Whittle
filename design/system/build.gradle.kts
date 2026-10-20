plugins {
    alias(libs.plugins.whittle.android.compose.library)
}

android {
    namespace = "app.whittle.design"
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

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateDesignTokens, GenerateDesignTokensTask::outputDirectory)
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateSprites, GenerateSpriteKotlinTask::outputDirectory)
    }
}
