package app.whittle.apps

import app.whittle.core.common.Outcome
import app.whittle.core.common.getOrNull
import java.security.cert.X509Certificate

/** A [SigningKeys] that hands back what [result] gives; it never makes a key of its own. */
class FakeKeys(private val result: () -> Outcome<SigningKey>) : SigningKeys {
    constructor(outcome: Outcome<SigningKey>) : this({ outcome })

    override fun keyFor(packageName: String): Outcome<SigningKey> = result()

    override fun existingCertificate(packageName: String): X509Certificate? = result().getOrNull()?.certificate
}
