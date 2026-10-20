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

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateDesignTokens, GenerateDesignTokensTask::outputDirectory)
    }
}
