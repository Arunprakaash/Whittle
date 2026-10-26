package app.whittle.design.tokens

// Desktop reads them from the classpath, where the build puts Android's font files.
internal actual fun fontFile(name: String): ByteArray? =
    WhittleFonts::class.java.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
