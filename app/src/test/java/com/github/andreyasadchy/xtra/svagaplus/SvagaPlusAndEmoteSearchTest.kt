package com.github.andreyasadchy.xtra.svagaplus

import android.app.Application
import android.graphics.Color
import com.github.andreyasadchy.xtra.model.chat.Chatter
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.ui.chat.EmotePickerSearch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [28])
class SvagaPlusAndEmoteSearchTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses linear gradient css with drop shadow into NamePaint`() {
        val css = "background: linear-gradient(135deg, #ff3366 0%, #5266eb 100%); -webkit-background-clip: text; filter: drop-shadow(1px 2px 4px rgba(0, 0, 0, 0.8));"
        val style = SvagaCssStyleParser.parseViewerStyle(
            login = "olesha",
            nameColor = null,
            nameGradient = null,
            nameCss = css,
        )
        val paint = style.paint
        assertNotNull(paint)
        assertEquals("LINEAR_GRADIENT", paint!!.type)
        assertEquals(135, paint.angle)
        assertArrayEquals(intArrayOf(Color.parseColor("#ff3366"), Color.parseColor("#5266eb")), paint.colors)
        assertArrayEquals(floatArrayOf(0f, 1f), paint.colorPositions!!, 0.001f)
        val shadows = paint.shadows
        assertNotNull(shadows)
        assertEquals(1, shadows!!.size)
        assertEquals(1f, shadows[0].xOffset, 0.01f)
        assertEquals(2f, shadows[0].yOffset, 0.01f)
        assertEquals(4f, shadows[0].radius, 0.01f)
    }

    @Test
    fun `parses directional gradient and solid fallback colors`() {
        val gradient = SvagaCssStyleParser.parseViewerStyle(
            login = "viewer1",
            nameColor = null,
            nameGradient = "linear-gradient(to right, #00ffcc, #3366ff, #ff00aa)",
            nameCss = null,
        )
        val gPaint = gradient.paint
        assertNotNull(gPaint)
        assertEquals(90, gPaint!!.angle)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f), gPaint.colorPositions!!, 0.001f)

        val solid = SvagaCssStyleParser.parseViewerStyle(
            login = "viewer2",
            nameColor = "#5266eb",
            nameGradient = null,
            nameCss = null,
        )
        assertEquals(Color.parseColor("#5266eb"), solid.solidColor)
        assertNull(solid.paint)
    }

    @Test
    fun `request cooldown enforces 10m on 404 and 30s on transient errors`() {
        var now = 1_000_000L
        val cooldown = SvagaRequestCooldown { now }
        val key404 = SvagaRequestCooldown.channelBadgesKey("non_svaga_channel")
        val key503 = SvagaRequestCooldown.channelBadgesKey("busy_channel")

        cooldown.markFromHttpStatus(key404, 404)
        cooldown.markFromHttpStatus(key503, 503)

        assertTrue(cooldown.isBlocked(key404))
        assertTrue(cooldown.isBlocked(key503))

        now += 31_000L
        assertFalse(cooldown.isBlocked(key503))
        assertTrue(cooldown.isBlocked(key404))

        now += 10 * 60 * 1000L
        assertFalse(cooldown.isBlocked(key404))
    }

    @Test
    fun `parses v3 channel snapshot payload and resolves relative urls`() {
        val api = SvagaPlusApi(RuntimeEnvironment.getApplication(), OkHttpClient(), json)
        val payload = json.parseToJsonElement(
            """
            {
              "success": true,
              "data": {
                "rev": 12,
                "global_rev": 5,
                "badges": {
                  "b1": {
                    "id": "b1",
                    "title": "Легенда Сваги",
                    "image_url": "/uploads/badges/legend.webp",
                    "source": "collectible",
                    "rarity": "mythic",
                    "is_animated": true,
                    "page": "/badges/collectible/legend-1"
                  }
                },
                "font_presets": {
                  "f1": {
                    "source": "cdn",
                    "cdn_path": "/fonts/pixel.ttf",
                    "font_family": "SvagaPixel",
                    "size_adjust": 105,
                    "is_pixel_font": true
                  }
                },
                "viewers": {
                  "ViewerOne": {
                    "badge_ids": ["b1"],
                    "name_color": "#ff9900",
                    "font_preset_id": "f1"
                  }
                }
              }
            }
            """.trimIndent()
        ) as JsonObject

        val result = api.parseSnapshotPayload(payload, "\"12.5\"")
        assertTrue(result is SvagaSnapshotFetchResult.Ok)
        val ok = result as SvagaSnapshotFetchResult.Ok
        assertEquals(12, ok.rev)
        assertEquals(5, ok.globalRev)
        assertEquals("\"12.5\"", ok.etag)
        val badge = ok.badges["b1"]!!
        assertEquals("https://svagaplus.com/uploads/badges/legend.webp", badge.url)
        assertEquals("mythic", badge.rarity)
        assertTrue(badge.isAnimated)
        assertEquals("/badges/collectible/legend-1", badge.page)
        val viewer = ok.viewers["viewerone"]!!
        assertEquals(listOf("b1"), viewer.badgeIds)
        assertEquals("f1", viewer.fontPresetId)
        assertTrue(ok.fontPresets["f1"]!!.isPixelFont)
    }

    @Test
    fun `parses vote cooldown timestamps and pluralizes badge owners in Russian`() {
        assertEquals(1_750_000_000_000L, SvagaPlusApi.parseNextVoteAtMs(JsonPrimitive(1_750_000_000L)))
        assertEquals(1_749_996_800_000L, SvagaPlusApi.parseNextVoteAtMs(JsonPrimitive("2025-06-15T14:13:20Z")))

        assertEquals("владелец", SvagaBadgeCard.pluralRu(1, "владелец", "владельца", "владельцев"))
        assertEquals("владельца", SvagaBadgeCard.pluralRu(23, "владелец", "владельца", "владельцев"))
        assertEquals("владельцев", SvagaBadgeCard.pluralRu(14, "владелец", "владельца", "владельцев"))
    }

    @Test
    fun `builds socket io websocket url and bounded jittered reconnect delay`() {
        assertEquals(
            "wss://svagaplus.com/socket.io/?EIO=4&transport=websocket",
            SvagaSocketClient.buildSocketIoWsUrl("https://svagaplus.com/"),
        )
        val delay1 = SvagaSocketClient.reconnectDelayMs(1, 0.5)
        val delay10 = SvagaSocketClient.reconnectDelayMs(10, 0.5)
        assertEquals(1000L, delay1)
        assertEquals(15000L, delay10)
    }

    @Test
    fun `emote picker search ranks favorites and channel 7tv above globals and matches aliases`() {
        val emotes = listOf(
            Emote(name = "PogChamp", source = Emote.GLOBAL_FFZ),
            Emote(name = "Pog", source = Emote.GLOBAL_STV),
            Emote(name = "PogSlide", source = Emote.CHANNEL_STV),
            Emote(name = "PogU", source = Emote.GLOBAL_BTTV, isOverlayEmote = true),
        )
        val ranked = EmotePickerSearch.filterEmotes(emotes, "pog", favorites = setOf("PogU"))
        assertEquals("Pog", ranked[0].name) // Exact match always wins
        assertEquals("PogU", ranked[1].name) // Prefix + Favorite beats non-favorite prefix
        assertEquals("PogSlide", ranked[2].name) // Channel 7TV prefix beats Global FFZ prefix
        assertEquals("PogChamp", ranked[3].name)
        assertEquals("BTTV·G · ZW", EmotePickerSearch.providerLabel(emotes[3]))

        val chatters: List<Any?> = listOf(
            Chatter("olesha", "olesha"),
            Chatter("random_viewer", "random_viewer"),
        )
        val matchedByAlias = EmotePickerSearch.filterAutoCompleteItems(
            items = chatters,
            rawConstraint = "@шеф",
            aliasLookup = { login -> if (login == "olesha") "Шеф Сваги" else null },
        )
        assertEquals(1, matchedByAlias.size)
        assertEquals("olesha", (matchedByAlias[0] as Chatter).name)
    }
}
