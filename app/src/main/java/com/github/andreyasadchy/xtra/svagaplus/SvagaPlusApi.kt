package com.github.andreyasadchy.xtra.svagaplus

import android.content.Context
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SvagaPlusApi @Inject constructor(
    @param:ApplicationContext private val context: Context,
    baseOkHttpClient: OkHttpClient,
    private val json: Json,
) {
    private val httpClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    fun getBackendUrl(): String {
        val raw = context.prefs().getString(PREF_SVAGA_BACKEND_URL, DEFAULT_BACKEND_URL)
            ?.trim()
            ?.trimEnd('/')
        return if (!raw.isNullOrEmpty() && (raw.startsWith("https://") || raw.startsWith("http://"))) {
            raw
        } else {
            DEFAULT_BACKEND_URL
        }
    }

    fun resolveAbsoluteUrl(urlOrPath: String?): String? {
        if (urlOrPath.isNullOrBlank()) return null
        val trimmed = urlOrPath.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        val base = getBackendUrl()
        return if (trimmed.startsWith("/")) "$base$trimmed" else "$base/$trimmed"
    }

    suspend fun fetchChannelSnapshot(channelLogin: String, etag: String?): SvagaSnapshotFetchResult = withContext(Dispatchers.IO) {
        val channel = encodeSegment(channelLogin.trim().lowercase())
        val url = "${getBackendUrl()}/api/v3/channels/$channel/snapshot"
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        if (!etag.isNullOrBlank()) {
            reqBuilder.header("If-None-Match", etag)
        }
        try {
            httpClient.newCall(reqBuilder.build()).execute().use { res ->
                when {
                    res.code == 304 -> SvagaSnapshotFetchResult.NotModified
                    res.code == 404 -> {
                        val bodyObj = res.body.string().let(::parseJsonObjectOrNull)
                        val err = bodyObj?.get("error")?.jsonPrimitive?.contentOrNull
                        if (err == "channel_not_found") {
                            SvagaSnapshotFetchResult.ChannelUnknown
                        } else {
                            SvagaSnapshotFetchResult.MissingRoute
                        }
                    }
                    !res.isSuccessful -> SvagaSnapshotFetchResult.Failed
                    else -> {
                        val bodyObj = res.body.string().let(::parseJsonObjectOrNull)
                            ?: return@use SvagaSnapshotFetchResult.Failed
                        parseSnapshotPayload(bodyObj, res.header("ETag"))
                    }
                }
            }
        } catch (_: Exception) {
            SvagaSnapshotFetchResult.Failed
        }
    }

    fun parseSnapshotPayload(bodyObj: JsonObject, headerEtag: String?): SvagaSnapshotFetchResult {
        val data = unwrapDataObject(bodyObj) ?: return SvagaSnapshotFetchResult.Failed
        val rev = data["rev"]?.jsonPrimitive?.intOrNull ?: return SvagaSnapshotFetchResult.Failed
        val globalRev = data["global_rev"]?.jsonPrimitive?.intOrNull ?: return SvagaSnapshotFetchResult.Failed
        val rawViewers = data["viewers"] as? JsonObject ?: return SvagaSnapshotFetchResult.Failed
        if (rev < 0 || globalRev < 0) return SvagaSnapshotFetchResult.Failed

        val base = getBackendUrl()
        val badges = parseBadgesMap(data["badges"] as? JsonObject, base)
        val fontPresets = parseFontPresetsMap(data["font_presets"] as? JsonObject)
        val viewers = parseViewersMap(rawViewers, base)

        return SvagaSnapshotFetchResult.Ok(
            rev = rev,
            globalRev = globalRev,
            etag = headerEtag?.takeIf { it.isNotBlank() } ?: "\"$rev.$globalRev\"",
            badges = badges,
            fontPresets = fontPresets,
            viewers = viewers,
        )
    }

    suspend fun fetchChannelBadgesBatch(channelLogin: String, logins: List<String>): SvagaBatchBadgesResult = withContext(Dispatchers.IO) {
        val normalized = logins.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (normalized.isEmpty()) return@withContext SvagaBatchBadgesResult(ok = true)

        val channel = encodeSegment(channelLogin.trim().lowercase())
        val baseHttpUrl = "${getBackendUrl()}/api/v3/channels/$channel/badges".toHttpUrlOrNull()
            ?: return@withContext SvagaBatchBadgesResult(ok = false)
        val url = baseHttpUrl.newBuilder()
            .addQueryParameter("viewers", normalized.joinToString(","))
            .build()

        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        try {
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    return@use SvagaBatchBadgesResult(
                        ok = false,
                        notFound = res.code == 404,
                        statusCode = res.code,
                    )
                }
                val bodyObj = res.body.string().let(::parseJsonObjectOrNull)
                    ?: return@use SvagaBatchBadgesResult(ok = false, statusCode = res.code)
                val data = unwrapDataObject(bodyObj) ?: bodyObj
                val base = getBackendUrl()
                SvagaBatchBadgesResult(
                    ok = true,
                    statusCode = res.code,
                    badges = parseBadgesMap(data["badges"] as? JsonObject, base),
                    fontPresets = parseFontPresetsMap(data["font_presets"] as? JsonObject),
                    viewers = parseViewersMap(data["viewers"] as? JsonObject, base),
                )
            }
        } catch (_: Exception) {
            SvagaBatchBadgesResult(ok = false, statusCode = 0)
        }
    }

    suspend fun fetchBadgeCard(page: String): SvagaBadgeCard? = withContext(Dispatchers.IO) {
        val cleanPage = page.trim()
        if (!BADGE_PAGE_REGEX.matches(cleanPage)) return@withContext null
        val base = getBackendUrl()
        val req = Request.Builder()
            .url("$base/api/v3$cleanPage")
            .header("Accept", "application/json")
            .build()

        try {
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val bodyObj = res.body.string().let(::parseJsonObjectOrNull) ?: return@use null
                if (bodyObj["success"]?.jsonPrimitive?.booleanOrNull == false) return@use null
                val data = unwrapDataObject(bodyObj) ?: return@use null
                val name = data["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@use null
                val imgUrl = resolveAbsoluteUrl(data["image_url"]?.jsonPrimitive?.contentOrNull) ?: return@use null
                val contextObj = data["context"] as? JsonObject
                SvagaBadgeCard(
                    kind = data["kind"]?.jsonPrimitive?.contentOrNull ?: "collectible",
                    name = name,
                    imageUrl = imgUrl,
                    rarity = data["rarity"]?.jsonPrimitive?.contentOrNull,
                    description = data["description"]?.jsonPrimitive?.contentOrNull,
                    howToGet = data["how_to_get"]?.jsonPrimitive?.contentOrNull,
                    owners = data["owners"]?.jsonPrimitive?.intOrNull ?: 0,
                    channel = contextObj?.get("channel")?.jsonPrimitive?.contentOrNull,
                    period = contextObj?.get("period")?.jsonPrimitive?.contentOrNull,
                    pageUrl = "$base$cleanPage",
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchSocialChannelStatus(channelLogin: String, token: String? = null): SvagaChannelSocialStatus? = withContext(Dispatchers.IO) {
        val channel = encodeSegment(channelLogin.trim().lowercase())
        val req = Request.Builder()
            .url("${getBackendUrl()}/api/v3/social/channels/$channel/status")
            .header("Accept", "application/json")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .build()
        try {
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val bodyObj = res.body.string().let(::parseJsonObjectOrNull) ?: return@use null
                val data = unwrapDataObject(bodyObj) ?: bodyObj
                val chObj = data["channel"] as? JsonObject
                SvagaChannelSocialStatus(
                    channelLogin = chObj?.get("login")?.jsonPrimitive?.contentOrNull ?: channelLogin.trim().lowercase(),
                    ratingEnabled = chObj?.get("rating_enabled")?.jsonPrimitive?.booleanOrNull == true,
                    activityPublic = chObj?.get("activity_public")?.jsonPrimitive?.booleanOrNull == true,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchSocialRating(
        channelLogin: String,
        viewerLogin: String,
        token: String? = null,
    ): Pair<SvagaSocialRating?, Int> = withContext(Dispatchers.IO) {
        val channel = encodeSegment(channelLogin.trim().lowercase())
        val viewer = encodeSegment(viewerLogin.trim().lowercase())
        val req = Request.Builder()
            .url("${getBackendUrl()}/api/v3/social/channels/$channel/viewers/$viewer/rating")
            .header("Accept", "application/json")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .build()
        try {
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null to res.code
                val bodyObj = res.body.string().let(::parseJsonObjectOrNull) ?: return@use null to res.code
                val data = unwrapDataObject(bodyObj) ?: bodyObj
                val enabled = data["enabled"]?.jsonPrimitive?.booleanOrNull != false
                if (!enabled) return@use null to res.code
                val swag = data["swag_score"]?.jsonPrimitive?.intOrNull
                    ?: data["score"]?.jsonPrimitive?.intOrNull
                    ?: return@use null to res.code
                val social = data["social_score"]?.jsonPrimitive?.intOrNull ?: 0
                val vLogin = (data["viewer"] as? JsonObject)?.get("login")?.jsonPrimitive?.contentOrNull
                    ?: viewerLogin.trim().lowercase()
                val cLogin = (data["channel"] as? JsonObject)?.get("login")?.jsonPrimitive?.contentOrNull
                    ?: channelLogin.trim().lowercase()
                SvagaSocialRating(
                    channelLogin = cLogin,
                    viewerLogin = vLogin,
                    swagScore = swag,
                    socialScore = social,
                    enabled = true,
                ) to res.code
            }
        } catch (_: Exception) {
            null to 0
        }
    }

    suspend fun postSocialVote(
        channelLogin: String,
        targetLogin: String,
        value: Int,
        token: String,
    ): SvagaVoteResult = withContext(Dispatchers.IO) {
        val voteVal = if (value >= 0) 1 else -1
        val channel = encodeSegment(channelLogin.trim().lowercase())
        val bodyJson = buildJsonObject {
            put("target_login", targetLogin.trim().lowercase())
            put("value", voteVal)
        }.toString()
        val req = Request.Builder()
            .url("${getBackendUrl()}/api/v3/social/channels/$channel/votes")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            httpClient.newCall(req).execute().use { res ->
                val text = res.body.string()
                val bodyObj = parseJsonObjectOrNull(text)
                if (res.code == 401) {
                    return@use SvagaVoteResult(ok = false, error = "not_authenticated")
                }
                if (!res.isSuccessful) {
                    val (errMsg, nextVoteMs) = extractVoteError(bodyObj, res.code)
                    return@use SvagaVoteResult(ok = false, error = errMsg, nextVoteAtMs = nextVoteMs)
                }
                val data = bodyObj?.let(::unwrapDataObject) ?: bodyObj
                val swag = data?.get("swag_score")?.jsonPrimitive?.intOrNull
                    ?: data?.get("score")?.jsonPrimitive?.intOrNull
                val social = data?.get("social_score")?.jsonPrimitive?.intOrNull
                val nextAt = parseNextVoteAtMs(data?.get("next_vote_at"))
                if (swag != null) {
                    SvagaVoteResult(ok = true, swagScore = swag, socialScore = social, nextVoteAtMs = nextAt)
                } else {
                    val refreshed = fetchSocialRating(channelLogin, targetLogin, token).first
                    SvagaVoteResult(
                        ok = true,
                        swagScore = refreshed?.swagScore,
                        socialScore = refreshed?.socialScore,
                        nextVoteAtMs = nextAt,
                    )
                }
            }
        } catch (_: Exception) {
            SvagaVoteResult(ok = false, error = "network_error")
        }
    }

    suspend fun getViewerMe(token: String): Pair<SvagaViewerAccount?, Int> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${getBackendUrl()}/api/viewer/me")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .build()
        try {
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null to res.code
                val bodyObj = res.body.string().let(::parseJsonObjectOrNull) ?: return@use null to res.code
                val data = unwrapDataObject(bodyObj) ?: bodyObj
                val login = data["twitch_username"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return@use null to res.code
                val telegramLinked = data["telegram_linked"]?.jsonPrimitive?.booleanOrNull
                    ?: data["is_linked"]?.jsonPrimitive?.booleanOrNull
                    ?: false
                SvagaViewerAccount(
                    token = token,
                    twitchLogin = login,
                    avatarUrl = resolveAbsoluteUrl(data["avatar_url"]?.jsonPrimitive?.contentOrNull),
                    telegramLinked = telegramLinked,
                    colorBanned = data["color_banned"]?.jsonPrimitive?.booleanOrNull == true,
                    colorBanReason = data["color_ban_reason"]?.jsonPrimitive?.contentOrNull,
                    lastCheckedAt = System.currentTimeMillis(),
                ) to res.code
            }
        } catch (_: Exception) {
            null to 0
        }
    }

    suspend fun getTwitchAuthorizeUrl(redirectUri: String, state: String): String? = withContext(Dispatchers.IO) {
        val baseUrl = "${getBackendUrl()}/api/auth/twitch/authorize-url".toHttpUrlOrNull() ?: return@withContext null
        val url = baseUrl.newBuilder()
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("state", state)
            .build()
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        try {
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val bodyObj = res.body.string().let(::parseJsonObjectOrNull) ?: return@use null
                if (bodyObj["success"]?.jsonPrimitive?.booleanOrNull != true) return@use null
                bodyObj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun linkViewerTwitch(twitchCode: String, redirectUri: String): String? = withContext(Dispatchers.IO) {
        val bodyJson = buildJsonObject {
            put("twitch_code", twitchCode)
            put("redirect_uri", redirectUri)
        }.toString()
        val req = Request.Builder()
            .url("${getBackendUrl()}/api/auth/twitch/viewer")
            .header("Accept", "application/json")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val bodyObj = res.body.string().let(::parseJsonObjectOrNull) ?: return@use null
                if (bodyObj["success"]?.jsonPrimitive?.booleanOrNull != true) return@use null
                bodyObj["token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchServerAliases(token: String): Map<String, String>? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${getBackendUrl()}/api/v3/social/aliases")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .build()
        try {
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val root = json.parseToJsonElement(res.body.string())
                val unwrapped = if (root is JsonObject && root["data"] != null) root["data"]!! else root
                val map = LinkedHashMap<String, String>()
                when (unwrapped) {
                    is JsonArray -> {
                        for (el in unwrapped) {
                            val obj = el as? JsonObject ?: continue
                            val login = obj["login"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: continue
                            val alias = obj["alias"]?.jsonPrimitive?.contentOrNull?.trim() ?: continue
                            if (login.isNotEmpty() && alias.isNotEmpty()) map[login] = alias
                        }
                    }
                    is JsonObject -> {
                        val arr = unwrapped["aliases"]
                        if (arr is JsonArray) {
                            for (el in arr) {
                                val obj = el as? JsonObject ?: continue
                                val login = obj["login"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: continue
                                val alias = obj["alias"]?.jsonPrimitive?.contentOrNull?.trim() ?: continue
                                if (login.isNotEmpty() && alias.isNotEmpty()) map[login] = alias
                            }
                        } else if (arr is JsonObject) {
                            for ((k, v) in arr) {
                                val login = k.trim().lowercase()
                                val alias = v.jsonPrimitive.contentOrNull?.trim() ?: continue
                                if (login.isNotEmpty() && alias.isNotEmpty()) map[login] = alias
                            }
                        } else {
                            for ((k, v) in unwrapped) {
                                if (v is JsonPrimitive) {
                                    val login = k.trim().lowercase()
                                    val alias = v.contentOrNull?.trim() ?: continue
                                    if (login.isNotEmpty() && alias.isNotEmpty()) map[login] = alias
                                }
                            }
                        }
                    }
                    else -> Unit
                }
                map
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun upsertServerAlias(login: String, alias: String, token: String): Boolean = withContext(Dispatchers.IO) {
        val bodyJson = buildJsonObject {
            put("login", login.trim().lowercase())
            put("alias", alias.trim())
        }.toString()
        val req = Request.Builder()
            .url("${getBackendUrl()}/api/v3/social/aliases")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            httpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun deleteServerAlias(aliasOrLogin: String, token: String): Boolean = withContext(Dispatchers.IO) {
        val encoded = encodeSegment(aliasOrLogin.trim())
        val req = Request.Builder()
            .url("${getBackendUrl()}/api/v3/social/aliases/$encoded")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        try {
            httpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    fun parseBadgesMap(obj: JsonObject?, baseUrl: String): Map<String, SvagaBadge> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, SvagaBadge>(obj.size)
        var idx = 0
        for ((key, el) in obj) {
            idx++
            val bObj = el as? JsonObject ?: continue
            parseSingleBadge(key, bObj, baseUrl, idx)?.let { out[key] = it }
        }
        return out
    }

    fun parseSingleBadge(idFallback: String, bObj: JsonObject, baseUrl: String, fallbackRank: Int = 1): SvagaBadge? {
        if (bObj["active"]?.jsonPrimitive?.booleanOrNull == false) return null
        val rawUrl = bObj["image_url"]?.jsonPrimitive?.contentOrNull
            ?: bObj["url"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val absUrl = if (rawUrl.startsWith("http://", true) || rawUrl.startsWith("https://", true)) {
            rawUrl
        } else if (rawUrl.startsWith("/")) {
            "$baseUrl$rawUrl"
        } else {
            "$baseUrl/$rawUrl"
        }
        val rawSource = bObj["source"]?.jsonPrimitive?.contentOrNull
        val source = when (rawSource) {
            "social_rating", "tsr" -> "tsr"
            "collectible" -> "collectible"
            else -> "tra"
        }
        val page = bObj["page"]?.jsonPrimitive?.contentOrNull?.takeIf { BADGE_PAGE_REGEX.matches(it) }
        val id = bObj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: idFallback
        return SvagaBadge(
            id = id,
            source = source,
            title = bObj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Badge",
            url = absUrl,
            rarity = bObj["rarity"]?.jsonPrimitive?.contentOrNull,
            isAnimated = bObj["is_animated"]?.jsonPrimitive?.booleanOrNull == true,
            rank = bObj["rank"]?.jsonPrimitive?.intOrNull ?: fallbackRank,
            page = page,
        )
    }

    fun parseFontPresetsMap(obj: JsonObject?): Map<String, SvagaFontPreset> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, SvagaFontPreset>(obj.size)
        for ((key, el) in obj) {
            val fObj = el as? JsonObject ?: continue
            out[key] = SvagaFontPreset(
                id = key,
                source = fObj["source"]?.jsonPrimitive?.contentOrNull,
                googleFontsUrl = fObj["google_fonts_url"]?.jsonPrimitive?.contentOrNull,
                cdnPath = fObj["cdn_path"]?.jsonPrimitive?.contentOrNull,
                fontFamily = fObj["font_family"]?.jsonPrimitive?.contentOrNull,
                sizeAdjustPercent = fObj["size_adjust"]?.jsonPrimitive?.floatOrNull,
                isPixelFont = fObj["is_pixel_font"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }
        return out
    }

    fun parseViewersMap(obj: JsonObject?, baseUrl: String): Map<String, SvagaRawViewerEntry> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, SvagaRawViewerEntry>(obj.size)
        for ((key, el) in obj) {
            val vObj = el as? JsonObject ?: continue
            val login = key.trim().lowercase()
            if (login.isEmpty()) continue
            out[login] = parseSingleViewerEntry(login, vObj, baseUrl)
        }
        return out
    }

    fun parseSingleViewerEntry(login: String, vObj: JsonObject, baseUrl: String): SvagaRawViewerEntry {
        val badgeIds = (vObj["badge_ids"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotEmpty) }
            ?: emptyList()

        val directBadges = mutableListOf<SvagaBadge>()
        var rankIdx = 1
        for (field in listOf("tra_badges", "collectible_badges", "tsr_badges")) {
            val arr = vObj[field] as? JsonArray ?: continue
            for (item in arr) {
                val bObj = item as? JsonObject ?: continue
                parseSingleBadge("${field}_$rankIdx", bObj, baseUrl, rankIdx)?.let {
                    directBadges.add(it)
                    rankIdx++
                }
            }
        }

        return SvagaRawViewerEntry(
            login = login,
            badgeIds = badgeIds,
            directBadges = directBadges,
            nameColor = vObj["name_color"]?.jsonPrimitive?.contentOrNull,
            nameGradient = vObj["name_gradient"]?.jsonPrimitive?.contentOrNull,
            nameCss = vObj["name_css"]?.jsonPrimitive?.contentOrNull,
            namePresetName = vObj["name_preset_name"]?.jsonPrimitive?.contentOrNull,
            fontPresetId = vObj["font_preset_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun parseJsonObjectOrNull(raw: String): JsonObject? {
        if (raw.isBlank()) return null
        return try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun unwrapDataObject(obj: JsonObject): JsonObject? {
        return (obj["data"] as? JsonObject) ?: obj
    }

    companion object {
        const val DEFAULT_BACKEND_URL = "https://svagaplus.com"
        const val PREF_SVAGA_BACKEND_URL = "svaga_backend_url"
        val BADGE_PAGE_REGEX = Regex("""^/badges/(collectible|service|sub|social)/[A-Za-z0-9-]{1,64}$""")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun encodeSegment(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

        fun extractVoteError(bodyObj: JsonObject?, statusCode: Int): Pair<String, Long?> {
            if (bodyObj == null) return statusCode.toString() to null
            val detail = bodyObj["detail"]
            var message: String? = null
            var rawNext: JsonElement? = null

            when (detail) {
                is JsonObject -> {
                    message = detail["message"]?.jsonPrimitive?.contentOrNull
                        ?: detail["msg"]?.jsonPrimitive?.contentOrNull
                    rawNext = detail["next_vote_at"]
                }
                is JsonPrimitive -> {
                    message = detail.contentOrNull
                }
                else -> Unit
            }
            if (message.isNullOrBlank()) {
                message = bodyObj["message"]?.jsonPrimitive?.contentOrNull
            }
            if (rawNext == null) {
                rawNext = bodyObj["next_vote_at"]
            }
            return (message ?: statusCode.toString()) to parseNextVoteAtMs(rawNext)
        }

        fun parseNextVoteAtMs(element: JsonElement?): Long? {
            val prim = element as? JsonPrimitive ?: return null
            prim.longOrNull?.let { n ->
                return if (n < 1_000_000_000_000L) n * 1000L else n
            }
            val raw = prim.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            raw.toDoubleOrNull()?.let { d ->
                if (!d.isFinite()) return null
                return if (d < 1e12) kotlin.math.round(d * 1000.0).toLong() else kotlin.math.round(d).toLong()
            }
            return try {
                val normalized = if (Regex("""(?:Z|[+-]\d{2}:?\d{2})$""", RegexOption.IGNORE_CASE).containsMatchIn(raw)) {
                    raw
                } else {
                    "${raw}Z"
                }
                Instant.parse(normalized).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }
}
