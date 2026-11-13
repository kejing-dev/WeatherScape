package io.embodit.weatherscape.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3 (sample day qaCombos read straight from the scene model) and M9 (no point lights: glows are emissive only). */
class SceneModelTest {
    private val s = Sample.slice()
    private fun at(h: Int, night: Boolean? = null) = SceneModel.at(s, h.toFloat(), night)

    @Test fun sampleDayCombos() {
        at(0).let { assertEquals(Sky.CLEAR, it.sky); assertFalse(it.isDay); assertTrue(it.moonUp); assertFalse(it.sunUp); assertEquals(0, it.clouds) }
        at(8).let { assertEquals(Sky.PARTLY, it.sky); assertTrue(it.isDay); assertTrue(it.sunUp); assertEquals(2, it.clouds) }
        at(7).let { assertEquals(Sky.PARTLY, it.sky); assertTrue("sunrise 7:10: still twilight at 7:00", it.day < 0.5f) }
        at(11).let { assertEquals(Sky.OVERCAST, it.sky); assertTrue(it.clouds >= 4); assertFalse(it.moonUp) }
        at(15).let { assertEquals(Precip.RAIN, it.precip); assertEquals(2, it.sheets); assertEquals("小雨", Texts.sky(s.at(15), true)) }
        at(16).let { assertEquals(Precip.RAIN, it.precip); assertEquals(4, it.sheets); assertEquals("大雨", Texts.sky(s.at(16), true)) }
        at(19).let { assertEquals(Sky.STORM, it.sky); assertTrue(it.storm); assertFalse(it.isDay); assertEquals(3, it.cloudShade) }
        at(23).let { assertEquals(Sky.FOG, it.sky); assertTrue(it.fog); assertFalse(it.isDay) }
        at(31).let { assertEquals(Precip.SNOW, it.precip); assertEquals(1f, it.snowDepth, 1e-4f) }
        at(34).let { assertEquals(Sky.CLEAR, it.sky); assertEquals(2, it.windLevel); assertEquals(6f, it.treeAmpDeg); assertEquals(0f, it.snowDepth, 1e-4f) }
        at(46).let { assertEquals(3, it.windLevel); assertEquals(9f, it.treeAmpDeg); assertFalse(it.isDay); assertEquals(12f, it.tiltDeg) }
    }

    @Test fun nightOverlays() {
        at(11, night = true).let { assertFalse(it.isDay); assertEquals(0f, it.day) }
        at(23, night = false).let { assertTrue(it.isDay); assertTrue(it.fog) }
        at(19, night = false).let { assertTrue(it.isDay); assertTrue(it.storm) }
        at(15, night = true).let { assertFalse(it.isDay); assertEquals(Precip.RAIN, it.precip) }
    }

    @Test fun brightnessDayOvercastNight() {
        assertEquals(1f, at(34).brightness, 0.05f)
        assertTrue(at(11).brightness in 0.75f..0.85f)
        assertEquals(0.45f, at(2).brightness, 0.02f)
    }

    @Test fun windAndVaneFollowTheCompass() {
        // the wind blows FROM the north (+Z): the vane points +Z (yaw 0), rain leans toward -Z (south)
        val north = Fx.slice { Fx.hour(it, code = 61, precip = 2f, wind = 30f).copy(windDir = 0) }
        val p = SceneModel.at(north, 0f)
        assertEquals(0f, p.vaneYaw, 1e-4f)
        assertEquals(-1f, p.downZ, 1e-4f)
        // from the east (-X): downwind +X
        val east = Fx.slice { Fx.hour(it, wind = 30f).copy(windDir = 90) }
        assertEquals(1f, SceneModel.at(east, 0f).downX, 1e-4f)
    }

    @Test fun sunRisesInTheEastAndSetsInTheWest() {
        val rise = SceneModel.at(s, 7.25f)
        val set = SceneModel.at(s, 17.5f)
        assertTrue(rise.sunPos.x < -0.2f)
        assertTrue(set.sunPos.x > 0.2f)
        val noon = SceneModel.at(s, 12.4f)
        assertTrue(noon.sunPos.y > 0.1f)
    }
}
