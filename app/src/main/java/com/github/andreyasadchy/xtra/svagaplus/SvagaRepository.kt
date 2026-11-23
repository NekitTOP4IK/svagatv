package com.github.andreyasadchy.xtra.svagaplus

import android.content.Context
import android.util.LruCache
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Central Svaga+ state coordinator:
 * - Loads per-channel v3 snapshots (`ETag`, `rev`, `global_rev`) with automatic batch fallback (`/badges`)
 * - Enforces 10-minute cooldowns on non-Svaga (`404`) channels
 * - Subscribes to realtime Socket.IO updates (`user_update`, `channel_refresh`, `viewer_refresh`, `social_rating_update`, `badge_grants_updated`)
 * - Provides non-blocking O(1) lookups (`getViewerProfile`) for `ChatAdapterUtils` during 60/120 Hz scrolling
 */
@Singleton
class SvagaRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: SvagaPlusApi,
    private val socketClient: SvagaSocketClient,
    val authManager: SvagaAuthManager,
    val aliasManager: SvagaAliasManager,
    val fontManager: SvagaFontManager,
) : SvagaSocketClient.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cooldown = SvagaRequestCooldown()

    private class ChannelState(val channelLogin: String) {
        @Volatile var mode: SvagaSnapshotMode? = null
        @Volatile var rev: Int = 0
        @Volatile var globalRev: Int = 0
        @Volatile var etag: String? = null
        val badges = ConcurrentHashMap<String, SvagaBadge>()
        val fontPresets = ConcurrentHashMap<String, SvagaFontPreset>()
        val viewers = ConcurrentHashMap<String, SvagaViewerProfile>()
        val queriedEmptyViewers = ConcurrentHashMap<String, Long>()
        val pendingBatchLogins = LinkedHashSet<String>()
        var batchTimerJob: Job? = null
        var loadingSnapshotJob: Job? = null
    }

    private val channels = ConcurrentHashMap<String, ChannelState>()
    private val badgeCardCache = LruCache<String, SvagaBadgeCard>(200)
    private val socialRatingCache = ConcurrentHashMap<String, Pair<Long, SvagaSocialRating>>()
    private val socialStatusCache = ConcurrentHashMap<String, Pair<Long, SvagaChannelSocialStatus>>()

    @Volatile
    private var activeChannel: String? = null

    private val _visualUpdates = MutableSharedFlow<String?>(extraBufferCapacity = 32)
    /** Emits a lowercase viewer login when a single chatter updated, or `null` when the whole channel refreshed. */
    val visualUpdates: SharedFlow<String?> = _visualUpdates.asSharedFlow()

    private val _ratingUpdates = MutableSharedFlow<SvagaSocialRating>(extraBufferCapacity = 32)
    val ratingUpdates: SharedFlow<SvagaSocialRating> = _ratingUpdates.asSharedFlow()

    init {
        socketClient.listener = this
        fontManager.onFontReadyCallback = {
            _visualUpdates.tryEmit(null)
        }
        scope.launch {
            aliasManager.aliases.collect {
                _visualUpdates.tryEmit(null)
            }
        }
    }

    fun isBadgesEnabled(): Boolean =
        context.prefs().getBoolean(PREF_SVAGA_BADGES_ENABLED, true)

    fun isStylesEnabled(): Boolean =
        context.prefs().getBoolean(PREF_SVAGA_STYLES_ENABLED, true)

    fun isSocialRatingEnabled(): Boolean =
        context.prefs().getBoolean(PREF_SVAGA_SOCIAL_RATING_ENABLED, true)

    fun openChannel(channelLogin: String?) {
        val normChannel = channelLogin?.trim()?.lowercase()?.takeIf { LOGIN_REGEX.matches(it) } ?: return
        activeChannel = normChannel
        if (!isBadgesEnabled() && !isStylesEnabled() && !isSocialRatingEnabled()) return

        val coolKey = SvagaRequestCooldown.channelBadgesKey(normChannel)
        if (cooldown.isBlocked(coolKey)) return

        val state = channels.getOrPut(normChannel) { ChannelState(normChannel) }
        if (state.loadingSnapshotJob?.isActive == true) return

        state.loadingSnapshotJob = scope.launch {
            val result = api.fetchChannelSnapshot(normChannel, state.etag)
            when (result) {
                is SvagaSnapshotFetchResult.Ok -> {
                    state.mode = SvagaSnapshotMode.SNAPSHOT
                    state.rev = result.rev
                    state.globalRev = result.globalRev
                    state.etag = result.etag
                    applySnapshotData(state, result.badges, result.fontPresets, result.viewers)
                    if (activeChannel == normChannel) {
                        socketClient.joinChannel(normChannel)
                    }
                    _visualUpdates.tryEmit(null)
                }
                SvagaSnapshotFetchResult.NotModified -> {
                    state.mode = SvagaSnapshotMode.SNAPSHOT
                    if (activeChannel == normChannel) {
                        socketClient.joinChannel(normChannel)
                    }
                }
                SvagaSnapshotFetchResult.ChannelUnknown -> {
                    state.mode = SvagaSnapshotMode.UNKNOWN
                    cooldown.mark(coolKey, SvagaRequestCooldown.Kind.NOT_FOUND)
                }
                SvagaSnapshotFetchResult.MissingRoute -> {
                    state.mode = SvagaSnapshotMode.LEGACY
                    if (activeChannel == normChannel) {
                        socketClient.joinChannel(normChannel)
                    }
                }
                SvagaSnapshotFetchResult.Failed -> {
                    if (state.mode == null) state.mode = SvagaSnapshotMode.LEGACY
                    cooldown.mark(coolKey, SvagaRequestCooldown.Kind.TRANSIENT)
                }
            }
        }
    }

    fun closeChannel(channelLogin: String? = null) {
        val norm = channelLogin?.trim()?.lowercase()
        if (norm == null || activeChannel == norm) {
            socketClient.leaveChannel(norm)
            if (norm == null || activeChannel == norm) {
                activeChannel = null
            }
        }
    }

    /**
     * Non-blocking lookup called from `ChatAdapterUtils.prepareChatMessage`.
     * If the channel is in `LEGACY` batch mode and [viewerLogin] hasn't been fetched yet,
     * queues a debounced background batch request automatically.
     */
    fun getViewerProfile(channelLogin: String?, viewerLogin: String?): SvagaViewerProfile? {
        if (channelLogin.isNullOrBlank() || viewerLogin.isNullOrBlank()) return null
        val normChannel = channelLogin.trim().lowercase()
        val normLogin = viewerLogin.trim().lowercase()
        val state = channels[normChannel] ?: return null
        if (state.mode == SvagaSnapshotMode.UNKNOWN) return null

        state.viewers[normLogin]?.let { return it }

        if (state.mode == SvagaSnapshotMode.LEGACY) {
            val emptyUntil = state.queriedEmptyViewers[normLogin]
            if (emptyUntil == null || emptyUntil <= System.currentTimeMillis()) {
                enqueueBatchViewerFetch(state, normLogin)
            }
        }
        return null
    }

    suspend fun getBadgeCard(page: String): SvagaBadgeCard? {
        badgeCardCache.get(page)?.let { return it }
        val fetched = api.fetchBadgeCard(page) ?: return null
        badgeCardCache.put(page, fetched)
        return fetched
    }

    suspend fun getSocialChannelStatus(channelLogin: String): SvagaChannelSocialStatus? {
        val norm = channelLogin.trim().lowercase()
        val now = System.currentTimeMillis()
        socialStatusCache[norm]?.let { (exp, cached) ->
            if (exp > now) return cached
        }
        val status = api.fetchSocialChannelStatus(norm, authManager.currentToken) ?: return null
        socialStatusCache[norm] = (now + CACHE_TTL_MS) to status
        return status
    }

    suspend fun getSocialRating(channelLogin: String, viewerLogin: String, force: Boolean = false): SvagaSocialRating? {
        if (!isSocialRatingEnabled()) return null
        val normChannel = channelLogin.trim().lowercase()
        val normViewer = viewerLogin.trim().lowercase()
        val cacheKey = "$normChannel:$normViewer"
        val coolKey = SvagaRequestCooldown.ratingKey(normChannel, normViewer)
        if (!force && cooldown.isBlocked(coolKey)) return null

        val now = System.currentTimeMillis()
        if (!force) {
            socialRatingCache[cacheKey]?.let { (exp, rating) ->
                if (exp > now) return rating
            }
        }

        val (rating, statusCode) = api.fetchSocialRating(normChannel, normViewer, authManager.currentToken)
        if (rating != null) {
            socialRatingCache[cacheKey] = (now + CACHE_TTL_MS) to rating
            return rating
        }
        cooldown.markFromHttpStatus(coolKey, statusCode)
        return null
    }

    suspend fun voteSocialRating(
        channelLogin: String,
        targetLogin: String,
        value: Int,
    ): SvagaVoteResult {
        val token = authManager.currentToken
            ?: return SvagaVoteResult(ok = false, error = "not_authenticated")
        val normChannel = channelLogin.trim().lowercase()
        val normTarget = targetLogin.trim().lowercase()
        val res = api.postSocialVote(normChannel, normTarget, value, token)
        if (res.ok && res.swagScore != null) {
            val updated = SvagaSocialRating(
                channelLogin = normChannel,
                viewerLogin = normTarget,
                swagScore = res.swagScore,
                socialScore = res.socialScore ?: 0,
                enabled = true,
            )
            socialRatingCache["$normChannel:$normTarget"] = (System.currentTimeMillis() + CACHE_TTL_MS) to updated
            _ratingUpdates.tryEmit(updated)
        }
        return res
    }

    override fun onJoinAck(channel: String, payload: JsonObject?) {
        val state = channels[channel] ?: return
        val ackRev = payload?.get("rev")?.jsonPrimitive?.intOrNull ?: return
        val ackGlobalRev = payload["global_rev"]?.jsonPrimitive?.intOrNull ?: return
        if (state.mode == SvagaSnapshotMode.SNAPSHOT && (ackRev != state.rev || ackGlobalRev != state.globalRev)) {
            scheduleSnapshotRefetch(state, withJitter = true)
        }
    }

    override fun onBadgeUpdate(channel: String, payload: JsonObject) {
        val state = channels[channel] ?: return
        val type = payload["type"]?.jsonPrimitive?.contentOrNull ?: return
        when (type) {
            "channel_refresh" -> {
                scheduleSnapshotRefetch(state, withJitter = true)
            }
            "viewer_refresh" -> {
                val data = payload["data"] as? JsonObject
                val login = data?.get("viewer")?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                if (!login.isNullOrEmpty()) {
                    state.viewers.remove(login)
                    state.queriedEmptyViewers.remove(login)
                    scheduleSnapshotRefetch(state, withJitter = true)
                    _visualUpdates.tryEmit(login)
                }
            }
            "user_update" -> {
                val data = payload["data"] as? JsonObject ?: return
                val login = data["twitch_username"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: return
                val base = api.getBackendUrl()
                val newBadges = api.parseBadgesMap(data["badges"] as? JsonObject, base)
                val newFonts = api.parseFontPresetsMap(data["font_presets"] as? JsonObject)
                if (newBadges.isNotEmpty()) state.badges.putAll(newBadges)
                if (newFonts.isNotEmpty()) {
                    state.fontPresets.putAll(newFonts)
                    fontManager.registerPresets(newFonts)
                }
                val rawViewer = api.parseSingleViewerEntry(login, data, base)
                val resolved = materializeViewerProfile(rawViewer, state.badges)
                if (resolved.hasVisualData) {
                    state.viewers[login] = resolved
                } else {
                    state.viewers.remove(login)
                }
                payload["rev"]?.jsonPrimitive?.intOrNull?.let { r ->
                    if (r > state.rev) state.rev = r
                }
                _visualUpdates.tryEmit(login)
            }
        }
    }

    override fun onSocialRatingUpdate(payload: JsonObject) {
        val ch = payload["channel"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: return
        val login = payload["login"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: return
        val swag = payload["swag_score"]?.jsonPrimitive?.intOrNull
            ?: payload["score"]?.jsonPrimitive?.intOrNull
            ?: return
        val social = payload["social_score"]?.jsonPrimitive?.intOrNull ?: 0
        val updated = SvagaSocialRating(
            channelLogin = ch,
            viewerLogin = login,
            swagScore = swag,
            socialScore = social,
            enabled = true,
        )
        socialRatingCache["$ch:$login"] = (System.currentTimeMillis() + CACHE_TTL_MS) to updated
        _ratingUpdates.tryEmit(updated)
    }

    override fun onBadgeGrantsUpdated(payload: JsonObject) {
        val ch = payload["channel"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: return
        val state = channels[ch] ?: return
        scheduleSnapshotRefetch(state, withJitter = true)
    }

    private fun scheduleSnapshotRefetch(state: ChannelState, withJitter: Boolean) {
        if (state.loadingSnapshotJob?.isActive == true) return
        state.loadingSnapshotJob = scope.launch {
            if (withJitter) {
                delay(Random.nextLong(200L, 2500L))
            }
            val result = api.fetchChannelSnapshot(state.channelLogin, state.etag)
            if (result is SvagaSnapshotFetchResult.Ok) {
                state.rev = result.rev
                state.globalRev = result.globalRev
                state.etag = result.etag
                applySnapshotData(state, result.badges, result.fontPresets, result.viewers)
                _visualUpdates.tryEmit(null)
            }
        }
    }

    private fun enqueueBatchViewerFetch(state: ChannelState, login: String) {
        val coolKey = SvagaRequestCooldown.channelBadgesKey(state.channelLogin)
        if (cooldown.isBlocked(coolKey)) return

        synchronized(state.pendingBatchLogins) {
            state.pendingBatchLogins.add(login)
            if (state.batchTimerJob?.isActive == true) return
            state.batchTimerJob = scope.launch {
                delay(80L)
                flushBatchViewerFetch(state)
            }
        }
    }

    private suspend fun flushBatchViewerFetch(state: ChannelState) {
        while (true) {
            val chunk = synchronized(state.pendingBatchLogins) {
                if (state.pendingBatchLogins.isEmpty()) return
                val list = state.pendingBatchLogins.take(BATCH_MAX)
                state.pendingBatchLogins.removeAll(list.toSet())
                list
            }
            val res = api.fetchChannelBadgesBatch(state.channelLogin, chunk)
            if (!res.ok) {
                cooldown.markFromHttpStatus(
                    SvagaRequestCooldown.channelBadgesKey(state.channelLogin),
                    res.statusCode,
                )
                return
            }
            state.badges.putAll(res.badges)
            if (res.fontPresets.isNotEmpty()) {
                state.fontPresets.putAll(res.fontPresets)
                fontManager.registerPresets(res.fontPresets)
            }
            val exp = System.currentTimeMillis() + CACHE_TTL_MS
            var anyVisual = false
            for (login in chunk) {
                val raw = res.viewers[login]
                if (raw != null) {
                    val profile = materializeViewerProfile(raw, state.badges)
                    if (profile.hasVisualData) {
                        state.viewers[login] = profile
                        anyVisual = true
                    } else {
                        state.queriedEmptyViewers[login] = exp
                    }
                } else {
                    state.queriedEmptyViewers[login] = exp
                }
            }
            if (anyVisual) {
                _visualUpdates.tryEmit(null)
            }
        }
    }

    private fun applySnapshotData(
        state: ChannelState,
        badges: Map<String, SvagaBadge>,
        fontPresets: Map<String, SvagaFontPreset>,
        rawViewers: Map<String, SvagaRawViewerEntry>,
    ) {
        state.badges.clear()
        state.badges.putAll(badges)
        state.fontPresets.clear()
        state.fontPresets.putAll(fontPresets)
        if (fontPresets.isNotEmpty()) {
            fontManager.registerPresets(fontPresets)
        }

        val nextViewers = HashMap<String, SvagaViewerProfile>(rawViewers.size)
        for ((login, raw) in rawViewers) {
            val profile = materializeViewerProfile(raw, badges)
            if (profile.hasVisualData) {
                nextViewers[login] = profile
            }
        }
        state.viewers.clear()
        state.viewers.putAll(nextViewers)
    }

    private fun materializeViewerProfile(
        raw: SvagaRawViewerEntry,
        badgesCatalog: Map<String, SvagaBadge>,
    ): SvagaViewerProfile {
        val resolvedBadges = if (raw.badgeIds.isNotEmpty()) {
            raw.badgeIds.mapNotNull { badgesCatalog[it] }
        } else {
            raw.directBadges
        }.distinctBy { "${it.source}|${it.url}|${it.title}" }

        val parsedStyle = SvagaCssStyleParser.parseViewerStyle(
            login = raw.login,
            nameColor = raw.nameColor,
            nameGradient = raw.nameGradient,
            nameCss = raw.nameCss,
        )

        return SvagaViewerProfile(
            login = raw.login,
            badges = resolvedBadges,
            nameColor = parsedStyle.solidColor,
            nameGradient = raw.nameGradient,
            nameCss = raw.nameCss,
            namePresetName = raw.namePresetName,
            fontPresetId = raw.fontPresetId,
            paint = parsedStyle.paint,
        )
    }

    companion object {
        const val PREF_SVAGA_BADGES_ENABLED = "svaga_badges_enabled"
        const val PREF_SVAGA_STYLES_ENABLED = "svaga_styles_enabled"
        const val PREF_SVAGA_SOCIAL_RATING_ENABLED = "svaga_social_rating_enabled"

        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private const val BATCH_MAX = 100
        private val LOGIN_REGEX = Regex("""^[a-z0-9_]{3,25}$""")
    }
}
