package app.whittle.dev.cli

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.Secret
import app.whittle.core.model.ProviderId
import app.whittle.llm.api.CredentialsProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Developer-only credentials: reads "<provider-id>=<key>" lines from a local file outside the repo.
 * The app itself uses the encrypted on-device vault instead.
 */
class DevKeyFileCredentials(private val file: Path) : CredentialsProvider {
    override suspend fun apiKeyFor(providerId: ProviderId): Outcome<Secret> {
        if (!Files.isRegularFile(file)) {
            return Outcome.Failure(HarnessError.Authentication("Key file not found: $file"))
        }
        val properties = Properties().apply { Files.newBufferedReader(file).use(::load) }
        val key = properties.getProperty(providerId.value)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Outcome.Failure(HarnessError.Authentication("No '${providerId.value}' entry in $file"))
        return Outcome.Success(Secret(key))
    }
}
