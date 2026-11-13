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
