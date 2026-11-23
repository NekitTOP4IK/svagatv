package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Chatter
import com.github.andreyasadchy.xtra.model.chat.Emote

/**
 * Fast, allocation-light search and ranking engine for Emote Picker and Chat Autocomplete.
 */
object EmotePickerSearch {

    fun filterEmotes(
        emotes: Collection<Emote>,
        rawQuery: String,
        favorites: Set<String> = emptySet(),
        limit: Int = 200,
    ): List<Emote> {
        val q = rawQuery.trim().removePrefix(":").lowercase()
        if (q.isEmpty()) return emptyList()

        val scored = ArrayList<Pair<Int, Emote>>()
        val seenNames = HashSet<String>()
        for (emote in emotes) {
            val name = emote.name ?: continue
            if (!seenNames.add(name)) continue
            val matchScore = scoreEmoteMatch(name, q) ?: continue
            val favBonus = if (favorites.contains(name)) 0 else 10
            val tierScore = matchScore * 100 + favBonus + sourcePriority(emote.source)
            scored.add(tierScore to emote)
        }
        scored.sortWith(compareBy<Pair<Int, Emote>> { it.first }.thenBy { it.second.name?.length ?: 0 })
        return scored.take(limit).map { it.second }
    }

    fun filterAutoCompleteItems(
        items: List<Any?>,
        rawConstraint: CharSequence,
        favorites: Set<String> = emptySet(),
        aliasLookup: ((String?) -> String?)? = null,
        limit: Int = 60,
    ): List<Any> {
        val raw = rawConstraint.toString().trim()
        if (raw.isEmpty()) return emptyList()

        if (raw.startsWith("@")) {
            val q = raw.removePrefix("@").lowercase()
            val scoredChatters = ArrayList<Pair<Int, Chatter>>()
            for (item in items) {
                val chatter = item as? Chatter ?: continue
                val name = chatter.name ?: continue
                val login = chatter.login ?: name.lowercase()
                val alias = aliasLookup?.invoke(login)
                chatter.alias = alias

                if (q.isEmpty()) {
                    scoredChatters.add((if (alias != null) 0 else 1) to chatter)
                    continue
                }
                val best = listOfNotNull(
                    scoreTextMatch(name, q),
                    scoreTextMatch(login, q),
                    alias?.let { scoreTextMatch(it, q)?.minus(1) },
                ).minOrNull() ?: continue
                scoredChatters.add(best to chatter)
            }
            scoredChatters.sortWith(compareBy<Pair<Int, Chatter>> { it.first }.thenBy { it.second.name?.length ?: 0 })
            return scoredChatters.take(limit).map { it.second }
        }

        val hasColon = raw.startsWith(":")
        val q = raw.removePrefix(":").lowercase()
        if (q.isEmpty() || (!hasColon && q.length < 2)) return emptyList()

        val scoredEmotes = ArrayList<Pair<Int, Emote>>()
        val seen = HashSet<String>()
        for (item in items) {
            val emote = item as? Emote ?: continue
            val name = emote.name ?: continue
            if (!seen.add(name)) continue
            val match = scoreEmoteMatch(name, q) ?: continue
            val favBonus = if (favorites.contains(name)) 0 else 10
            val score = match * 100 + favBonus + sourcePriority(emote.source)
            scoredEmotes.add(score to emote)
        }
        scoredEmotes.sortWith(compareBy<Pair<Int, Emote>> { it.first }.thenBy { it.second.name?.length ?: 0 })
        return scoredEmotes.take(limit).map { it.second }
    }

    /** Lower score = better match; `null` = does not match. */
    fun scoreEmoteMatch(emoteName: String, lowerQuery: String): Int? =
        scoreTextMatch(emoteName, lowerQuery)

    private fun scoreTextMatch(candidate: String, lowerQuery: String): Int? {
        val lower = candidate.lowercase()
        if (lower == lowerQuery) return 0
        if (lower.startsWith(lowerQuery)) return 1
        val idx = lower.indexOf(lowerQuery)
        if (idx > 0) return 2
        // Subsequence match for >= 3-char queries (e.g. "poggers" / camelCase shortcuts)
        if (lowerQuery.length >= 3 && isSubsequence(lowerQuery, lower)) return 3
        return null
    }

    private fun isSubsequence(needle: String, haystack: String): Boolean {
        var ni = 0
        var hi = 0
        while (ni < needle.length && hi < haystack.length) {
            if (needle[ni] == haystack[hi]) ni++
            hi++
        }
        return ni == needle.length
    }

    fun sourcePriority(source: Int?): Int = when (source) {
        Emote.CHANNEL_STV -> 0
        Emote.PERSONAL_STV -> 1
        Emote.CHANNEL_BTTV -> 2
        Emote.CHANNEL_FFZ -> 3
        null -> 4 // Twitch subscriber/user emote
        Emote.GLOBAL_STV -> 5
        Emote.GLOBAL_BTTV -> 6
        Emote.GLOBAL_FFZ -> 7
        else -> 8
    }

    fun providerLabel(emote: Emote): String {
        val base = when (emote.source) {
            Emote.PERSONAL_STV, Emote.CHANNEL_STV -> "7TV"
            Emote.CHANNEL_BTTV -> "BTTV"
            Emote.CHANNEL_FFZ -> "FFZ"
            Emote.GLOBAL_STV -> "7TV·G"
            Emote.GLOBAL_BTTV -> "BTTV·G"
            Emote.GLOBAL_FFZ -> "FFZ·G"
            else -> "Twitch"
        }
        return if (emote.isOverlayEmote) "$base · ZW" else base
    }
}
