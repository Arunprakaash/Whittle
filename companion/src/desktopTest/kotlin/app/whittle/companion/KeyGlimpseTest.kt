package app.whittle.companion

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyGlimpseTest {
    @Test
    fun showsTheTypeAndTheEndOfTheKey() {
        assertEquals("ed25519 …R3NezK", keyGlimpse("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ17zwjTKzWd1t3hCnWIR1toKt9zcKIJ/JQmf6R3NezK arun@MacBook-Pro-2"))
    }

    @Test
    fun keepsATypeWithoutTheSshPrefix() {
        assertEquals("ecdsa-sha2-nistp256 …QWxyz0", keyGlimpse("ecdsa-sha2-nistp256 AAAAE2VjZHNhQWxyz0"))
    }

    @Test
    fun showsAWordWithNoBodyAsItIs() {
        assertEquals("oneword", keyGlimpse("oneword"))
    }
}
