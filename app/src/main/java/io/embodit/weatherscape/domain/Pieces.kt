package io.embodit.weatherscape.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The procedural parts of the diorama (design entities3d / assets "程序化"): true-circle lathes with smooth normals for the
 * plinth, ring, knob and pin (B 圆部件 rule: >= 64 segments), the four rain sheets (one merged mesh each),
 * texture cards (snow crystals, smoke wisps, the sun's halo: v0.1.4, the sun / moon / clouds / smoke curl are Tripo models), the moon's phase shell, the iron vane, the roof snow cap,
 * the window / lamp glows and pond ripples. All opaque. Every piece is in its own local frame (documented per piece).
 */
object Pieces {
    private const val TAU = (2 * PI).toFloat()

    /** Walnut drum Ø 0.40 x 0.07, origin = bottom centre, 3 mm rounded rims. */
    fun plinth(): MeshData {
        val r = Layout.PLINTH_R; val h = Layout.PLINTH_Y1 - Layout.PLINTH_Y0; val c = 0.003f
        return Shapes.lathe(V3.ZERO, listOf(0f to 0f, r - c to 0f, r to c, r to h - c, r - c to h, 0f to h), 96)
    }

    /** Flat brass band (inner 0.215, outer 0.280, 2.5 cm, 3 mm chamfers on all four edges), origin = band centre. */
    fun ringBand(): MeshData {
        val a = Layout.RING_IN; val b = Layout.RING_OUT; val y0 = -Layout.RING_H / 2; val y1 = Layout.RING_H / 2; val c = Layout.CHAMFER
        return Shapes.lathe(V3.ZERO, listOf(a + c to y0, b - c to y0, b to y0 + c, b to y1 - c, b - c to y1, a + c to y1, a to y1 - c, a to y0 + c, a + c to y0), 128)
    }

    /**
     * The engraved top face of the ring (an annulus 0.4 mm above the band top) with planar uv: u = (x / R + 1) / 2,
     * v = 1 - (z / R + 1) / 2 with R = the outer radius, so the texture's top row is the far (-Z) side.
     */
    fun ringFace(): MeshData {
        val b = MeshBuilder()
        val r0 = Layout.RING_IN + Layout.CHAMFER; val r1 = Layout.RING_OUT - Layout.CHAMFER; val y = Layout.RING_H / 2 + 0.0004f
        val R = Layout.RING_OUT
        val seg = 128
        for (k in 0..seg) {
            val a = TAU * k / seg
            for (r in listOf(r0, r1)) {
                val p = V3(r * sin(a), y, r * cos(a))
                b.v(p, V3.UP, (p.x / R + 1f) / 2f, 1f - (p.z / R + 1f) / 2f)
            }
        }
        for (k in 0 until seg) { val i = 2 * k; b.quad(i, i + 2, i + 3, i + 1) }
        return Shapes.fixWinding(b.build())
    }

    /** Stop pin Ø 8 mm x 1.2 cm, origin = its bottom centre. */
    fun pin(): MeshData = Shapes.lathe(V3.ZERO, listOf(0f to 0f, Layout.PIN_D / 2 to 0f, Layout.PIN_D / 2 to Layout.PIN_H - 0.001f, Layout.PIN_D / 2 - 0.001f to Layout.PIN_H, 0f to Layout.PIN_H), 32)

    /** Readout case: a brass box 0.30 x 0.0425 x 0.012 centred on its origin (the entity leans it back 25°). */
    fun readoutCase(): MeshData = Shapes.box(V3.ZERO, V3(Layout.READOUT_W, Layout.READOUT_H, Layout.READOUT_T))

    /** A quad [w] x [h] facing +Z, uv (0,0) bottom-left (image up = +Y). */
    fun quad(w: Float, h: Float): MeshData {
        val b = MeshBuilder()
        val n = V3(0f, 0f, 1f)
        val a = b.v(V3(-w / 2, -h / 2, 0f), n, 0f, 0f)
        val bb = b.v(V3(w / 2, -h / 2, 0f), n, 1f, 0f)
        val c = b.v(V3(w / 2, h / 2, 0f), n, 1f, 1f)
        val d = b.v(V3(-w / 2, h / 2, 0f), n, 0f, 1f)
        b.quad(a, bb, c, d)
        return b.build()
    }

    /** The pointer: a flat brass triangle 1.2 cm wide, 6.2 cm long, 6 mm thick; tip at the origin pointing -Z (to the ring). */
    fun pointer(): MeshData {
        val w = 0.006f; val l = 0.062f; val t = 0.006f
        val tri = listOf(V3(0f, 0f, 0f), V3(w, 0f, l), V3(-w, 0f, l))
        val top = tri.map { it + V3(0f, t, 0f) }
        val parts = ArrayList<MeshData>()
        parts += Shapes.fan(top, V3.UP)
        parts += Shapes.fan(tri.reversed(), V3(0f, -1f, 0f))
        for (i in 0 until 3) {
            val a = tri[i]; val b = tri[(i + 1) % 3]
            val n = (b - a).cross(V3.UP).norm()
            parts += Shapes.fan(listOf(a, b, b + V3(0f, t, 0f), a + V3(0f, t, 0f)), n * -1f)
        }
        return Shapes.merge(parts)
    }

    /** The "Panel" knob: a brass disc Ø 5.5 cm x 2 cm with a rounded front, axis along +Z, origin = its back centre. */
    fun knob(): MeshData {
        val r = Layout.KNOB_R; val t = Layout.KNOB_T
        val m = Shapes.lathe(V3.ZERO, listOf(0f to 0f, r to 0f, r to t - 0.004f, r - 0.003f to t - 0.001f, r - 0.006f to t, 0f to t), 64)
        // lathe is about +Y: turn it so +Y becomes +Z
        return transform(m, Q4.axis(V3(1f, 0f, 0f), (PI / 2).toFloat()))
    }

    /** A ◀ / ▶ button (v0.1.2): a brass disc Ø 6.2 cm x 2 cm with a rounded front, axis +Z, origin = its centre. */
    fun stepButton(): MeshData {
        val r = Layout.STEP_R; val t = Layout.STEP_T
        val m = Shapes.lathe(V3.ZERO, listOf(0f to 0f, r to 0f, r to t - 0.005f, r - 0.003f to t - 0.0015f, r - 0.007f to t, 0f to t), 64)
        return transform(m, Q4.axis(V3(1f, 0f, 0f), (PI / 2).toFloat()), V3(0f, 0f, -t / 2))
    }

    /** A round face [r] facing +Z (48 segments), uv = the square image's inscribed circle (image up = +Y). */
    fun disc(r: Float, seg: Int = 48): MeshData {
        val b = MeshBuilder()
        val n = V3(0f, 0f, 1f)
        val c = b.v(V3.ZERO, n, 0.5f, 0.5f)
        val rim = (0..seg).map { k -> val a = 2 * PI.toFloat() * k / seg; b.v(V3(r * kotlin.math.cos(a), r * kotlin.math.sin(a), 0f), n, 0.5f + 0.5f * kotlin.math.cos(a), 0.5f + 0.5f * kotlin.math.sin(a)) }
        for (k in 0 until seg) b.tri(c, rim[k], rim[k + 1])
        return b.build()
    }

    fun transform(m: MeshData, q: Q4, t: V3 = V3.ZERO, s: V3 = V3(1f, 1f, 1f)): MeshData {
        val pos = FloatArray(m.pos.size); val nor = FloatArray(m.nor.size)
        for (i in 0 until m.vertexCount) {
            val p = q.rotate(V3(m.pos[3 * i] * s.x, m.pos[3 * i + 1] * s.y, m.pos[3 * i + 2] * s.z)) + t
            val n = q.rotate(V3(m.nor[3 * i] / s.x, m.nor[3 * i + 1] / s.y, m.nor[3 * i + 2] / s.z)).norm()
            pos[3 * i] = p.x; pos[3 * i + 1] = p.y; pos[3 * i + 2] = p.z
            nor[3 * i] = n.x; nor[3 * i + 1] = n.y; nor[3 * i + 2] = n.z
        }
        return MeshData(pos, nor, m.uv, m.idx)
    }

    /**
     * Low fog (design: two opaque cotton bands near the ground). v0.1.1 (QA #1): one long squashed cloud read as a grey
     * floating board, so a band is now a loose arc of 9 small soft wisps (smooth ellipsoids, 2-3.6 cm) that hug the
     * island rim at slightly different heights; origin = the island centre. Still opaque (M8: nothing transparent).
     */
    fun fogBand(seed: Int): MeshData {
        val rnd = Random(seed)
        val start = rnd.nextFloat() * TAU
        val parts = (0 until 9).map { j ->
            val a = start + j * 0.34f + (rnd.nextFloat() - 0.5f) * 0.12f
            val r = 0.125f + rnd.nextFloat() * 0.04f
            val rx = 0.020f + rnd.nextFloat() * 0.016f
            val y = (rnd.nextFloat() - 0.5f) * 0.014f
            val q = Q4.axis(V3.UP, a)
            transform(Shapes.sphere(V3.ZERO, rx, 16, 10), q, V3(r * sin(a), y, r * cos(a)), V3(1.25f, 0.42f, 0.85f))
        }
        return Shapes.merge(parts)
    }

    fun strands(seed: Int, n: Int): List<Pair<Float, Float>> {
        val rnd = Random(seed)
        return (0 until n).map {
            val r = kotlin.math.sqrt(rnd.nextFloat()) * (Layout.PRECIP_R - 0.004f)
            val a = rnd.nextFloat() * TAU
            r * sin(a) to r * cos(a)
        }
    }

    /**
     * A rain sheet: 16 strands x 5 drops = 80 opaque streaks 2 mm x 16 mm on a 5 cm lattice, from y 0 to the column height
     * + one step (the entity slides it down by (t x 0.6 m/s) mod 5 cm, so it falls without a seam). Origin = column bottom.
     */
    fun rainSheet(seed: Int, skip: ((Float, Float, Float) -> Boolean)? = null): MeshData {
        val h = Layout.PRECIP_Y1 - Layout.PRECIP_Y0 + Layout.RAIN_STEP
        val b = MeshBuilder()
        val rnd = Random(seed * 7 + 1)
        for ((x, z) in strands(seed, RAIN_STRANDS)) {
            var y = rnd.nextFloat() * Layout.RAIN_STEP
            while (y + 0.016f <= h) {
                if (skip?.invoke(x, y, z) == true) { y += Layout.RAIN_STEP; continue } // q3-2: this drop makes way for a tag
                for (k in 0 until 2) {
                    val a = if (k == 0) 0f else (PI / 2).toFloat()
                    val dx = 0.001f * cos(a); val dz = 0.001f * sin(a)
                    val n = V3(sin(a), 0f, cos(a))
                    val i0 = b.v(V3(x - dx, y, z + dz), n); val i1 = b.v(V3(x + dx, y, z - dz), n)
                    val i2 = b.v(V3(x + dx, y + 0.016f, z - dz), n); val i3 = b.v(V3(x - dx, y + 0.016f, z + dz), n)
                    b.quad(i0, i1, i2, i3)
                }
                y += Layout.RAIN_STEP
            }
        }
        return b.build().let { if (it.vertexCount == 0) Shapes.sphere(V3.ZERO, 0.0001f, 4, 3) else it } // never an empty mesh
    }
    const val RAIN_STRANDS = 16
    const val SNOW_STRANDS = 12
    fun rainStrands(seed: Int) = strands(seed, RAIN_STRANDS)
    fun snowStrands(seed: Int) = strands(seed + 50, SNOW_STRANDS)

    /**
     * v0.1.4: the moon's phase shade, a hemisphere shell of radius [r] around +Z (pole at +Z, 24 x 8 segments, normals
     * out, uv unused). The scene turns its pole away from the sun (MoonPhase.shadeTurn) so it darkens the night side.
     */
    fun shadeShell(r: Float, seg: Int = 24, rings: Int = 8): MeshData {
        val b = MeshBuilder()
        val idx = Array(rings + 1) { j ->
            val th = (PI / 2).toFloat() * j / rings // 0 at the pole .. 90° at the rim
            IntArray(seg + 1) { k ->
                val ph = TAU * k / seg
                val n = V3(sin(th) * cos(ph), sin(th) * sin(ph), cos(th))
                b.v(n * r, n, k / seg.toFloat(), j / rings.toFloat())
            }
        }
        for (j in 0 until rings) for (k in 0 until seg) b.quad(idx[j][k], idx[j + 1][k], idx[j + 1][k + 1], idx[j][k + 1])
        return Shapes.fixWinding(b.build())
    }

    fun sphere(r: Float, seg: Int = 24, rings: Int = 16): MeshData = Shapes.sphere(V3.ZERO, r, seg, rings)

    /** Vane base (static): a pole 2 cm and the four compass arms; origin = foot of the pole on the ridge. */
    fun vaneBase(): MeshData {
        val pole = Shapes.lathe(V3.ZERO, listOf(0f to 0f, 0.0012f to 0f, 0.0012f to 0.028f, 0f to 0.028f), 12)
        val arms = listOf(
            Shapes.box(V3(0f, 0.016f, 0f), V3(0.022f, 0.0012f, 0.0012f)),
            Shapes.box(V3(0f, 0.016f, 0f), V3(0.0012f, 0.0012f, 0.022f)),
        )
        return Shapes.merge(listOf(pole) + arms)
    }

    /** Vane arrow (turns about its pole): head toward +Z, tail fin toward -Z; origin = on the pole axis at the arrow height. */
    fun vaneArrow(): MeshData {
        val shaft = Shapes.box(V3(0f, 0f, 0f), V3(0.0018f, 0.0018f, 0.030f))
        val head = transform(Shapes.cone(V3.ZERO, 0.0035f, 0.008f, 12), Q4.axis(V3(1f, 0f, 0f), (PI / 2).toFloat()), V3(0f, 0f, 0.015f))
        val tail = Shapes.box(V3(0f, 0f, -0.0135f), V3(0.0012f, 0.008f, 0.007f))
        return Shapes.merge(listOf(shaft, head, tail))
    }

    /**
     * Roof snow cap for the calibrated stone house (ridge along Z at local y 0.073, eaves at x +-0.042, y 0.047, depth
     * 0.083): two 2 mm slabs on the roof planes. Origin = the house's bottom centre (its calibration origin).
     */
    fun roofSnow(): MeshData {
        val ridge = 0.0745f; val eaveY = 0.0485f; val eaveX = 0.044f; val hz = 0.044f; val t = 0.002f
        fun slab(sx: Float): MeshData {
            val a = V3(0f, ridge, -hz); val b = V3(0f, ridge, hz); val c = V3(sx * eaveX, eaveY, hz); val d = V3(sx * eaveX, eaveY, -hz)
            val n = (b - a).cross(c - a).norm().let { if (it.y < 0) it * -1f else it }
            val up = n * t
            return Shapes.merge(listOf(Shapes.fan(listOf(a + up, b + up, c + up, d + up), n), Shapes.fan(listOf(a, b, c, d), n * -1f),
                Shapes.fan(listOf(c, d, d + up, c + up), V3(sx, 0f, 0f)), Shapes.fan(listOf(b, c, c + up, b + up), V3(0f, 0f, 1f)), Shapes.fan(listOf(a, d, d + up, a + up), V3(0f, 0f, -1f))))
        }
        return Shapes.merge(listOf(slab(-1f), slab(1f)))
    }

    /** A pond ripple: a 1.2 mm wide annulus of radius 1 (the entity scales it), lying flat. */
    fun ripple(): MeshData {
        val b = MeshBuilder()
        val seg = 48
        for (k in 0..seg) {
            val a = TAU * k / seg
            b.v(V3(0.88f * sin(a), 0f, 0.88f * cos(a)), V3.UP); b.v(V3(sin(a), 0f, cos(a)), V3.UP)
        }
        for (k in 0 until seg) { val i = 2 * k; b.quad(i, i + 2, i + 3, i + 1) }
        return Shapes.fixWinding(b.build())
    }

    /** Ring collider boxes: [Layout.RING_HIT_SEGMENTS] boxes around the band (centre, yaw), band-local. */
    fun ringHitBoxes(): List<Pair<V3, Float>> {
        val n = Layout.RING_HIT_SEGMENTS
        val rm = (Layout.RING_HIT_IN + Layout.RING_HIT_OUT) / 2
        val yc = (Layout.RING_HIT_Y0 + Layout.RING_HIT_Y1) / 2 - Layout.RING_Y
        return (0 until n).map { k -> val a = TAU * k / n; V3(rm * sin(a), yc, rm * cos(a)) to a }
    }

    /** Size of one ring collider box: radial depth 8.5 cm, height 5.5 cm, tangential length covering 1/n of the circle. */
    fun ringHitBoxSize(): V3 = V3(2f * Layout.RING_HIT_OUT * sin(PI.toFloat() / Layout.RING_HIT_SEGMENTS) + 0.004f, Layout.RING_HIT_Y1 - Layout.RING_HIT_Y0, Layout.RING_HIT_OUT - Layout.RING_HIT_IN)
}
