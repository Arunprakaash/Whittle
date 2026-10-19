package com.mobileagent.dev.cli

import com.mobileagent.core.common.HarnessError
import com.mobileagent.core.common.Outcome
import com.mobileagent.core.common.Secret
import com.mobileagent.core.model.ProviderId
import com.mobileagent.llm.api.CredentialsProvider
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
