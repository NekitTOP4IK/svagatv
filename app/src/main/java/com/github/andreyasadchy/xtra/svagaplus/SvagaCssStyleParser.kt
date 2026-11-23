package com.github.andreyasadchy.xtra.svagaplus

import android.graphics.Color
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import kotlin.math.roundToInt

/**
 * Parses Svaga+ viewer nickname CSS declarations (`name_css`, `name_gradient`, `name_color`)
 * into native Android colors and [NamePaint] shaders/shadows compatible with `NamePaintSpan`.
 */
object SvagaCssStyleParser {

    private val hexColorRegex = Regex("""#([0-9a-fA-F]{3,8})\b""")
    private val rgbFuncRegex = Regex("""rgba?\(\s*([0-9.]+)\s*,\s*([0-9.]+)\s*,\s*([0-9.]+)(?:\s*[,/]\s*([0-9.%]+))?\s*\)""", RegexOption.IGNORE_CASE)
    private val dropShadowRegex = Regex("""drop-shadow\(\s*([^)]+)\)""", RegexOption.IGNORE_CASE)
    private val angleDegRegex = Regex("""(-?[0-9.]+)\s*deg""", RegexOption.IGNORE_CASE)
    private val percentRegex = Regex("""([0-9.]+)%""")
    private val pxNumberRegex = Regex("""(-?[0-9.]+)(?:px)?""", RegexOption.IGNORE_CASE)

    data class ParsedNameStyle(
        val solidColor: Int? = null,
        val paint: NamePaint? = null,
    )

    fun parseViewerStyle(
        login: String,
        nameColor: String?,
        nameGradient: String?,
        nameCss: String?,
    ): ParsedNameStyle {
        val declarations = parseDeclarations(nameCss)
        val filterValue = declarations["filter"]
        val shadows = parseDropShadows(filterValue)

        val bgValue = declarations["background"]
            ?: declarations["background-image"]
            ?: nameGradient?.trim()?.takeIf { it.isNotEmpty() }

        if (bgValue != null) {
            val gradientPaint = parseGradient(id = "svaga:$login", raw = bgValue, shadows = shadows)
            if (gradientPaint != null) {
                val firstColor = gradientPaint.colors?.firstOrNull()
                return ParsedNameStyle(solidColor = firstColor, paint = gradientPaint)
            }
            parseCssColor(bgValue)?.let { color ->
                return buildSolidStyle(login, color, shadows)
            }
        }

        val rawColor = declarations["color"]
            ?.takeUnless { it.equals("transparent", ignoreCase = true) }
            ?: nameColor?.trim()?.takeIf { it.isNotEmpty() }

        val parsedColor = rawColor?.let(::parseCssColor)
        if (parsedColor != null) {
            return buildSolidStyle(login, parsedColor, shadows)
        }

        return ParsedNameStyle()
    }

    private fun buildSolidStyle(login: String, color: Int, shadows: List<NamePaint.Shadow>): ParsedNameStyle {
        if (shadows.isEmpty()) {
            return ParsedNameStyle(solidColor = color, paint = null)
        }
        val paint = NamePaint(
            id = "svaga:$login",
            type = "LINEAR_GRADIENT",
            colors = intArrayOf(color, color),
            colorPositions = floatArrayOf(0f, 1f),
            angle = 90,
            repeat = false,
            shadows = shadows,
        )
        return ParsedNameStyle(solidColor = color, paint = paint)
    }

    fun parseDeclarations(css: String?): Map<String, String> {
        if (css.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (part in css.split(';')) {
            val idx = part.indexOf(':')
            if (idx <= 0) continue
            val prop = part.substring(0, idx).trim().lowercase()
            val value = part.substring(idx + 1).trim().removeSuffix("!important").trim()
            if (prop.isNotEmpty() && value.isNotEmpty()) {
                result[prop] = value
            }
        }
        return result
    }

    fun parseGradient(
        id: String,
        raw: String,
        shadows: List<NamePaint.Shadow> = emptyList(),
    ): NamePaint? {
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()
        val isRepeating = lower.startsWith("repeating-")
        val isLinear = lower.contains("linear-gradient(")
        val isRadial = !isLinear && lower.contains("radial-gradient(")
        if (!isLinear && !isRadial) return null

        val openParen = trimmed.indexOf('(')
        val closeParen = findMatchingCloseParen(trimmed, openParen)
        if (openParen < 0 || closeParen <= openParen) return null

        val inner = trimmed.substring(openParen + 1, closeParen)
        val args = splitTopLevelArgs(inner)
        if (args.isEmpty()) return null

        var angle = 180
        var stopStartIndex = 0
        if (isLinear) {
            val parsedAngle = parseLinearDirectionAngle(args[0])
            if (parsedAngle != null) {
                angle = parsedAngle
                stopStartIndex = 1
            }
        } else {
            val firstArgLower = args[0].trim().lowercase()
            if (parseCssColor(args[0]) == null && (firstArgLower.contains("circle") || firstArgLower.contains("ellipse") || firstArgLower.contains("at "))) {
                stopStartIndex = 1
            }
        }

        val rawStops = args.subList(stopStartIndex, args.size)
        if (rawStops.isEmpty()) return null

        val parsedStops = mutableListOf<Pair<Int, Float?>>()
        for (stop in rawStops) {
            val color = findFirstColorInStop(stop) ?: continue
            val afterColor = removeFirstColorFromStop(stop)
            val pctMatches = percentRegex.findAll(afterColor).toList()
            if (pctMatches.isEmpty()) {
                parsedStops.add(color to null)
            } else {
                for (match in pctMatches) {
                    val pct = (match.groupValues[1].toFloatOrNull() ?: continue) / 100f
                    parsedStops.add(color to pct.coerceIn(0f, 1f))
                }
            }
        }

        if (parsedStops.isEmpty()) return null
        if (parsedStops.size == 1) {
            val single = parsedStops[0].first
            return NamePaint(
                id = id,
                type = "LINEAR_GRADIENT",
                colors = intArrayOf(single, single),
                colorPositions = floatArrayOf(0f, 1f),
                angle = angle,
                repeat = false,
                shadows = shadows.ifEmpty { null },
            )
        }

        val positions = resolveStopPositions(parsedStops.map { it.second })
        return NamePaint(
            id = id,
            type = if (isLinear) "LINEAR_GRADIENT" else "RADIAL_GRADIENT",
            colors = parsedStops.map { it.first }.toIntArray(),
            colorPositions = positions,
            angle = angle,
            repeat = isRepeating,
            shadows = shadows.ifEmpty { null },
        )
    }

    private fun resolveStopPositions(rawPositions: List<Float?>): FloatArray {
        val n = rawPositions.size
        val result = FloatArray(n)
        val hasFirst = rawPositions[0] != null
        val hasLast = rawPositions[n - 1] != null
        result[0] = rawPositions[0] ?: 0f
        result[n - 1] = rawPositions[n - 1] ?: 1f

        for (i in 1 until n - 1) {
            rawPositions[i]?.let { result[i] = it }
        }

        var i = 0
        while (i < n) {
            if (i > 0 && rawPositions[i] == null && !(i == n - 1 && !hasLast)) {
                val startIdx = i - 1
                var endIdx = i
                while (endIdx < n - 1 && rawPositions[endIdx] == null) {
                    endIdx++
                }
                val startVal = result[startIdx]
                val endVal = result[endIdx]
                val span = endIdx - startIdx
                for (k in 1 until span) {
                    result[startIdx + k] = startVal + (endVal - startVal) * (k.toFloat() / span.toFloat())
                }
                i = endIdx + 1
            } else {
                if (i == 0 && !hasFirst) result[0] = 0f
                i++
            }
        }

        for (idx in 1 until n) {
            if (result[idx] < result[idx - 1]) {
                result[idx] = result[idx - 1]
            }
        }
        return result
    }

    private fun parseLinearDirectionAngle(arg: String): Int? {
        val s = arg.trim().lowercase()
        angleDegRegex.find(s)?.let { match ->
            val deg = match.groupValues[1].toFloatOrNull() ?: return null
            return ((deg.roundToInt() % 360) + 360) % 360
        }
        if (s.startsWith("to ")) {
            val dir = s.removePrefix("to ").trim().replace(Regex("\\s+"), " ")
            return when (dir) {
                "top" -> 0
                "top right", "right top" -> 45
                "right" -> 90
                "bottom right", "right bottom" -> 135
                "bottom" -> 180
                "bottom left", "left bottom" -> 225
                "left" -> 270
                "top left", "left top" -> 315
                else -> null
            }
        }
        return null
    }

    fun parseDropShadows(filterCss: String?): List<NamePaint.Shadow> {
        if (filterCss.isNullOrBlank()) return emptyList()
        val shadows = mutableListOf<NamePaint.Shadow>()
        val lower = filterCss.lowercase()
        var searchFrom = 0
        while (searchFrom < lower.length) {
            val tagIdx = lower.indexOf("drop-shadow(", searchFrom)
            if (tagIdx < 0) break
            val openParen = tagIdx + "drop-shadow".length
            val closeParen = findMatchingCloseParen(filterCss, openParen)
            if (closeParen <= openParen) break
            val content = filterCss.substring(openParen + 1, closeParen).trim()
            searchFrom = closeParen + 1

            val color = findFirstColorInStop(content) ?: continue
            val withoutColor = removeFirstColorFromStop(content).trim()
            val nums = pxNumberRegex.findAll(withoutColor)
                .mapNotNull { it.groupValues[1].toFloatOrNull() }
                .toList()
            if (nums.size >= 2) {
                val dx = nums[0]
                val dy = nums[1]
                val radius = nums.getOrElse(2) { 1f }.coerceAtLeast(0.1f)
                shadows.add(NamePaint.Shadow(dx, dy, radius, color))
            }
        }
        return shadows
    }

    fun parseCssColor(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        rgbFuncRegex.matchEntire(trimmed)?.let { return parseRgbMatch(it) }
        if (trimmed.startsWith("#")) {
            return parseHexColor(trimmed.removePrefix("#"))
        }
        return try {
            Color.parseColor(trimmed)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun findFirstColorInStop(stop: String): Int? {
        rgbFuncRegex.find(stop)?.let { return parseRgbMatch(it) }
        hexColorRegex.find(stop)?.let { return parseHexColor(it.groupValues[1]) }
        val firstToken = stop.trim().split(Regex("\\s+")).firstOrNull() ?: return null
        return parseCssColor(firstToken)
    }

    private fun removeFirstColorFromStop(stop: String): String {
        rgbFuncRegex.find(stop)?.let { return stop.removeRange(it.range) }
        hexColorRegex.find(stop)?.let { return stop.removeRange(it.range) }
        val trimmed = stop.trim()
        val spaceIdx = trimmed.indexOf(' ')
        return if (spaceIdx > 0) trimmed.substring(spaceIdx + 1) else ""
    }

    private fun parseRgbMatch(match: MatchResult): Int? {
        val r = match.groupValues[1].toFloatOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
        val g = match.groupValues[2].toFloatOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
        val b = match.groupValues[3].toFloatOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
        val alphaRaw = match.groupValues[4]
        val a = if (alphaRaw.isEmpty()) {
            255
        } else if (alphaRaw.endsWith("%")) {
            val pct = alphaRaw.removeSuffix("%").toFloatOrNull() ?: 100f
            ((pct / 100f) * 255f).roundToInt().coerceIn(0, 255)
        } else {
            val f = alphaRaw.toFloatOrNull() ?: 1f
            (if (f <= 1f) f * 255f else f).roundToInt().coerceIn(0, 255)
        }
        return Color.argb(a, r, g, b)
    }

    private fun parseHexColor(hex: String): Int? {
        return try {
            when (hex.length) {
                3 -> {
                    val r = hex[0].digitToInt(16) * 17
                    val g = hex[1].digitToInt(16) * 17
                    val b = hex[2].digitToInt(16) * 17
                    Color.rgb(r, g, b)
                }
                4 -> {
                    val r = hex[0].digitToInt(16) * 17
                    val g = hex[1].digitToInt(16) * 17
                    val b = hex[2].digitToInt(16) * 17
                    val a = hex[3].digitToInt(16) * 17
                    Color.argb(a, r, g, b)
                }
                6 -> {
                    val rgb = hex.toInt(16)
                    (0xFF000000.toInt()) or rgb
                }
                8 -> {
                    // CSS 8-digit hex is #RRGGBBAA
                    val rr = hex.substring(0, 2).toInt(16)
                    val gg = hex.substring(2, 4).toInt(16)
                    val bb = hex.substring(4, 6).toInt(16)
                    val aa = hex.substring(6, 8).toInt(16)
                    Color.argb(aa, rr, gg, bb)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findMatchingCloseParen(s: String, openIndex: Int): Int {
        if (openIndex < 0 || openIndex >= s.length || s[openIndex] != '(') return -1
        var depth = 0
        for (i in openIndex until s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun splitTopLevelArgs(inner: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in inner.indices) {
            when (inner[i]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    val part = inner.substring(start, i).trim()
                    if (part.isNotEmpty()) result.add(part)
                    start = i + 1
                }
            }
        }
        val tail = inner.substring(start).trim()
        if (tail.isNotEmpty()) result.add(tail)
        return result
    }
}
