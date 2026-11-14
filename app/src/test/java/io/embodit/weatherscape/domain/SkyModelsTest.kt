package io.embodit.weatherscape.domain

import io.embodit.weatherscape.ui.scene.DioramaScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * v0.1.4 (gate C, user 11-14 on the headset: "太阳和月亮 云，烟都要有自己的模型…不然都是白模，太丑了，❄️也是").
 * Sun / moon / 3 clouds / smoke curl are Tripo models (B12, primitive-hero), calibrated in one place (D25); snow is
 * six-armed crystal cards; smoke wisps rise, grow and fade; the moon shows its phase.
 */
class SkyModelsTest {
    private val assets = File("src/main/assets/models")

    @Test fun everySkyModelShipsCalibratedAndOpaque() {
        for (c in ModelCalib.SKY) {
            val f = File(assets, "${c.id}.glb")
            assertTrue("${c.id}.glb in assets", f.isFile)
            val json = String(f.readBytes(), Charsets.ISO_8859_1)
            assertTrue("${c.id} OPAQUE", json.contains("\"alphaMode\":\"OPAQUE\"") && !json.contains("\"BLEND\""))
            assertTrue("${c.id} decimated ${c.triangles}", c.triangles in 1000..4000)
            // D25: bottom centre to the origin (pivot), uniform scale
            assertEquals(c.scale, c.scaleY, 1e-6f)
            val bottom = (c.rawMin.y - c.pivot.y) * c.scale
            assertEquals(0f, bottom, 1e-4f)
            assertEquals(0f, ((c.rawMin.x + c.rawMax.x) / 2 - c.pivot.x) * c.scale, 0.01f)
        }
        // sizes against the design: sun 3.6 cm with rays (inside its 4 cm halo), moon 2.6 cm, clouds in the cloud box
        assertEquals(Layout.SUN_MODEL, maxOf(ModelCalib.SUN.size.x, ModelCalib.SUN.size.y), 0.0036f)
        assertTrue("sun disc faces the user (thin along z)", ModelCalib.SUN.size.z < 0.6f * ModelCalib.SUN.size.x)
        assertEquals(2 * Layout.MOON_R, ModelCalib.MOON.size.x, 0.0026f)
        assertTrue(ModelCalib.SUN.size.x / 2 <= Layout.SUN_R)
        // sun, moon and clouds emit their own texture (self-lit, B24; WhiteLook colours the clouds per sky)
        for ((c, lit) in listOf(ModelCalib.SUN to true, ModelCalib.MOON to true, ModelCalib.CLOUD_A to true)) {
            val json = String(File(assets, "${c.id}.glb").readBytes(), Charsets.ISO_8859_1)
            assertEquals(c.id, lit, json.contains("emissiveTexture"))
        }
    }

    @Test fun moonPhaseFollowsTheCalendar() {
        // known phases (UTC): new 2024-01-11 11:57, full 2024-01-25 17:54, first quarter 2026-11-17 ~, full 2026-11-24 ~
        val newMoon = MoonPhase.age(MoonPhase.epochDays("2024-01-11T12:00")!!)
        assertTrue("new $newMoon", newMoon < 0.6 || newMoon > MoonPhase.SYNODIC - 0.6)
        val full = MoonPhase.age(MoonPhase.epochDays("2024-01-25T18:00")!!)
        assertEquals(14.77, full, 0.7)
        assertTrue(MoonPhase.illumination(full) > 0.97)
        assertTrue(MoonPhase.illumination(newMoon) < 0.03)
        // waxing lights the right side, waning the left; full faces the user, new faces away
        assertTrue(MoonPhase.sunDir(7.4).x > 0.9f)
        assertTrue(MoonPhase.sunDir(22.1).x < -0.9f)
        assertTrue(MoonPhase.sunDir(14.77).z > 0.99f)
        // the shade shell's pole (+Z) points away from the sun
        for (age in listOf(1.0, 7.4, 14.0, 22.1, 28.0)) {
            val pole = MoonPhase.shadeTurn(age).rotate(V3(0f, 0f, 1f))
            assertTrue("age $age", pole.dot(MoonPhase.sunDir(age)) < -0.99f)
        }
        assertEquals(null, MoonPhase.epochDays("bad"))
    }

    @Test fun snowCrystalsFaceTheUserFallAndStayInside() {
        assertEquals(0, Snowfall.count(0)); assertEquals(9, Snowfall.count(1)); assertEquals(18, Snowfall.count(2)); assertEquals(36, Snowfall.count(4))
        val winds = listOf(0f to 1f, 1f to 0f, 0f to -1f, -1f to 0f)
        for ((dx, dz) in winds) {
            val q = PrecipYield.tiltQ(Layout.PRECIP_TILT_MAX_DEG, dx, dz)
            for (i in 0 until Snowfall.MAX) {
                var lastY = Float.MAX_VALUE; var falls = 0
                for (k in 0 until 200) {
                    val p = Snowfall.pose(i, k * 0.05f)
                    val w = Snowfall.world(p, q)
                    val half = p.size * 0.71f
                    assertTrue("flake $i $w", Aabb.centred(w, V3(2 * half, 2 * half, 2 * half)).inside(Layout.SAFE))
                    assertTrue(p.size <= Snowfall.SIZE_MAX)
                    if (p.local.y < lastY) falls++
                    lastY = p.local.y
                }
                assertTrue("flake $i falls", falls > 150)
            }
        }
        // full size flakes are >= 1 cm (x 1.42 in the room): ~0.47° from 1.75 m, the six arms read
        assertTrue(Snowfall.SIZE_MIN * Layout.SCALE >= 0.014f)
        // they turn slowly (< 1 rad/s) so the crystal stays readable
        assertTrue(Snowfall.flakes.all { abs(it.spin) in 0.2f..0.8f })
    }

    @Test fun snowGivesWayToEveryTag() {
        for (kind in Ask.values()) {
            val box = DioramaScene.tagBox(kind)
            val zone = PrecipYield.tagZone(box)
            for (tilt in listOf(0f, 12f)) for ((dx, dz) in listOf(0f to 1f, 1f to 0f, -0.7071f to -0.7071f)) {
                val q = PrecipYield.tiltQ(tilt, dx, dz)
                var shown = 0
                for (k in 0 until 300) for (i in 0 until Snowfall.MAX) {
                    val p = Snowfall.pose(i, k * 0.04f)
                    val w = Snowfall.world(p, q)
                    if (Snowfall.yields(w, box)) continue
                    shown++
                    // a shown flake's card (half diagonal) never reaches the tag zone in front
                    val r = p.size * 0.71f
                    val inFront = w.x + r >= zone.lo.x && w.x - r <= zone.hi.x && w.y + r >= zone.lo.y && w.y - r <= zone.hi.y && w.z >= zone.lo.z
                    assertTrue("$kind flake $i at $w in front of $zone", !inFront)
                }
                assertTrue("$kind it still snows around the tag ($shown)", shown > 300 * Snowfall.MAX / 2)
            }
        }
    }

    @Test fun smokeWispsRiseGrowAndFade() {
        for (i in 0 until Smoke.N) {
            val a = Smoke.wisp(i, 0.6f - i * Smoke.LIFE / Smoke.N + Smoke.LIFE, 1f, 0f, 1)
            val b = Smoke.wisp(i, 3.6f - i * Smoke.LIFE / Smoke.N + Smoke.LIFE, 1f, 0f, 1)
            assertTrue("rises", b.c.y > a.c.y + 0.04f)
            assertTrue("grows", b.size > a.size * 1.8f)
            assertTrue("fades", b.alpha < a.alpha * 0.4f)
            assertTrue("drifts downwind", b.c.x > a.c.x)
        }
        // born invisible (no pop at the chimney), never fully opaque
        val born = Smoke.wisp(0, 0f, 1f, 0f, 1)
        assertEquals(0f, born.alpha, 1e-4f)
        for (k in 0 until 100) for (i in 0 until Smoke.N) assertTrue(Smoke.wisp(i, k * 0.05f, 0f, 1f, 3).alpha <= Smoke.ALPHA)
    }

    @Test fun cardsAndGreyCloudsStayLight() {
        // B24: the unlit crystal / wisp cards never go darker than light grey, night or day
        for (b in listOf(0f, 0.38f, 0.45f, 0.68f, 1f)) assertTrue(WhiteLook.cardK(b) >= 0.6f)
        // overcast / rain / storm clouds are greyer than fair ones (user 11-14) but still light (ControlsTest)
        for (shade in 1..3) assertTrue(WhiteLook.lum(WhiteLook.cloud(shade, 1f).base) < WhiteLook.lum(WhiteLook.cloud(shade - 1, 1f).base))
    }
}
