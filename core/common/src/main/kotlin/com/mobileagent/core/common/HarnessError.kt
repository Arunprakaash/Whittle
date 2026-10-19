package com.mobileagent.core.common

/** Every failure the harness reports. Messages are for logs and telemetry, never shown raw to users. */
sealed interface HarnessError {
    val message: String

    data class Configuration(override val message: String) : HarnessError

    data class Serialization(override val message: String) : HarnessError

    data class Network(override val message: String) : HarnessError

    data class Provider(val statusCode: Int, override val message: String) : HarnessError

    data class Authentication(override val message: String) : HarnessError

    data class NotFound(override val message: String) : HarnessError

    data class Storage(override val message: String) : HarnessError

    data class Cancelled(override val message: String) : HarnessError
}
