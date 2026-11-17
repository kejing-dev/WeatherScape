package io.embodit.weatherscape.domain

import io.embodit.weatherscape.ui.scene.DioramaScene
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.6 (QA #6 q6-1: the new Tripo cumulus hid the tree tag in 4 of 5 shots). While a tag is up, every cloud the scene
 * keeps (yield target 1) neither meets the tag nor sits on any sightline from the user eyes (design eyes + 0–40° above,
 * 30° left / right, 1.2 / 1.75 m) to the tag's corners and centre; all hours of two days, eight winds, a minute of drift.
 */
class CloudYieldTest {
    private val winds = listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f, 0.7071f to 0.7071f, -0.7071f to 0.7071f, 0.7071f to -0.7071f, -0.7071f to -0.7071f)

    @Test fun keptCloudsNeverHideOrTouchATag() {
        var yielded = 0; var kept = 0; var neededForTree = false
        val per = HashMap<Ask, IntArray>()
        for (k in Ask.entries) {
            val pk = per.getOrPut(k) { IntArray(2) }
            val tag = DioramaScene.tagBox(k)
            for ((dx, dz) in winds) for (drift in listOf(0.005f, 0.013f, 0.045f)) for (step in 0 until 60) for (i in 0 until 6) {
                val (c, _) = SceneModel.cloudPos(i, step * 1f, drift, dx, dz)
                for (drop in listOf(0f, -0.008f)) {
                    val at = c + V3(0f, drop, 0f)
                    val box = CloudYield.bounds(at)
                    if (CloudYield.target(at, tag) == 0f) { yielded++; pk[1]++; if (k == Ask.TREE) neededForTree = true; continue }
                    kept++; pk[0]++
                    assertFalse("$k cloud $i at $at meets the tag", box.intersects(PrecipYield.tagZone(tag)))
                    for (eye in CloudYield.EYES) for (p in CloudYield.tagPoints(tag)) assertFalse("$k cloud $i at $at hides $p from $eye", CloudYield.segmentHits(eye, p, box))
                }
            }
        }
        println("CLOUD yield: kept $kept, yielded $yielded; per tag kept/yielded ${per.map { "${it.key} ${it.value[0]}/${it.value[1]}" }}")
        assertTrue("the rule is needed: some cloud did hide the tree tag", neededForTree)
        assertTrue("most clouds stay", kept > yielded)
    }

    /** QA #6's frame: overcast 11 h, the tree tag; no tag -> nothing yields. */
    @Test fun noTagNoYield() {
        for (i in 0 until 6) assertTrue(CloudYield.target(SceneModel.cloudPos(i, 3f, 0.01f, 1f, 0f).first, null) == 1f)
    }

    /** The emulator's default view (~20° from above, straight ahead) is in the eye set. */
    @Test fun eyeFanCoversTheEmulatorView() {
        val e = CloudYield.EYES
        assertTrue(e.any { kotlin.math.abs(it.x) < 1e-3f && kotlin.math.atan2(it.y, it.z) in Layout.deg(15f)..Layout.deg(25f) })
        assertTrue(e.size >= 10)
    }
}
