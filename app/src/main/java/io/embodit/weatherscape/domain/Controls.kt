package io.embodit.weatherscape.domain

/**
 * v0.1.2 (gate B C1 / C2 / M17 / M18, user 11-13 "时刻分到的太细不太好点，时刻应该显示在那个 UI 可以点的区域的横轴"):
 * the 48 h strip is 8 tappable blocks of 6 hours; a tap jumps to the block's middle hour. The clock ticks (0 / 6 / 12 /
 * 18 and the day names) sit on the bottom edge of the same tappable strip.
 */
object StripMath {
    const val BLOCKS = 8
    const val BLOCK_H = 6

    fun blockOf(h: Int): Int = (h / BLOCK_H).coerceIn(0, BLOCKS - 1)

    /** A block is greyed out when its first hour is past the ring's end (M18). */
    fun enabled(block: Int, end: Int): Boolean = block * BLOCK_H <= end

    /** Where a tap on [block] turns the ring: the block's middle hour, at most the end. */
    fun target(block: Int, end: Int): Int = minOf(block * BLOCK_H + BLOCK_H / 2, end)

    /** One axis label: [hour] = slice hour it marks, [day] = true for a day name (local midnight / today at 0). */
    data class Tick(val hour: Int, val text: String, val day: Boolean)

    /**
     * Axis ticks for the strip: local 0 / 6 / 12 / 18 h (midnight carries the day name instead of "0"), today's name at
     * hour 0. [hourWord] = "6 时" / "6a" etc.
     */
    fun ticks(hods: List<Int>, days: List<Int>, end: Int, dayName: (Int) -> String, hourWord: (Int) -> String): List<Tick> {
        val out = ArrayList<Tick>()
        val n = minOf(hods.size, end + 1)
        for (i in 0 until n) {
            val midnight = hods[i] == 0 && i > 0
            when {
                i == 0 -> out += Tick(0, dayName(days[0]), true)
                midnight -> out += Tick(i, dayName(days[i]), true)
                hods[i] % 6 == 0 -> out += Tick(i, hourWord(hods[i]), false)
            }
        }
        return out
    }

    /**
     * Greedy label placement on the axis: the midnight day names first, then today's name, then the hour ticks. Day
     * names start at their day line ([edge] = hour -> its left edge x), hour ticks are centred on the hour ([x]); a label
     * is dropped when it would overlap one already placed ([gap] apart) or leave the strip. [w] = label width.
     */
    fun place(ticks: List<Tick>, width: Float, gap: Float, x: (Int) -> Float, w: (Tick) -> Float, edge: (Int) -> Float = x): List<Pair<Tick, Float>> {
        val placed = ArrayList<Pair<Tick, ClosedFloatingPointRange<Float>>>()
        val order = ticks.sortedWith(compareBy({ if (it.day && it.hour > 0) 0 else if (it.day) 1 else 2 }, { it.hour }))
        for (t in order) {
            val ww = w(t)
            val left = if (t.day) edge(t.hour) else x(t.hour) - ww / 2
            val l = left.coerceAtLeast(0f)
            val r = l + ww
            if (r > width) continue
            if (placed.any { (_, span) -> l < span.endInclusive + gap && r > span.start - gap }) continue
            placed += t to (l..r)
        }
        return placed.sortedBy { it.first.hour }.map { it.first to it.second.start }
    }

    /**
     * M17: fewest presses from [from] to [to] with the window's controls (tap a block, ◀, ▶, back to now). BFS over
     * hours; ◀ / ▶ move one hour, a block tap jumps to [target].
     */
    fun presses(from: Int, to: Int, end: Int): Int {
        if (from == to) return 0
        val dist = IntArray(end + 1) { -1 }
        dist[from] = 0
        val q = ArrayDeque<Int>().apply { add(from) }
        while (q.isNotEmpty()) {
            val h = q.removeFirst()
            val next = buildList {
                if (h > 0) add(h - 1)
                if (h < end) add(h + 1)
                add(0)
                for (b in 0 until BLOCKS) if (enabled(b, end)) add(target(b, end))
            }
            for (n in next) if (dist[n] < 0) { dist[n] = dist[h] + 1; if (n == to) return dist[n]; q.add(n) }
        }
        return Int.MAX_VALUE
    }
}

/**
 * ◀ / ▶ press-and-hold (M18): one hour on press, then 4 h/s after [DELAY_MS] while held; a release stops at once. The same
 * object serves the window buttons and the 3D buttons beside the ring (D53), so both repeat alike.
 */
class HourHold {
    companion object {
        const val DELAY_MS = 450L
        const val REPEAT_MS = 250L
        /** A system tap that arrives right after a press of the same button is the same press (no double step). */
        const val TAP_DEDUP_MS = 700L
        /** ... or right after its release (a long press ends with the system tap too). */
        const val RELEASE_DEDUP_MS = 400L
    }

    var dir = 0
        private set
    private var next = 0L
    private var pressedAt = -1L
    private var pressedDir = 0
    private var releasedAt = -1L

    /** Press: returns the step to make now (±1). */
    fun press(d: Int, ms: Long): Int {
        dir = d; next = ms + DELAY_MS; pressedAt = ms; pressedDir = d
        return d
    }

    fun release(ms: Long = -1L) { if (dir != 0) releasedAt = ms; dir = 0 }

    /** Per frame: the steps due by [ms] (0 or ±1 each REPEAT_MS). */
    fun tick(ms: Long): Int {
        if (dir == 0 || ms < next) return 0
        next += REPEAT_MS
        if (next < ms) next = ms + REPEAT_MS
        return dir
    }

    /** A plain tap with no press seen (some inputs only send the tap): one step, unless the press already stepped. */
    fun tap(d: Int, ms: Long): Int {
        val samePress = pressedDir == d && pressedAt >= 0 && (ms - pressedAt < TAP_DEDUP_MS || (releasedAt >= pressedAt && ms - releasedAt < RELEASE_DEDUP_MS))
        return if (samePress) 0 else d
    }
}

/**
 * B24 (user 11-13 "云怎么是黑的，应该是白云啊，烟囱的烟也是"): clouds, smoke, fog and snow carry an emissive base so they stay
 * white even when the PBR light in a Shared Space volume adds almost nothing (seen on the headset: white PBR rendered
 * near black). Overcast / night only go down to light grey.
 *
 * base = rgb x brightness (the scene's dimming), emissive = rgb x [emissiveK] (>= 40 % of the base by design rule).
 */
object WhiteLook {
    /** Cloud colour by shade (0 fair, 1 overcast / fog, 2 rain / snow, 3 storm): light greys only. */
    val CLOUD = intArrayOf(0xF6F7F8, 0xEAECEF, 0xDCDFE3, 0xCDD1D6)
    /** v0.1.4 (user 11-14: overcast / rain clouds turn grey): the colour the Tripo clouds' texture is multiplied by;
     *  the darkest stays light grey: final brightness >= 0.6 by day (B24, ControlsTest). */
    val CLOUD_TINT = intArrayOf(0xFFFFFF, 0xE4E7EA, 0xD7DBDF, 0xD0D5DA)
    const val SMOKE = 0xF1F2F3
    const val FOG = 0xE6EAEE
    const val FLAKE = 0xF7F9FB

    /** Emissive share of the colour at [brightness] (day 1.0 -> 0.83, night 0.45 -> 0.69). */
    fun emissiveK(brightness: Float): Float = 0.58f + 0.25f * brightness.coerceIn(0f, 1f)

    data class Look(val base: FloatArray, val emissive: FloatArray)

    fun look(rgb: Int, brightness: Float, emissiveScale: Float = 1f): Look {
        val c = floatArrayOf((rgb shr 16 and 255) / 255f, (rgb shr 8 and 255) / 255f, (rgb and 255) / 255f)
        val b = brightness.coerceIn(0f, 1f)
        val e = emissiveK(brightness) * emissiveScale
        return Look(FloatArray(3) { c[it] * b }, FloatArray(3) { c[it] * e })
    }

    /**
     * v0.1.4 Tripo clouds: the emissive carries the shaded cloud texture (emissiveTexture = base texture), the lit base is
     * halved so light + emissive don't burn the puffs to a flat white cut-out (emulator 11-14 shots).
     */
    fun cloud(shade: Int, brightness: Float) = look(CLOUD_TINT[shade.coerceIn(0, 3)], brightness).let { l -> Look(FloatArray(3) { l.base[it] * 0.5f }, l.emissive) }

    /** v0.1.4: lowest colour factor of the unlit snow-crystal / smoke-wisp cards (B24: never darker than light grey). */
    const val CARD_MIN = 0.62f
    /** Colour factor of the unlit cards at [brightness]: follows the scene, never below [CARD_MIN]. */
    fun cardK(brightness: Float): Float = maxOf(CARD_MIN, minOf(1f, 0.25f + 0.75f * brightness))
    /** Snow crystals stay near white at night too (snow is the brightest thing in a night scene). */
    fun crystalK(brightness: Float): Float = maxOf(0.88f, cardK(brightness))

    /** Relative luminance (Rec. 709 weights on the 0..1 values). */
    fun lum(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    /**
     * Final brightness the user sees in the worst case seen on the headset: the PBR light adds nothing, only the
     * emissive shows (clamped to 1).
     */
    fun worstCase(l: Look): Float = lum(FloatArray(3) { minOf(1f, l.emissive[it]) })
}
