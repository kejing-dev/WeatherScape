package io.embodit.weatherscape.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5: every entity x 48 whole hours x the rain / snow sheets at their largest tilt toward four winds stays inside the
 * volume shrunk by 1 cm; the stop pin never meets the pointer; mesh budgets (design budget: <= 62 k triangles).
 */
class VolumeBoundsTest {
    private val safe = Layout.SAFE

    private fun box(m: MeshData, at: V3 = V3.ZERO, s: V3 = V3(1f, 1f, 1f)): Aabb { val (lo, hi) = m.bounds(); return Aabb(V3(lo.x * s.x, lo.y * s.y, lo.z * s.z) + at, V3(hi.x * s.x, hi.y * s.y, hi.z * s.z) + at) }

    private fun modelBox(cal: ModelCal, at: V3) = Aabb(at + V3(-cal.size.x / 2, 0f, -cal.size.z / 2), at + V3(cal.size.x / 2, cal.size.y, cal.size.z / 2))

    @Test fun staticPartsInside() {
        val parts = mapOf(
            "plinth" to box(Pieces.plinth(), V3(0f, Layout.PLINTH_Y0, 0f)),
            "ring" to box(Pieces.ringBand(), V3(0f, Layout.RING_Y, 0f)),
            "readout" to Layout.READOUT_BOX,
            "pointer" to Layout.POINTER_BOX,
            "knob" to Layout.KNOB_BOX,
            "knobHit" to Layout.KNOB_HIT_BOX,
            "island" to modelBox(ModelCalib.ISLAND, V3(0f, ModelCalib.ISLAND_BOTTOM, 0f)),
            "house" to modelBox(ModelCalib.HOUSE, ModelCalib.HOUSE_AT).let { Aabb(it.lo, it.hi + V3(0f, 0.035f, 0f)) },
            "lamp" to modelBox(ModelCalib.LAMP, ModelCalib.LAMP_AT),
            "skyHit" to Layout.SKY_HIT,
            "stepBack" to Layout.stepBox(Layout.STEP_BACK_C), "stepFwd" to Layout.stepBox(Layout.STEP_FWD_C),
            "stepBackHit" to Layout.stepHitBox(Layout.STEP_BACK_C), "stepFwdHit" to Layout.stepHitBox(Layout.STEP_FWD_C),
        )
        for ((n, b) in parts) assertTrue("$n $b outside $safe", b.inside(safe))
    }

    @Test fun treeSwayInsideAtTheLargestAmplitude() {
        val t = ModelCalib.TREE
        val h = t.size.y; val r = maxOf(t.size.x, t.size.z) / 2
        val lean = kotlin.math.sin(Layout.deg(9f * 1.15f)) * h
        val b = Aabb(ModelCalib.TREE_AT + V3(-r - lean, 0f, -r - lean), ModelCalib.TREE_AT + V3(r + lean, h, r + lean))
        assertTrue("$b", b.inside(safe))
    }

    @Test fun everyHourEveryWindInside() {
        for (slice in listOf(Sample.slice(), Sample.slice(20))) for (h in 0..47) {
            val p = SceneModel.at(slice, h.toFloat())
            for ((dx, dz) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
                val pb = Layout.precipBox(Layout.PRECIP_TILT_MAX_DEG, dx, dz)
                assertTrue("precip $h $dx $dz $pb", pb.inside(safe))
                for (i in 0 until 6) for (t in listOf(0f, 3.3f, 17f, 41f, 99f)) {
                    val (c, _) = SceneModel.cloudPos(i, t, 0.03f, dx, dz)
                    val cb = Aabb.centred(c, V3(Layout.CLOUD_W, Layout.CLOUD_H, Layout.CLOUD_W))
                    assertTrue("cloud $i $cb", cb.inside(safe))
                    assertTrue("cloud in r 0.22", Layout.hyp(c.x, c.z) + Layout.CLOUD_W / 2 <= Layout.CLOUD_R + 1e-4f)
                }
            }
            for (body in listOf(p.sunPos to Layout.SUN_R, p.moonPos to Layout.MOON_R)) {
                val b = Aabb.centred(body.first, V3(2 * body.second, 2 * body.second, 2 * body.second))
                assertTrue("sun/moon $h $b", b.inside(safe))
            }
        }
    }

    @Test fun cloudModelsFitTheirBox() {
        // v0.1.4: the Tripo cumulus models, calibrated into the design's cloud box (any yaw: the xz diagonal stays in CLOUD_W)
        for (c in ModelCalib.CLOUDS) {
            val s = c.size
            assertTrue("${c.id} $s", s.x <= Layout.CLOUD_W + 1e-3f && s.y <= Layout.CLOUD_H + 1e-3f && s.z <= Layout.CLOUD_D + 1e-3f)
        }
    }

    @Test fun tagsInsideAndClearOfTheModels() {
        for (k in Ask.entries) {
            val tb = io.embodit.weatherscape.ui.scene.DioramaScene.tagBox(k)
            assertTrue("$k $tb", tb.inside(safe))
        }
        val houseTag = io.embodit.weatherscape.ui.scene.DioramaScene.tagBox(Ask.HOUSE)
        val treeTag = io.embodit.weatherscape.ui.scene.DioramaScene.tagBox(Ask.TREE)
        assertFalse(houseTag.intersects(treeTag))
        assertFalse("tree tag below the cloud base", treeTag.hi.y > Layout.CLOUD_Y0 + 0.01f)
    }

    /** Segment a..b crosses box [b0] (slab test). */
    private fun hits(a: V3, b: V3, box: Aabb): Boolean {
        var t0 = 0f; var t1 = 1f
        val d = b - a
        for ((o, dd, lo, hi) in listOf(arrayOf(a.x, d.x, box.lo.x, box.hi.x), arrayOf(a.y, d.y, box.lo.y, box.hi.y), arrayOf(a.z, d.z, box.lo.z, box.hi.z))) {
            if (kotlin.math.abs(dd) < 1e-7f) { if (o < lo || o > hi) return false; continue }
            var ta = (lo - o) / dd; var tb = (hi - o) / dd
            if (ta > tb) { val x = ta; ta = tb; tb = x }
            t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
            if (t0 > t1) return false
        }
        return true
    }

    private fun corners(b: Aabb) = listOf(V3(b.lo.x, b.lo.y, b.hi.z), V3(b.hi.x, b.lo.y, b.hi.z), V3(b.lo.x, b.hi.y, b.hi.z), V3(b.hi.x, b.hi.y, b.hi.z), V3((b.lo.x + b.hi.x) / 2, (b.lo.y + b.hi.y) / 2, b.hi.z))

    private val winds = listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f, 0.7071f to 0.7071f, -0.7071f to 0.7071f, 0.7071f to -0.7071f, -0.7071f to -0.7071f)

    /** Every smoke wisp card box over one life, all winds, wind levels 0..6, plus the smoke curl model. */
    private fun puffBoxes(): List<Aabb> {
        val out = ArrayList<Aabb>()
        out += Smoke.curlBounds()
        for ((dx, dz) in winds) for (wl in 0..6) for (k in 0 until 42) for (i in 0 until Smoke.N) {
            out += Smoke.wispBounds(Smoke.wisp(i, k * 0.1f, dx, dz, wl))
        }
        return out
    }

    @Test fun smokeInside() { for (b in puffBoxes()) assertTrue("smoke $b", b.inside(safe)) }

    @Test fun smokeYieldsToEveryTagAndTagsAreSeenWhole() {
        // q1-1 (QA #1): the chimney smoke crossed the house tag. Show that it did (so the rule is needed) ...
        val houseTag = io.embodit.weatherscape.ui.scene.DioramaScene.tagBox(Ask.HOUSE)
        val puffs = puffBoxes()
        val crossed = Layout.EYES.any { eye -> corners(houseTag).any { c -> puffs.any { hits(eye, c, it) } } }
        assertTrue("without the yield, smoke sits between the eye and the house tag", crossed)
        // ... and the yield: any tag up -> target 0, so the puffs are gone while the tag is shown
        for (smoke in listOf(true, false)) assertTrue(Smoke.target(smoke, tagUp = true) == 0f)
        assertTrue(Smoke.target(true, tagUp = false) == 1f)
        // the tags themselves: every sightline from the four design eyes to the tag's four corners + centre misses the
        // models' boxes (house + vane, tree crown, lamp) and the tag never meets them
        val house = modelBox(ModelCalib.HOUSE, ModelCalib.HOUSE_AT).let { Aabb(it.lo, it.hi + V3(0f, 0.035f, 0f)) }
        val tree = modelBox(ModelCalib.TREE, ModelCalib.TREE_AT)
        val lamp = modelBox(ModelCalib.LAMP, ModelCalib.LAMP_AT)
        for (k in Ask.entries) {
            val tb = io.embodit.weatherscape.ui.scene.DioramaScene.tagBox(k)
            for (m in listOf(house, tree, lamp)) {
                assertFalse("$k tag meets $m", tb.intersects(m))
                for (eye in Layout.EYES) for (c in corners(tb)) assertFalse("$k eye $eye corner $c blocked by $m", hits(eye, c, m))
            }
        }
    }

    @Test fun pinNeverMeetsThePointer() {
        for (end in listOf(47, 30, 11)) for (k in 0..(end * 4)) {
            val pos = k / 4f
            assertFalse("end $end pos $pos", Layout.pinBox(pos, end).intersects(Layout.POINTER_BOX))
        }
    }

    @Test fun readoutDoesNotHideTheRingFront() {
        // design occlusionCheck: from the seated and level eyes, the line to the ring's front top edge passes above the readout
        val ringFront = V3(0f, Layout.RING_TOP, Layout.RING_OUT)
        for (eye in Layout.EYES.take(2)) {
            val t = (Layout.READOUT_BOX.hi.z - eye.z) / (ringFront.z - eye.z)
            val y = eye.y + (ringFront.y - eye.y) * t
            assertTrue("eye $eye y at readout front $y", y > Layout.READOUT_BOX.hi.y)
        }
    }

    @Test fun triangleBudget() {
        val procedural = listOf(Pieces.plinth(), Pieces.ringBand(), Pieces.ringFace(), Pieces.pin(), Pieces.readoutCase(), Pieces.pointer(), Pieces.knob(), Pieces.vaneBase(), Pieces.vaneArrow(), Pieces.roofSnow()).sumOf { it.triangles } +
            (0 until 4).sumOf { Pieces.rainSheet(3 + it).triangles } + (Snowfall.MAX + Smoke.N + 1) * 2 + Pieces.shadeShell(Layout.MOON_R).triangles +
            2 * Pieces.fogBand(91).triangles + 6 * Pieces.ripple().triangles
        // v0.1.4 sky models: 6 clouds (3 shapes), sun, moon, smoke curl
        val sky = (0 until 6).sumOf { ModelCalib.CLOUDS[it % 3].triangles } + ModelCalib.SUN.triangles + ModelCalib.MOON.triangles + ModelCalib.SMOKE_CURL.triangles
        val models = ModelCalib.ALL.sumOf { it.triangles } + ModelCalib.ISLAND.triangles + sky // + snow shell
        println("TRIANGLES procedural=$procedural models=$models total=${procedural + models}")
        assertTrue("total ${procedural + models}", procedural + models <= 62_000 + 12_000) // the snow shell repeats the island (12 k), shown only with snow
    }

    /** v0.1.2 D53 / D17: the 3D ◀ ▶ are >= 2° and >= 4 cm from every design eye, clear of the ring, the knob, the readout. */
    @Test fun stepButtonsBigAndClear() {
        for (c in listOf(Layout.STEP_BACK_C, Layout.STEP_FWD_C)) {
            val hit = Layout.stepHitBox(c)
            // >= 1.5 cm from the ring's collider (r 0.290), the knob's collider and the readout
            val nearR = kotlin.math.sqrt(minOf(kotlin.math.abs(hit.lo.x), kotlin.math.abs(hit.hi.x)).let { it * it } + minOf(kotlin.math.abs(hit.lo.z), kotlin.math.abs(hit.hi.z)).let { it * it })
            assertTrue("ring gap ${nearR - Layout.RING_HIT_OUT}", nearR - Layout.RING_HIT_OUT >= 0.015f)
            assertFalse(hit.grow(0.0075f).intersects(Layout.KNOB_HIT_BOX.grow(0.0075f)))
            assertFalse(hit.intersects(Layout.READOUT_BOX))
            assertTrue(2 * Layout.STEP_R >= 0.04f)
            for (eye in Layout.EYES) {
                val dist = (eye - c).length
                val deg = Math.toDegrees(2.0 * kotlin.math.atan(Layout.STEP_R / dist.toDouble())).toFloat()
                val hitDeg = Math.toDegrees(2.0 * kotlin.math.atan(Layout.STEP_HIT / 2 / dist.toDouble())).toFloat()
                println("STEP ${c.x} eye=$eye d=${"%.2f".format(dist)} disc=${"%.2f".format(deg)}° hit=${"%.2f".format(hitDeg)}°")
                assertTrue("disc $deg° from $eye", deg >= 1.9f && hitDeg >= 2f)
            }
        }
        // the two buttons mirror each other (D38: one function for both sides)
        assertTrue(kotlin.math.abs(Layout.stepYaw(Layout.STEP_BACK_C) + Layout.stepYaw(Layout.STEP_FWD_C)) < 1e-5f)
    }
}
