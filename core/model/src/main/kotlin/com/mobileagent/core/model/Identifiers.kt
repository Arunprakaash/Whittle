package com.mobileagent.core.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class RunId(val value: String)

@Serializable
@JvmInline
value class ToolCallId(val value: String)

@Serializable
@JvmInline
value class ProviderId(val value: String)

@Serializable
@JvmInline
value class ModelId(val value: String)
