package io.embodit.weatherscape.domain

import io.embodit.weatherscape.ui.scene.DioramaScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.atan

/** v0.1.3: QA #3 q3-2 (rain / snow give way to tags), q3-3 (D54 size), q3-4 (D13 step word), K2 (minus sign). */
class Rework3Test {
    private fun deg(rad: Double) = Math.toDegrees(rad)

    /** q3-2: no rain streak passes in front of a tag, at any tilt / wind direction / slide offset (v0.1.4: snow, see SkyModelsTest). */
    @Test fun precipitationGivesWayToEveryTag() {
        for (kind in Ask.values()) {
            val box = DioramaScene.tagBox(kind)
            val zone = PrecipYield.tagZone(box)
            for (tilt in listOf(0f, 4f, 8f, 12f)) for (k in 0 until 8) {
                val a = Math.PI * 2 * k / 8
                val q = PrecipYield.tiltQ(tilt, kotlin.math.sin(a).toFloat(), kotlin.math.cos(a).toFloat())
                var kept = 0; var all = 0
                for (i in 0 until 4) {
                    val mesh = Pieces.rainSheet(3 + i, PrecipYield.rainSkip(q, box))
                    all += Pieces.rainSheet(3 + i).vertexCount; kept += mesh.vertexCount
                    for (s in 0 until 10) {
                        val off = Layout.RAIN_STEP * s / 10f
                        for (v in 0 until mesh.vertexCount) {
                            val p = V3(0f, Layout.PRECIP_Y0, 0f) + q.rotate(V3(mesh.pos[3 * v], mesh.pos[3 * v + 1] - off, mesh.pos[3 * v + 2]))
                            val inFront = p.x in zone.lo.x..zone.hi.x && p.y in zone.lo.y..zone.hi.y && p.z >= zone.lo.z
                            assertTrue("$kind tilt $tilt dir $k rain sheet $i vertex $p in front of the tag $zone", !inFront)
                        }
                    }
                }
                // it still rains around the tag: most drops stay
                assertTrue("$kind $tilt $k kept $kept / $all", kept >= all / 2)
            }
        }
    }

    /** q3-2: with no tag nothing is left out (the sheets are the v0.1.2 ones). */
    @Test fun noTagNoHole() {
        for (i in 0 until 4) assertEquals(Pieces.rainSheet(3 + i).vertexCount, Pieces.rainSheet(3 + i) { _, _, _ -> false }.vertexCount)
    }

    /** q3-3 (D54): from the design eye (1.75 m) the ring spans >= 25°, the volume is >= 0.78 m wide, the manifest agrees. */
    @Test fun dioramaIsBigEnough() {
        val ringDeg = deg(2 * atan(Layout.RING_OUT * Layout.SCALE / 1.75))
        val volDeg = deg(2 * atan(Layout.VOLUME_M[0] / 2 / 1.75))
        val keysW = (Layout.STEP_FWD_C.x + Layout.STEP_R) * Layout.SCALE
        val keysDeg = deg(2 * atan(keysW / (1.75 - Layout.STEP_FWD_C.z * Layout.SCALE)))
        println("D54 ring ${"%.1f".format(ringDeg)}° ring+keys ${"%.1f".format(keysDeg)}° volume ${"%.1f".format(volDeg)}° (width ${Layout.VOLUME_M[0]} m)")
        assertTrue("ring $ringDeg", ringDeg >= 25.0)
        assertTrue(Layout.VOLUME_M[0] >= 0.78f)
        // content (layout units) x SCALE still fits the manifest volume
        val w = Layout.VOLUME.hi - Layout.VOLUME.lo
        assertTrue(w.x * Layout.SCALE <= Layout.VOLUME_M[0] + 1e-4f && w.y * Layout.SCALE <= Layout.VOLUME_M[1] + 1e-4f && w.z * Layout.SCALE <= Layout.VOLUME_M[2] + 1e-4f)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:value=\"${Layout.VOLUME_M.joinToString("x")}\""))
        // longest side of the subject >= 70 % of the volume width (D54)
        assertTrue(2 * Layout.RING_OUT / w.x >= 0.7f)
    }

    /** q3-4 (D13): the word on the 3D ◀ ▶ is >= 0.5° tall from every design eye (zh glyph 0.88 em, en digit 0.72 em). */
    @Test fun stepWordIsReadable() {
        val pxM = 2 * Layout.STEP_FACE_R / Layout.STEP_TEX_PX * Layout.SCALE
        for (c in listOf(Layout.STEP_BACK_C, Layout.STEP_FWD_C)) for (eye in Layout.EYES) {
            val dist = (eye - c * Layout.SCALE).length.toDouble()
            val zh = deg(2 * atan(Layout.STEP_LABEL_MIN_PX * 0.88 * pxM / 2 / dist))
            val en = deg(2 * atan(Layout.STEP_LABEL_PX * 0.72 * pxM / 2 / dist))
            assertTrue("zh $zh° en $en° from $eye", zh >= 0.5 && en >= 0.5)
        }
    }

    /** K2: below zero reads with a real minus sign. */
    @Test fun realMinusSign() {
        assertEquals("−1°", Units.tempText(-1.2f, TempUnit.C))
        assertEquals("0°", Units.tempText(-0.3f, TempUnit.C))
        assertEquals("5°", Units.tempText(5f, TempUnit.C))
    }
}
