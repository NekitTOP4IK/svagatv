package com.github.andreyasadchy.xtra.svagaplus

import com.github.andreyasadchy.xtra.model.chat.NamePaint

data class SvagaBadge(
    val id: String,
    val source: String,
    val title: String,
    val url: String,
    val rarity: String? = null,
    val isAnimated: Boolean = false,
    val rank: Int = 0,
    val page: String? = null,
)

data class SvagaFontPreset(
    val id: String,
    val source: String? = null,
    val googleFontsUrl: String? = null,
    val cdnPath: String? = null,
    val fontFamily: String? = null,
    val sizeAdjustPercent: Float? = null,
    val isPixelFont: Boolean = false,
)

data class SvagaViewerProfile(
    val login: String,
    val badges: List<SvagaBadge> = emptyList(),
    val nameColor: Int? = null,
    val nameGradient: String? = null,
    val nameCss: String? = null,
    val namePresetName: String? = null,
    val fontPresetId: String? = null,
    val paint: NamePaint? = null,
) {
    val hasVisualData: Boolean
        get() = badges.isNotEmpty() || nameColor != null || paint != null || fontPresetId != null
}

data class SvagaBadgeCard(
    val kind: String,
    val name: String,
    val imageUrl: String,
    val rarity: String? = null,
    val description: String? = null,
    val howToGet: String? = null,
    val owners: Int = 0,
    val channel: String? = null,
    val period: String? = null,
    val pageUrl: String,
) {
    fun rarityLabelRu(): String? = when (rarity) {
        "common" -> "Обычный"
        "rare" -> "Редкий"
        "epic" -> "Эпический"
        "mythic" -> "Мифический"
        "service" -> "Служебный"
        else -> null
    }

    fun kindLabelRu(): String = when (kind) {
        "sub" -> if (!channel.isNullOrBlank()) "Подписка на $channel" else "За подписку"
        "social" -> listOfNotNull("Соц. рейтинг", period?.takeIf { it.isNotBlank() }).joinToString(" · ")
        else -> "Коллекционный"
    }

    fun ownersLabelRu(): String {
        val word = if (kind == "sub") {
            pluralRu(owners, "подписчик с ним", "подписчика с ним", "подписчиков с ним")
        } else {
            pluralRu(owners, "владелец", "владельца", "владельцев")
        }
        return "$owners $word"
    }

    companion object {
        fun pluralRu(n: Int, one: String, few: String, many: String): String {
            val mod100 = kotlin.math.abs(n) % 100
            val mod10 = mod100 % 10
            return when {
                mod100 in 11..19 -> many
                mod10 == 1 -> one
                mod10 in 2..4 -> few
                else -> many
            }
        }
    }
}

data class SvagaChannelSocialStatus(
    val channelLogin: String,
    val ratingEnabled: Boolean,
    val activityPublic: Boolean,
)

data class SvagaSocialRating(
    val channelLogin: String,
    val viewerLogin: String,
    val swagScore: Int,
    val socialScore: Int = 0,
    val enabled: Boolean = true,
) {
    val isLowRating: Boolean get() = swagScore < 0
}

data class SvagaVoteResult(
    val ok: Boolean,
    val swagScore: Int? = null,
    val socialScore: Int? = null,
    val error: String? = null,
    val nextVoteAtMs: Long? = null,
)

data class SvagaViewerAccount(
    val token: String,
    val twitchLogin: String,
    val avatarUrl: String? = null,
    val telegramLinked: Boolean = false,
    val colorBanned: Boolean = false,
    val colorBanReason: String? = null,
    val lastCheckedAt: Long = 0L,
)

enum class SvagaSnapshotMode {
    SNAPSHOT,
    UNKNOWN,
    LEGACY,
}

sealed interface SvagaSnapshotFetchResult {
    data class Ok(
        val rev: Int,
        val globalRev: Int,
        val etag: String,
        val badges: Map<String, SvagaBadge>,
        val fontPresets: Map<String, SvagaFontPreset>,
        val viewers: Map<String, SvagaRawViewerEntry>,
    ) : SvagaSnapshotFetchResult

    data object NotModified : SvagaSnapshotFetchResult
    data object ChannelUnknown : SvagaSnapshotFetchResult
    data object MissingRoute : SvagaSnapshotFetchResult
    data object Failed : SvagaSnapshotFetchResult
}

data class SvagaRawViewerEntry(
    val login: String,
    val badgeIds: List<String> = emptyList(),
    val directBadges: List<SvagaBadge> = emptyList(),
    val nameColor: String? = null,
    val nameGradient: String? = null,
    val nameCss: String? = null,
    val namePresetName: String? = null,
    val fontPresetId: String? = null,
)

data class SvagaBatchBadgesResult(
    val ok: Boolean,
    val notFound: Boolean = false,
    val statusCode: Int = 0,
    val badges: Map<String, SvagaBadge> = emptyMap(),
    val fontPresets: Map<String, SvagaFontPreset> = emptyMap(),
    val viewers: Map<String, SvagaRawViewerEntry> = emptyMap(),
)
