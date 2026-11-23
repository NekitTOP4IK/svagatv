package com.github.andreyasadchy.xtra.svagaplus

import android.content.Context
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads, caches, and creates Android [Typeface] spans for Svaga+ custom nickname `font_presets`.
 * Supports direct `.ttf` / `.otf` CDN paths, Google Fonts CSS (`@font-face` extraction with a
 * TTF User-Agent so Google Fonts serves Android-compatible TrueType binaries), pixel-font anti-aliasing
 * control, and `size_adjust` scaling.
 */
@Singleton
class SvagaFontManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val api: SvagaPlusApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheDir: File by lazy {
        File(context.cacheDir, "svaga_fonts").apply { mkdirs() }
    }
    private val loadedTypefaces = ConcurrentHashMap<String, Typeface>()
    private val presetRegistry = ConcurrentHashMap<String, SvagaFontPreset>()
    private val inFlightIds = ConcurrentHashMap.newKeySet<String>()

    var onFontReadyCallback: (() -> Unit)? = null

    fun registerPresets(presets: Map<String, SvagaFontPreset>) {
        for ((id, preset) in presets) {
            presetRegistry[id] = preset
            if (!loadedTypefaces.containsKey(id)) {
                ensureFontLoadedAsync(preset)
            }
        }
    }

    fun createSpanIfAvailable(fontPresetId: String?): MetricAffectingSpan? {
        if (fontPresetId.isNullOrBlank()) return null
        val preset = presetRegistry[fontPresetId] ?: return null
        val typeface = loadedTypefaces[fontPresetId]
        if (typeface == null) {
            ensureFontLoadedAsync(preset)
            return if (preset.isPixelFont) {
                SvagaFontSpan(Typeface.MONOSPACE, preset.sizeAdjustPercent, isPixelFont = true)
            } else {
                null
            }
        }
        return SvagaFontSpan(typeface, preset.sizeAdjustPercent, preset.isPixelFont)
    }

    private fun ensureFontLoadedAsync(preset: SvagaFontPreset) {
        if (loadedTypefaces.containsKey(preset.id)) return
        if (!inFlightIds.add(preset.id)) return

        scope.launch {
            try {
                val cachedFile = File(cacheDir, "font_${sha256Hex(preset.id)}.ttf")
                if (cachedFile.isFile && cachedFile.length() > 64L) {
                    tryLoadTypeface(cachedFile)?.let { tf ->
                        loadedTypefaces[preset.id] = tf
                        onFontReadyCallback?.invoke()
                        return@launch
                    }
                    cachedFile.delete()
                }

                val fontUrl = resolveDownloadableFontUrl(preset) ?: return@launch
                val req = Request.Builder()
                    .url(fontUrl)
                    .header("User-Agent", LEGACY_TTF_USER_AGENT)
                    .build()
                okHttpClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@use
                    val bytes = res.body.bytes()
                    if (!isSupportedFontBinary(bytes)) return@use
                    val tmpFile = File(cacheDir, "tmp_${sha256Hex(preset.id)}.ttf")
                    tmpFile.writeBytes(bytes)
                    if (!tmpFile.renameTo(cachedFile)) {
                        cachedFile.writeBytes(bytes)
                        tmpFile.delete()
                    }
                    val tf = tryLoadTypeface(cachedFile)
                    if (tf != null) {
                        loadedTypefaces[preset.id] = tf
                        onFontReadyCallback?.invoke()
                    } else {
                        cachedFile.delete()
                    }
                }
            } catch (_: Exception) {
            } finally {
                inFlightIds.remove(preset.id)
            }
        }
    }

    private fun resolveDownloadableFontUrl(preset: SvagaFontPreset): String? {
        if (preset.source == "google" && !preset.googleFontsUrl.isNullOrBlank()) {
            // Request Google Fonts CSS with a legacy User-Agent so Google returns .ttf URLs instead of .woff2
            val cssReq = Request.Builder()
                .url(preset.googleFontsUrl)
                .header("User-Agent", LEGACY_TTF_USER_AGENT)
                .build()
            try {
                okHttpClient.newCall(cssReq).execute().use { res ->
                    if (res.isSuccessful) {
                        val css = res.body.string()
                        FONT_SRC_URL_REGEX.find(css)?.groupValues?.getOrNull(1)?.let { return it }
                    }
                }
            } catch (_: Exception) {
            }
        }
        val cdn = preset.cdnPath?.takeIf { it.isNotBlank() }
        if (cdn != null) {
            if (cdn.endsWith(".woff2", ignoreCase = true)) {
                if (!preset.fontFamily.isNullOrBlank()) {
                    // Try .ttf sibling first, otherwise Google Fonts fallback by family name
                    val ttfCandidate = api.resolveAbsoluteUrl(cdn.dropLast(6) + ".ttf")
                    if (ttfCandidate != null && headExists(ttfCandidate)) {
                        return ttfCandidate
                    }
                    val familyEncoded = preset.fontFamily.trim().replace(" ", "+")
                    val gUrl = "https://fonts.googleapis.com/css2?family=$familyEncoded"
                    val cssReq = Request.Builder().url(gUrl).header("User-Agent", LEGACY_TTF_USER_AGENT).build()
                    try {
                        okHttpClient.newCall(cssReq).execute().use { res ->
                            if (res.isSuccessful) {
                                FONT_SRC_URL_REGEX.find(res.body.string())?.groupValues?.getOrNull(1)?.let { return it }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                // Android Minikin/FreeType does not decode WOFF2 directly
                return null
            }
            return api.resolveAbsoluteUrl(cdn)
        }
        return null
    }

    private fun headExists(url: String): Boolean {
        return try {
            val req = Request.Builder().url(url).head().build()
            okHttpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun tryLoadTypeface(file: File): Typeface? {
        return try {
            val tf = Typeface.createFromFile(file)
            if (tf == Typeface.DEFAULT) null else tf
        } catch (_: Exception) {
            null
        }
    }

    class SvagaFontSpan(
        private val customTypeface: Typeface,
        private val sizeAdjustPercent: Float?,
        private val isPixelFont: Boolean,
    ) : MetricAffectingSpan() {

        override fun updateDrawState(tp: TextPaint) {
            applyFont(tp)
        }

        override fun updateMeasureState(tp: TextPaint) {
            applyFont(tp)
        }

        private fun applyFont(tp: TextPaint) {
            val oldStyle = tp.typeface?.style ?: Typeface.NORMAL
            tp.typeface = Typeface.create(customTypeface, oldStyle)
            if (sizeAdjustPercent != null && sizeAdjustPercent in 50f..200f && sizeAdjustPercent != 100f) {
                tp.textSize = tp.textSize * (sizeAdjustPercent / 100f)
            }
            if (isPixelFont) {
                tp.isSubpixelText = false
                tp.isLinearText = false
            }
        }
    }

    companion object {
        private const val LEGACY_TTF_USER_AGENT = "Mozilla/5.0 (Linux; U; Android 4.1.1; en-us) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30"
        private val FONT_SRC_URL_REGEX = Regex("""url\((https?://[^)'"\s]+\.(?:ttf|otf))\)""", RegexOption.IGNORE_CASE)

        internal fun isSupportedFontBinary(bytes: ByteArray): Boolean {
            if (bytes.size < 64) return false
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            val b3 = bytes[3].toInt() and 0xFF
            // TrueType (00 01 00 00), OpenType CFF ('OTTO'), Apple TrueType ('true'), TrueType Collection ('ttcf')
            if (b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) return true
            val tag = String(bytes, 0, 4, Charsets.US_ASCII)
            return tag == "OTTO" || tag == "true" || tag == "ttcf"
        }

        private fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(24)
        }
    }
}
