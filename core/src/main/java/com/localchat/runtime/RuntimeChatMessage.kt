package com.localchat.runtime

/**
 * Minimal chat turn used by the pure context-window logic ([ContextWindow]) and the runtime
 * interface. Kept here in `:core` (no Android types) so both the app runtime and JVM tests share it.
 */
data class RuntimeChatMessage(
    val role: RuntimeChatRole,
    val text: String,
)

enum class RuntimeChatRole {
    User,
    Model,
}
