package com.mobileagent.llm.http

import com.mobileagent.core.common.Secret
import com.mobileagent.llm.api.ProviderDescriptor
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** Builds requests to a provider: URL from its base URL plus a path, auth header and default headers. */
object ProviderRequests {
    fun resolve(provider: ProviderDescriptor, path: String): HttpUrl =
        requireNotNull(provider.baseUrl.toHttpUrl().resolve(path)) {
            "Cannot resolve '$path' against ${provider.baseUrl} for provider ${provider.id.value}"
        }

    fun builder(provider: ProviderDescriptor, apiKey: Secret, path: String): Request.Builder =
        Request.Builder()
            .url(resolve(provider, path))
            .header(provider.auth.headerName, provider.auth.valuePrefix + apiKey.reveal())
            .apply { provider.defaultHeaders.forEach { (name, value) -> header(name, value) } }
}
