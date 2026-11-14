package io.embodit.weatherscape.ui.scene

import io.embodit.weatherscape.domain.Ask
import io.embodit.weatherscape.domain.Settings
import io.embodit.weatherscape.ui.scene.input.parseLoggedPath
import io.embodit.weatherscape.ui.scene.input.resolvePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** build.md 命中回放: the real node chains a ray lands on (mesh node first, then its parents) resolve to the five targets. */
class HitReplayTest {
    private fun target(chain: String) = resolvePath(parseLoggedPath(chain)) { VolumeTargets.targetOf(it) }

    @Test fun everyHolderResolves() {
        assertEquals(Names.RING, target("ringBand < ring < root"))
        assertEquals(Names.RING, target("ringFace < ring < root"))
        assertEquals(Names.RING, target("pin < ring < root"))
        assertEquals(Names.KNOB, target("knobBody < knob < root"))
        assertEquals(Names.KNOB, target("knobLabel < knob < root"))
        assertEquals(Names.SKY, target("cloud3 < sky < root"))
        assertEquals(Names.TREE, target("tree < cal:tree < tree < root")) // GLB node is named after the model id
        assertEquals(Names.HOUSE, target("house < cal:house < house < root"))
        assertEquals(Names.HOUSE, target("window1 < house < root"))
        assertEquals(Names.HOUSE, target("vaneArrow < house < root"))
        // v0.1.2 3D ◀ ▶: the disc, the face and the pushed-in body all resolve to their button
        assertEquals(Names.STEP_BACK, target("stepBackDisc < stepBackBody < stepBack < root"))
        assertEquals(Names.STEP_BACK, target("stepBackFace < stepBackBody < stepBack < root"))
        assertEquals(Names.STEP_FWD, target("stepFwdDisc < stepFwdBody < stepFwd < root"))
        assertEquals(-1, Names.stepDir(Names.STEP_BACK)); assertEquals(1, Names.stepDir(Names.STEP_FWD)); assertNull(Names.stepDir(Names.RING))
    }

    /** A tap on the 3D ▶ / ◀ through routeFlow (the listener's path) moves one hour; greyed at the ends. */
    @Test fun stepTapsMoveOneHour() {
        val disp = kotlinx.coroutines.test.StandardTestDispatcher()
        val scope = kotlinx.coroutines.test.TestScope(disp)
        var up = 1000L
        val src = object : io.embodit.weatherscape.domain.WeatherSource {
            override suspend fun forecast(c: io.embodit.weatherscape.domain.City): String = error("offline")
            override suspend fun search(q: String, zh: Boolean) = "{}"
            override suspend fun names(id: Long, zh: Boolean) = "{}"
            override suspend fun currents(cs: List<io.embodit.weatherscape.domain.City>) = "[]"
        }
        val store = object : io.embodit.weatherscape.domain.WeatherStore {
            override fun loadForecast(key: String): Pair<String, Long>? = null
            override fun saveForecast(key: String, json: String, fetchedAtMs: Long) {}
            override fun loadCities(): Pair<List<io.embodit.weatherscape.domain.City>, Long?> = emptyList<io.embodit.weatherscape.domain.City>() to null
            override fun saveCities(cities: List<io.embodit.weatherscape.domain.City>, currentId: Long?) {}
        }
        var settings = Settings(seenHowTo = true)
        val flow = io.embodit.weatherscape.domain.WeatherFlow(scope, disp, { 0L }, { up }, src, store, { settings }, { settings = it }, { true }, { "Asia/Shanghai" }, { false }, {}, {})
        flow.debugSample(); flow.sceneReady(); flow.welcomeDone()
        fun frames(ms: Long) { val end = up + ms; while (up < end) { up += 14; flow.step() } }
        routeFlow(flow, Names.STEP_BACK); frames(400)
        assertEquals(0, flow.value.shown)
        routeFlow(flow, Names.STEP_FWD); frames(800)
        assertEquals(1, flow.value.shown)
        routeFlow(flow, Names.STEP_FWD); frames(800)
        assertEquals(2, flow.value.shown)
        routeFlow(flow, Names.STEP_BACK); frames(800)
        assertEquals(1, flow.value.shown)
    }

    @Test fun displayOnlyThingsResolveToNothing() {
        assertNull(target("readoutFace < readout < root")) // R2-1: the readout takes no hits
        assertNull(target("plinth < root"))
        assertNull(target("island < cal:island < island < root"))
        assertNull(target("lamp < cal:lamp < lamp < root"))
    }

    @Test fun gains() {
        assertEquals(2.5f, gainOf(io.embodit.weatherscape.ui.scene.input.InputSource.EYE_HAND))
        assertEquals(1.0f, gainOf(io.embodit.weatherscape.ui.scene.input.InputSource.CONTROLLER))
        assertEquals(1.0f, gainOf(io.embodit.weatherscape.ui.scene.input.InputSource.HAND_RAY))
    }

    @Suppress("unused") private val keep = listOf(Ask.SKY, Settings())
}
