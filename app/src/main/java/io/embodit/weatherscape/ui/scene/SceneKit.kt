package io.embodit.weatherscape.ui.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.weatherscape.domain.Layout
import io.embodit.weatherscape.domain.MeshData
import io.embodit.weatherscape.domain.Q4
import io.embodit.weatherscape.domain.Ring
import io.embodit.weatherscape.domain.V3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun Entity.at(p: V3, q: Q4? = null, s: Float? = null) {
    transform().setPosition(Vector3(p.x, p.y, p.z))
    q?.let { transform().setQuaternion(Quat(it.x, it.y, it.z, it.w)) }
    s?.let { transform().setScaleVector(Vector3(it, it, it)) }
}

internal fun Entity.scale(x: Float, y: Float, z: Float) { transform().setScaleVector(Vector3(x, y, z)) }

/** 0xRRGGBB -> Color4, [k] scales the colour (brightness / emissive strength). */
internal fun rgb4(rgb: Int, k: Float = 1f, a: Float = 1f) = Color4((rgb shr 16 and 255) / 255f * k, (rgb shr 8 and 255) / 255f * k, (rgb and 255) / 255f * k, a)

internal object Kit {
    /** A [MeshData] as an SDK mesh (creation waits on the renderer: call off the main thread). */
    fun mesh(m: MeshData, name: String): MeshResource {
        val pos = ArrayList<Vector3>(m.vertexCount); val nor = ArrayList<Vector3>(m.vertexCount); val uv = ArrayList<Vector2>(m.vertexCount)
        for (i in 0 until m.vertexCount) {
            pos += Vector3(m.pos[3 * i], m.pos[3 * i + 1], m.pos[3 * i + 2])
            nor += Vector3(m.nor[3 * i], m.nor[3 * i + 1], m.nor[3 * i + 2])
            uv += Vector2(m.uv[2 * i], m.uv[2 * i + 1])
        }
        val (lo, hi) = m.bounds()
        val c = (lo + hi) * 0.5f
        val h = (hi - lo) * 0.5f
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = pos, triangleIndices = m.idx.toList(), normals = nor, uv0 = uv),
            bounds = BoundingBox(center = Vector3(c.x, c.y, c.z), halfExtent = Vector3(maxOf(h.x, 0.001f), maxOf(h.y, 0.001f), maxOf(h.z, 0.001f))),
            name = name,
        )
    }

    fun pbr(rgb: Int, rough: Float, metal: Float = 0f, cull: Boolean = true): PhysicallyBasedMaterial =
        PhysicallyBasedMaterial.create().apply { setBaseColor(rgb4(rgb)); setRoughness(rough); setMetallic(metal); if (!cull) setCullingMode(MaterialCullingMode.NONE) }

    fun unlit(rgb: Int, opacity: Float = 1f): UnlitMaterial =
        UnlitMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
            setBaseColor(rgb4(rgb))
            if (opacity < 1f) setOpacity(opacity)
            setCullingMode(MaterialCullingMode.NONE)
            setApplyToneMapping(false)
        }
}

/**
 * Canvas textures. Convention (D10, checked with the F↑ test via debug `testf`): image up = +Y on upright quads; on the
 * ring face the image's top row is the far (-Z) side and its right = +X, so words at the front read upright to the user.
 */
internal object Tex {
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun walnut(): Bitmap {
        val w = 1024; val h = 256
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF5A3B26.toInt())
        val rnd = Random(3)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        repeat(110) { i ->
            val dark = rnd.nextFloat() < 0.7f
            p.color = if (dark) android.graphics.Color.argb((60 + rnd.nextInt(70)), 28, 16, 9) else android.graphics.Color.argb(40 + rnd.nextInt(40), 140, 98, 62)
            p.strokeWidth = 1f + rnd.nextFloat() * 3f
            val y = rnd.nextFloat() * h; val ph = rnd.nextFloat() * 6f
            val path = android.graphics.Path()
            path.moveTo(0f, y)
            var x = 0f
            while (x <= w) { path.lineTo(x, y + 4f * sin(x / 70f + ph + i) + rnd.nextFloat() * 1.5f); x += 16f }
            c.drawPath(path, p)
        }
        return bmp
    }

    fun brass(): Bitmap {
        val w = 512; val h = 64
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFFB08A3E.toInt(), 0xFFD2B064.toInt(), 0xFFA27E36.toInt()), null, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val rnd = Random(5)
        val s = Paint()
        repeat(260) {
            s.color = android.graphics.Color.argb(rnd.nextInt(24), 255, 240, 205)
            c.drawRect(rnd.nextFloat() * w, rnd.nextFloat() * h, rnd.nextFloat() * w + 60f, 0f + rnd.nextFloat() * h + 1f, s)
        }
        return bmp
    }

    /**
     * The ring's engraved face: 48 hour ticks (long every 6 h), the city's local clock hours 0 / 6 / 12 / 18 at the long
     * ticks, and the day names at local midnights (+ today's at the start). [hods] = local hour of each slice hour,
     * [days] = its day index. Ticks beyond [end] are left blank. Planar uv: see Pieces.ringFace.
     */
    fun ringFace(hods: List<Int>, days: List<Int>, end: Int, dayName: (Int) -> String, testF: Boolean): Bitmap {
        val n = 1024
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // brass base (brushed in rings)
        c.drawColor(0xFFC09A4C.toInt())
        val rnd = Random(9)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f }
        val R = Layout.RING_OUT
        fun px(r: Float) = r / R * n / 2f
        repeat(120) {
            ring.color = android.graphics.Color.argb(rnd.nextInt(30), if (rnd.nextBoolean()) 255 else 90, if (rnd.nextBoolean()) 236 else 70, 180)
            val rr = px(Layout.RING_IN + rnd.nextFloat() * (Layout.RING_OUT - Layout.RING_IN))
            c.drawCircle(n / 2f, n / 2f, rr, ring)
        }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B1D0C.toInt(); strokeCap = Paint.Cap.ROUND }
        val rOut = Layout.RING_OUT - 0.006f
        for (h in 0..minOf(end, hods.size - 1)) {
            val a = Layout.tickAngle(h.toFloat())
            val long = hods[h] % 6 == 0
            val r0 = rOut - if (long) 0.016f else 0.008f
            ink.strokeWidth = if (long) 4.5f else 3f
            val (x0, y0) = n / 2f + px(r0) * sin(a) to n / 2f + px(r0) * cos(a)
            val (x1, y1) = n / 2f + px(rOut) * sin(a) to n / 2f + px(rOut) * cos(a)
            c.drawLine(x0, y0, x1, y1, ink)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B1D0C.toInt(); typeface = bold; textAlign = Paint.Align.CENTER }
        fun label(h: Int, s: String, r: Float, size: Float) {
            val a = Layout.tickAngle(h.toFloat())
            val x = n / 2f + px(r) * sin(a); val y = n / 2f + px(r) * cos(a)
            text.textSize = size
            c.save()
            c.rotate(h * Ring.DEG_PER_H, x, y)
            c.drawText(s, x, y + size * 0.36f, text)
            c.restore()
        }
        for (h in 0..minOf(end, hods.size - 1)) {
            if (hods[h] % 6 == 0) label(h, hods[h].toString(), Layout.RING_OUT - 0.031f, 30f)
            val midnight = hods[h] == 0 && h > 0
            if (midnight || h == 0) label(h, dayName(days[h]), Layout.RING_IN + 0.0115f, 26f)
        }
        if (testF) label(0, "F↑", Layout.RING_OUT - 0.046f, 34f)
        return bmp
    }

    /** Readout face: cream card, two lines (time / sky · temperature). 1528 x 200 for a 0.275 x 0.036 m face. */
    fun readout(l1: String, l2: String, testF: Boolean): Bitmap {
        val w = 1528; val h = 200
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFF3EAD6.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B1D0E.toInt(); typeface = medium }
        p.textSize = 74f
        c.drawText(l1, 40f, 84f, p)
        p.typeface = bold; p.textSize = 78f
        c.drawText(l2, 40f, 178f, p)
        if (testF) { p.textSize = 90f; p.textAlign = Paint.Align.RIGHT; c.drawText("F↑", w - 30f, 150f, p) }
        return bmp
    }

    /** A small white tag with one line (S05), 640 x 160 for 0.16 x 0.04 m; text shrinks to fit (no ellipsis). */
    fun tag(s: String): Bitmap {
        val w = 640; val h = 160
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF6E6A62.toInt())
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFCFBF7.toInt() }
        c.drawRoundRect(RectF(6f, 6f, w - 6f, h - 6f), 36f, 36f, bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B1F23.toInt(); typeface = bold; textAlign = Paint.Align.CENTER; textSize = 66f }
        while (p.measureText(s) > w - 60f && p.textSize > 30f) p.textSize -= 2f
        c.drawText(s, w / 2f, h / 2f + p.textSize * 0.36f, p)
        return bmp
    }

    /** The knob's engraved word ("面板" / "Panel"), 256 x 96. */
    fun knob(s: String): Bitmap {
        val w = 256; val h = 96
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF7A5B22.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF8EDCF.toInt(); typeface = bold; textAlign = Paint.Align.CENTER; textSize = 58f }
        while (p.measureText(s) > w - 20f && p.textSize > 20f) p.textSize -= 2f
        c.drawText(s, w / 2f, h / 2f + p.textSize * 0.36f, p)
        return bmp
    }

    /**
     * A ◀ / ▶ button face (v0.1.2), 256 x 256 for a Ø 4.6 cm disc: dark brass, a big pale chevron and the word "1 小时" /
     * "1 hr" under it. [d] < 0 points left. Greyed by the material colour, not here.
     */
    fun step(d: Int, word: String, testF: Boolean): Bitmap {
        val n = 256
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFB08A3E.toInt())
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6E5120.toInt() }
        c.drawCircle(n / 2f, n / 2f, n / 2f - 2f, disc)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFBF1D6.toInt(); style = Paint.Style.STROKE; strokeWidth = 26f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        // v0.1.3 (QA #3 q3-4, D13): the word was 44 px (0.27°); now >= 72 px on a face x 1.42 (Layout.STEP_LABEL_*):
        // cap height >= 0.5° from the design eye (StepLabelTest). The arrow moves up and gets smaller to make room.
        val cx = n / 2f + if (d < 0) -4f else 4f; val cy = n * 0.31f; val a = 28f
        ink.strokeWidth = 20f
        val path = android.graphics.Path()
        if (d < 0) { path.moveTo(cx + a * 0.6f, cy - a); path.lineTo(cx - a * 0.6f, cy); path.lineTo(cx + a * 0.6f, cy + a) }
        else { path.moveTo(cx - a * 0.6f, cy - a); path.lineTo(cx + a * 0.6f, cy); path.lineTo(cx - a * 0.6f, cy + a) }
        c.drawPath(path, ink)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFBF1D6.toInt(); typeface = bold; textAlign = Paint.Align.CENTER; textSize = io.embodit.weatherscape.domain.Layout.STEP_LABEL_PX }
        while (p.measureText(word) > n * 0.86f && p.textSize > io.embodit.weatherscape.domain.Layout.STEP_LABEL_MIN_PX) p.textSize -= 2f
        c.drawText(word, n / 2f, n * 0.78f, p)
        if (testF) { p.textSize = 40f; c.drawText("F↑", n / 2f, n * 0.18f, p) }
        return bmp
    }

    /**
     * v0.1.4 (user 11-14 "❄️也是" white blobs): one six-armed snow crystal, white on transparent, 256 x 256 for a 1.0 -
     * 1.4 cm card. Each arm has a thick stem, two pairs of side branches and a small tip fork; a hexagonal plate in the
     * middle and a faint blue-grey edge so the shape reads against the white clouds and the snow-covered island.
     */
    fun crystal(): Bitmap {
        val n = 256
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = n / 2f; val r = n * 0.46f
        fun arms(p: Paint, w: Float) {
            p.strokeWidth = w
            for (k in 0 until 6) {
                c.save(); c.rotate(60f * k, cx, cx)
                c.drawLine(cx, cx, cx, cx - r, p)
                for ((at, len) in listOf(0.38f to 0.30f, 0.62f to 0.24f, 0.84f to 0.12f)) {
                    val y = cx - r * at; val l = r * len
                    c.drawLine(cx, y, cx - l * 0.82f, y - l * 0.57f, p)
                    c.drawLine(cx, y, cx + l * 0.82f, y - l * 0.57f, p)
                }
                c.restore()
            }
            val hex = android.graphics.Path()
            for (k in 0 until 6) { val a = Math.toRadians(60.0 * k + 30).toFloat(); val x = cx + r * 0.20f * cos(a); val y = cx + r * 0.20f * sin(a); if (k == 0) hex.moveTo(x, y) else hex.lineTo(x, y) }
            hex.close()
            c.drawPath(hex, p)
        }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB04E6478.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        arms(edge, 30f)
        val ice = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        arms(ice, 17f)
        ice.style = Paint.Style.FILL
        c.drawCircle(cx, cx, r * 0.13f, ice)
        return bmp
    }

    /**
     * v0.1.4: one soft smoke wisp, 256 x 256, white with alpha: a few overlapping soft blobs along a little curl, densest
     * in the middle, fading to nothing at the card edge (no visible square). [seed] varies the shape.
     */
    fun wisp(seed: Int): Bitmap {
        val n = 256
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rnd = Random(seed)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(9) { k ->
            val t = k / 8f
            val x = n / 2f + n * 0.16f * sin(t * 5f + seed) + (rnd.nextFloat() - 0.5f) * n * 0.08f
            val y = n * (0.72f - 0.44f * t) + (rnd.nextFloat() - 0.5f) * n * 0.06f
            val rr = n * (0.14f + 0.10f * rnd.nextFloat())
            p.shader = android.graphics.RadialGradient(x, y, rr, intArrayOf(0x99FFFFFF.toInt(), 0x44FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, rr, p)
        }
        return bmp
    }

    /** v0.1.4: the sun's soft halo, 256 x 256: warm white core fading through gold to transparent at the edge. */
    fun halo(): Bitmap {
        val n = 256
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(n / 2f, n / 2f, n / 2f, intArrayOf(0xCCFFE7A6.toInt(), 0x88FFC861.toInt(), 0x33FFB040, 0x00FFA830), floatArrayOf(0f, 0.38f, 0.7f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawCircle(n / 2f, n / 2f, n / 2f, p)
        return bmp
    }

    /**
     * Unlit material for a texture card: base colour x texture. [mode]: MASKED = alpha cut-out at 0.5, drawn in the opaque
     * pass (snow crystals, M8); TRANSPARENT = blended with [opacity] (smoke wisps fade out); ADD = additive glow (sun halo).
     */
    fun cardMat(tex: TextureResource, rgb: Int = 0xFFFFFF, opacity: Float = 1f, mode: BlendingMode = BlendingMode.TRANSPARENT): UnlitMaterial =
        UnlitMaterial.create(mode).apply {
            setBaseColor(rgb4(rgb)); setBaseColorTexture(tex)
            if (mode == BlendingMode.MASKED) setOpacityThreshold(0.5f) else setOpacity(opacity)
            setCullingMode(MaterialCullingMode.NONE); setApplyToneMapping(false)
        }

    @Suppress("unused") private fun unused() = cos(0f)
}
