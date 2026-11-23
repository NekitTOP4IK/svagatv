package com.github.andreyasadchy.xtra.svagaplus

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks 404 (not-found) and transient network/5xx/429 cooldowns per Svaga+ endpoint
 * so non-Svaga channels never trigger repeated backend requests.
 */
class SvagaRequestCooldown(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    enum class Kind {
        NOT_FOUND,
        TRANSIENT,
    }

    data class Entry(
        val kind: Kind,
        val untilMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun isBlocked(key: String): Boolean {
        val entry = entries[key] ?: return false
        if (entry.untilMs <= nowMs()) {
            entries.remove(key, entry)
            return false
        }
        return true
    }

    fun get(key: String): Entry? {
        val entry = entries[key] ?: return null
        if (entry.untilMs <= nowMs()) {
            entries.remove(key, entry)
            return null
        }
        return entry
    }

    fun mark(key: String, kind: Kind) {
        val ttl = when (kind) {
            Kind.NOT_FOUND -> NOT_FOUND_TTL_MS
            Kind.TRANSIENT -> TRANSIENT_TTL_MS
        }
        entries[key] = Entry(kind = kind, untilMs = nowMs() + ttl)
    }

    fun markFromHttpStatus(key: String, statusCode: Int) {
        when {
            statusCode == 404 -> mark(key, Kind.NOT_FOUND)
            statusCode == 429 || statusCode >= 500 || statusCode <= 0 -> mark(key, Kind.TRANSIENT)
        }
    }

    fun clear(key: String) {
        entries.remove(key)
    }

    fun clearAll() {
        entries.clear()
    }

    companion object {
        const val NOT_FOUND_TTL_MS = 10 * 60 * 1000L
        const val TRANSIENT_TTL_MS = 30 * 1000L

        fun channelBadgesKey(channelLogin: String): String =
            "channel-badges:${channelLogin.trim().lowercase()}"

        fun ratingKey(channelLogin: String, viewerLogin: String): String =
            "rating:${channelLogin.trim().lowercase()}:${viewerLogin.trim().lowercase()}"
    }
}
