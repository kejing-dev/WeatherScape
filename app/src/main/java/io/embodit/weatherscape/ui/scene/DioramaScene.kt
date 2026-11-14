package io.embodit.weatherscape.ui.scene

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.weatherscape.domain.Aabb
import io.embodit.weatherscape.domain.Ask
import io.embodit.weatherscape.domain.Layout
import io.embodit.weatherscape.domain.MoonPhase
import io.embodit.weatherscape.domain.Snowfall
import io.embodit.weatherscape.domain.ModelCal
import io.embodit.weatherscape.domain.ModelCalib
import io.embodit.weatherscape.domain.Phase
import io.embodit.weatherscape.domain.Pieces
import io.embodit.weatherscape.domain.Precip
import io.embodit.weatherscape.domain.Q4
import io.embodit.weatherscape.domain.Ring
import io.embodit.weatherscape.domain.SceneModel
import io.embodit.weatherscape.domain.Smoke
import io.embodit.weatherscape.domain.PrecipYield
import io.embodit.weatherscape.domain.SceneParams
import io.embodit.weatherscape.domain.Slice
import io.embodit.weatherscape.domain.V3
import io.embodit.weatherscape.domain.WhiteLook
import io.embodit.weatherscape.platform.Graph
import io.embodit.weatherscape.platform.TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** The words the scene draws, already in the app language (resolved by VolumeScreen; D38: same source as the window). */
internal data class SceneTexts(val read1: String, val read2: String, val knob: String, val tag: String?, val zh: Boolean, val testF: Boolean = false, val step: String = "1 h")

/** Every holder name the input layer knows (one place, design input.hitTargets: 5 targets). */
object Names {
    const val RING = "ring"
    const val KNOB = "knob"
    const val SKY = "sky"
    const val TREE = "tree"
    const val HOUSE = "house"
    /** v0.1.2 (D53): the 3D ◀ / ▶ buttons beside the ring. */
    const val STEP_BACK = "stepBack"
    const val STEP_FWD = "stepFwd"
    val ALL = setOf(RING, KNOB, SKY, TREE, HOUSE, STEP_BACK, STEP_FWD)
    fun stepDir(name: String?): Int? = when (name) { STEP_BACK -> -1; STEP_FWD -> 1; else -> null }
}

/**
 * Every entity of the diorama volume. All motion is placed in [onFrame], driven by DioramaFrameSystem (ECS, never a
 * Compose per-frame loop); the ring's position comes from WeatherFlow.step() (high-rate state never goes through Compose).
 *
 * ```
 * root (volume centre)
 * ├ plinth (walnut), readout (case + face + pointer), knob "knob" (collider 6.5 x 6.5 cm)
 * ├ ring "ring" (32 collider boxes, turns about Y) ── band, engraved face, stop pin
 * ├ island (Tripo, calibrated) + snow shell (same mesh, white), pond ripples x6
 * ├ house "house" (Tripo) ── windows glow, roof snow, vane base + arrow; smoke x6
 * ├ tree "tree" (Tripo, sways about its root), lamp (Tripo) + bulb
 * ├ stepBack "◀" / stepFwd "▶" (v0.1.2, D53: brass discs on the ring's front left / right, collider + HoverEffect)
 * ├ sky "sky" (one collider over the cloud layer) ── clouds x6;  fog bands x2, rain sheets x4, snow sheets x4
 * ├ sun, moon, key light (directional), tag (S05)
 * ```
 */
internal class DioramaScene(private val scope: CoroutineScope, private val graph: Graph) {
    val root: Entity = Entity().apply { setName("root"); at(V3.ZERO, null, Layout.SCALE) } // q3-3 (D54): the diorama x 1.42
    private val keep = ArrayList<Resource>()
    private fun <T : Resource> T.kept(): T { toGlobal(); synchronized(keep) { keep += this }; return this }

    private var basicBuilt = false
    private var modelsBuilt = false
    val ready: Boolean get() = basicBuilt && modelsBuilt

    @Volatile var texts = SceneTexts("", "", "", null, true)

    // static
    private var ring: Entity? = null
    private var ringBandMat: PhysicallyBasedMaterial? = null
    private var ringFaceMat: PhysicallyBasedMaterial? = null
    private var ringFaceTex: TextureResource? = null
    private var ringFaceKey: String? = null
    private var ringBusy = false
    private var pin: Entity? = null
    private var pinEnd = -1
    private var readMat: UnlitMaterial? = null
    private var readTex: TextureResource? = null
    private var readKey: String? = null
    private var readBusy = false
    private var knob: Entity? = null
    private var knobMat: PhysicallyBasedMaterial? = null
    private var knobLabelMat: UnlitMaterial? = null
    private var knobTex: TextureResource? = null
    private var knobKey: String? = null
    // ◀ / ▶ (v0.1.2, D53): holder, body material (greyed at the ends), face material + texture, last press time
    private class StepBtn(val name: String, val d: Int, val c: V3) {
        var holder: Entity? = null
        var body: Entity? = null
        var bodyMat: PhysicallyBasedMaterial? = null
        var faceMat: UnlitMaterial? = null
        var tex: TextureResource? = null
        var key: String? = null
        var busy = false
        @Volatile var pressedAt = -1L
        var push = 0f
        var on = true
        var lastBright = -1f
    }
    private val steps = listOf(StepBtn(Names.STEP_BACK, -1, Layout.STEP_BACK_C), StepBtn(Names.STEP_FWD, 1, Layout.STEP_FWD_C))
    private var snowCoverMat: PhysicallyBasedMaterial? = null
    // models
    private var house: Entity? = null
    private var tree: Entity? = null
    private var vaneArrow: Entity? = null
    private var roofSnow: Entity? = null
    private var snowIsland: Entity? = null
    private val windowMats = ArrayList<UnlitMaterial>()
    private var bulbMat: UnlitMaterial? = null
    // sky
    private var sky: Entity? = null
    private val clouds = ArrayList<Entity>()
    private val cloudScale = FloatArray(6)
    /** v0.1.4: the Tripo cloud models' own materials (texture kept), coloured by WhiteLook. */
    private val cloudMats = ArrayList<PhysicallyBasedMaterial>()
    private val flakes = ArrayList<Entity>()
    private val flakeK = FloatArray(Snowfall.MAX)
    private var crystalMat: UnlitMaterial? = null
    private val wispMats = ArrayList<UnlitMaterial>()
    private var smokeCurl: Entity? = null
    private val smokeMats = ArrayList<PhysicallyBasedMaterial>()
    private var sunModel: Entity? = null
    private val sunMats = ArrayList<PhysicallyBasedMaterial>()
    private var halo: Entity? = null
    private var haloMat: UnlitMaterial? = null
    private var moonShade: Entity? = null
    private var moonAge = -1.0
    private val fogBands = ArrayList<Entity>()
    private val rain = ArrayList<Entity>()
    private val smoke = ArrayList<Entity>()
    // q3-2: rain / snow sheets with the strands in front of the current tag left out (built when a tag goes up)
    private var rainMatRef: PhysicallyBasedMaterial? = null
    private val holeSheets = ArrayList<Entity>()
    private val holeMeshes = ArrayList<MeshResource>()
    private var holeKey: String? = null
    private var holeBusy = false
    private val ripples = ArrayList<Entity>()
    private var sun: Entity? = null
    private var moon: Entity? = null
    private var light: Entity? = null
    private var lightC: DirectionalLightComponent? = null
    private var tag: Entity? = null
    private var tagMat: UnlitMaterial? = null
    private var tagTex: TextureResource? = null
    private var tagShown: String? = null
    private var tagBusy = false
    // brightness: PBR materials with their base colours, GLB materials (texture x factor)
    private val dimmable = ArrayList<Pair<PhysicallyBasedMaterial, Int>>()
    private val glbMats = ArrayList<PhysicallyBasedMaterial>()
    private var lastBright = -1f
    private var lastCloudKey = -1
    // animated values
    private var glow = 0f
    private var snowCur = 0f
    private var smokeK = 0f
    private var fogMatRef: PhysicallyBasedMaterial? = null
    private var ampCur = 1f
    private var vaneYaw = 0f
    private var vaneVel = 0f
    private val t0 = SystemClock.uptimeMillis()
    @Volatile private var hover: String? = null
    @Volatile var lastParams: SceneParams? = null
        private set

    fun setHover(name: String?) {
        if (name == hover) return
        hover = name
        if (name != null) Log.i(TAG, "HOVER $name")
    }

    private fun clone(e: Entity): Entity = e.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))!!.also { it.enabled = true }

    private fun model(mesh: MeshResource, mat: Material, name: String, at: V3 = V3.ZERO, q: Q4? = null) = ModelEntity(mesh, mat).apply { setName(name); at(at, q) }

    private fun holder(e: Entity, shapes: List<ShapeResource>, physics: PhysicsMaterialResource) {
        e.components.set(CollisionComponent(collisionShape = shapes, physicsMaterial = physics))
        e.components.set(InteractableComponent())
        e.components.set(HoverEffectComponent())
    }

    /** Stage 1 (S00, < 1.5 s): plinth, ring, readout, knob, sky pieces (all procedural); the Tripo models follow in [installModels]. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun installBasics() {
        val tStart = SystemClock.elapsedRealtime()
        val io = Dispatchers.IO
        val d = coroutineScope {
            object {
                val walnutTex = async(io) { TextureResource.create(Tex.walnut()).kept() }
                val brassTex = async(io) { TextureResource.create(Tex.brass()).kept() }
                val plinth = async(io) { Kit.mesh(Pieces.plinth(), "plinth").kept() }
                val band = async(io) { Kit.mesh(Pieces.ringBand(), "ringBand").kept() }
                val face = async(io) { Kit.mesh(Pieces.ringFace(), "ringFace").kept() }
                val pin = async(io) { Kit.mesh(Pieces.pin(), "pin").kept() }
                val case = async(io) { Kit.mesh(Pieces.readoutCase(), "readoutCase").kept() }
                val readFace = async(io) { Kit.mesh(Pieces.quad(Layout.FACE_W, Layout.FACE_H), "readFace").kept() }
                val pointer = async(io) { Kit.mesh(Pieces.pointer(), "pointer").kept() }
                val knob = async(io) { Kit.mesh(Pieces.knob(), "knob").kept() }
                val knobLabel = async(io) { Kit.mesh(Pieces.quad(0.04f, 0.015f), "knobLabel").kept() }
                val stepBody = async(io) { Kit.mesh(Pieces.stepButton(), "stepBody").kept() }
                val stepFace = async(io) { Kit.mesh(Pieces.disc(Layout.STEP_FACE_R), "stepFace").kept() }
                val stepBox = async(io) { ShapeResource.createBox(Vector3(Layout.STEP_HIT, Layout.STEP_HIT, 0.03f)).kept() }
                val fog = async(io) { (0 until 2).map { Kit.mesh(Pieces.fogBand(91 + it * 7), "fog$it").kept() } }
                val rain = async(io) { (0 until 4).map { Kit.mesh(Pieces.rainSheet(3 + it), "rain$it").kept() } }
                // v0.1.4: texture cards (1 x 1 m, scaled per use) for the snow crystals, smoke wisps and the sun's halo
                val card = async(io) { Kit.mesh(Pieces.quad(1f, 1f), "textureCard").kept() }
                val shade = async(io) { Kit.mesh(Pieces.shadeShell(Layout.MOON_R * Layout.MOON_SHADE_K), "moonPhaseShell").kept() }
                val crystalTex = async(io) { TextureResource.create(Tex.crystal()).kept() }
                val wispTex = async(io) { (0 until 3).map { TextureResource.create(Tex.wisp(11 + it * 7)).kept() } }
                val haloTex = async(io) { TextureResource.create(Tex.halo()).kept() }
                val ripple = async(io) { Kit.mesh(Pieces.ripple(), "ripple").kept() }
                val tagQuad = async(io) { Kit.mesh(Pieces.quad(Layout.TAG_W, Layout.TAG_H), "tag").kept() }
                val physics = async(io) { PhysicsMaterialResource().kept() }
                // gate:allow geometry invisible ring colliders: 32 boxes around the band (design input.ringDrag), never drawn
                val ringBoxes = async(io) { Pieces.ringHitBoxes().map { (c, a) -> ShapeResource.createBox(Pieces.ringHitBoxSize().let { Vector3(it.x, it.y, it.z) }).offsetByTranslationAndRotation(com.pico.spatial.core.math.Quat(0f, sin(a / 2), 0f, kotlin.math.cos(a / 2)), Vector3(c.x, c.y, c.z)).kept() } } // gate:allow ring-from-boxes invisible collider boxes around the ring, the visible ring is a true lathe
                val knobBox = async(io) { ShapeResource.createBox(Vector3(Layout.KNOB_HIT, Layout.KNOB_HIT_H, 0.03f)).offsetByTranslation(Vector3(0f, 0.001f, 0.012f)).kept() }
                val skyBox = async(io) { Layout.SKY_HIT.let { b -> val s = b.hi - b.lo; val c = (b.hi + b.lo) * 0.5f; ShapeResource.createBox(Vector3(s.x, s.y, s.z)).offsetByTranslation(Vector3(c.x, c.y, c.z)).kept() } }
            }
        }
        // every resource was created concurrently (resource creation waits on the renderer); collect them
        val m = object {
            val walnutTex = d.walnutTex.getCompleted()
            val brassTex = d.brassTex.getCompleted()
            val plinth = d.plinth.getCompleted()
            val band = d.band.getCompleted()
            val face = d.face.getCompleted()
            val pin = d.pin.getCompleted()
            val case = d.case.getCompleted()
            val readFace = d.readFace.getCompleted()
            val pointer = d.pointer.getCompleted()
            val knob = d.knob.getCompleted()
            val knobLabel = d.knobLabel.getCompleted()
            val stepBody = d.stepBody.getCompleted()
            val stepFace = d.stepFace.getCompleted()
            val stepBox = d.stepBox.getCompleted()
            val fog = d.fog.getCompleted()
            val rain = d.rain.getCompleted()
            val card = d.card.getCompleted()
            val shade = d.shade.getCompleted()
            val crystalTex = d.crystalTex.getCompleted()
            val wispTex = d.wispTex.getCompleted()
            val haloTex = d.haloTex.getCompleted()
            val ripple = d.ripple.getCompleted()
            val tagQuad = d.tagQuad.getCompleted()
            val physics = d.physics.getCompleted()
            val ringBoxes = d.ringBoxes.getCompleted()
            val knobBox = d.knobBox.getCompleted()
            val skyBox = d.skyBox.getCompleted()
        }
        val walnut = Kit.pbr(0xFFFFFF, 0.55f).apply { setBaseColorTexture(m.walnutTex) }.kept()
        val brass = Kit.pbr(0xFFFFFF, 0.40f, 0.35f).apply { setBaseColorTexture(m.brassTex) }.kept()
        ringBandMat = Kit.pbr(0xFFFFFF, 0.40f, 0.35f).apply { setBaseColorTexture(m.brassTex) }.kept()
        ringFaceMat = Kit.pbr(0xFFFFFF, 0.45f, 0.30f).kept()
        val iron = Kit.pbr(0x2C2C2C, 0.45f, 0.6f).kept()
        dimmable += walnut to 0xFFFFFF; dimmable += brass to 0xFFFFFF; dimmable += ringBandMat!! to 0xFFFFFF; dimmable += ringFaceMat!! to 0xFFFFFF; dimmable += iron to 0x2C2C2C
        root.addChild(model(m.plinth, walnut, "plinth", V3(0f, Layout.PLINTH_Y0, 0f)))
        // ring
        val r = Entity().apply { setName(Names.RING); at(V3(0f, Layout.RING_Y, 0f)) }
        r.addChild(model(m.band, ringBandMat!!, "ringBand"))
        r.addChild(model(m.face, ringFaceMat!!, "ringFace"))
        pin = model(m.pin, iron, "pin").also { r.addChild(it) }
        holder(r, m.ringBoxes, m.physics)
        root.addChild(r); ring = r
        // readout (display only, no collider: R2-1), pointer, knob
        val tilt = Q4.axis(V3(1f, 0f, 0f), -Layout.deg(Layout.READOUT_TILT_DEG))
        val rd = Entity().apply { setName("readout"); at(Layout.READOUT_C, tilt) }
        rd.addChild(model(m.case, brass, "readoutCase"))
        readMat = Kit.unlit(0xFFFFFF).kept()
        rd.addChild(model(m.readFace, readMat!!, "readoutFace", V3(-0.0075f, 0f, Layout.READOUT_T / 2 + 0.0006f)))
        root.addChild(rd)
        root.addChild(model(m.pointer, brass, "pointer", Layout.POINTER_TIP))
        knobMat = Kit.pbr(0xFFFFFF, 0.40f, 0.35f).apply { setBaseColorTexture(m.brassTex) }.kept()
        dimmable += knobMat!! to 0xFFFFFF
        val k = Entity().apply { setName(Names.KNOB); at(Layout.KNOB_C - V3(0f, 0f, Layout.KNOB_T / 2)) }
        k.addChild(model(m.knob, knobMat!!, "knobBody"))
        knobLabelMat = Kit.unlit(0xFFFFFF).kept()
        k.addChild(model(m.knobLabel, knobLabelMat!!, "knobLabel", V3(0f, 0f, Layout.KNOB_T + 0.0008f)))
        holder(k, listOf(m.knobBox), m.physics)
        root.addChild(k); knob = k
        // ◀ / ▶ beside the ring (D53): brass disc + engraved face, collider + system HoverEffect on the holder
        for (b in steps) {
            val tiltQ = Q4.axis(V3.UP, Layout.stepYaw(b.c)) * Q4.axis(V3(1f, 0f, 0f), -Layout.deg(Layout.STEP_TILT_DEG))
            val hld = Entity().apply { setName(b.name); at(b.c, tiltQ) }
            val bodyMat = Kit.pbr(0xFFFFFF, 0.40f, 0.35f).apply { setBaseColorTexture(m.brassTex) }.kept()
            val faceMat = Kit.unlit(0xFFFFFF).kept()
            val body = Entity().apply { setName("${b.name}Body") }
            body.addChild(model(m.stepBody, bodyMat, "${b.name}Disc"))
            body.addChild(model(m.stepFace, faceMat, "${b.name}Face", V3(0f, 0f, Layout.STEP_T / 2 + 0.0008f)))
            hld.addChild(body)
            holder(hld, listOf(m.stepBox), m.physics)
            root.addChild(hld)
            b.holder = hld; b.body = body; b.bodyMat = bodyMat; b.faceMat = faceMat
        }
        // sky
        // v0.1.4: the clouds are Tripo cumulus models, added under the sky holder in installModels
        val s = Entity().apply { setName(Names.SKY) }
        holder(s, listOf(m.skyBox), m.physics)
        root.addChild(s); sky = s
        // fog (v0.1.1): two loose rings of soft pale wisps around the island rim, turning slowly in opposite directions
        val fogMat = Kit.pbr(WhiteLook.FOG, 1f).kept().also { fogMatRef = it }
        fogBands += model(m.fog[0], fogMat, "fog0", V3(0f, FOG_Y[0], 0f)).also { it.enabled = false; root.addChild(it) }
        fogBands += model(m.fog[1], fogMat, "fog1", V3(0f, FOG_Y[1], 0f), Q4.axis(V3.UP, 2.6f)).also { it.enabled = false; root.addChild(it) }
        val rainMat = Kit.pbr(0xB7C6D3, 0.4f, cull = false).kept().also { dimmable += it to 0xB7C6D3; rainMatRef = it }
        for (i in 0 until 4) rain += model(m.rain[i], rainMat, "rain$i").also { it.enabled = false; root.addChild(it) }
        // v0.1.4 (user 11-14 "❄️也是"): six-armed crystal cards facing the user, turning slowly as they fall (Snowfall);
        // unlit white x the painted crystal's alpha, dimmed with the scene but never below 0.6 (B24)
        crystalMat = Tex.cardMat(m.crystalTex, mode = com.pico.spatial.core.ecs.resource.BlendingMode.MASKED).kept() // cut-out, opaque pass (M8)
        for (i in 0 until Snowfall.MAX) flakes += model(m.card, crystalMat!!, "snowCrystal$i").also { it.enabled = false; root.addChild(it) } // gate:allow primitive-hero textured crystal card (painted six-armed snow crystal with alpha), the brief's "带 alpha 的结晶贴图片"
        // v0.1.4 (user 11-14 "烟都要有自己的模型"): smoke wisps = semi-transparent painted smoke cards rising from the Tripo
        // smoke curl on the chimney; each wisp has its own material for its fade
        for (i in 0 until Smoke.N) {
            val mat = Tex.cardMat(m.wispTex[i % 3], WhiteLook.SMOKE, 0f).kept()
            wispMats += mat
            smoke += model(m.card, mat, "smokeWisp$i").also { it.enabled = false; root.addChild(it) } // gate:allow primitive-hero textured smoke-wisp card (painted wisp with alpha) rising from the Tripo smoke model
        }
        val rippleMat = Kit.pbr(0x9DB2C2, 0.15f).kept().also { dimmable += it to 0x9DB2C2 }
        for (i in 0 until 6) ripples += model(m.ripple, rippleMat, "ripple$i").also { it.enabled = false; root.addChild(it) }
        // v0.1.4: sun / moon holders; the Tripo sun (glowing, rays) and the crater moon go in under them in installModels.
        // The sun has a soft halo card behind it; the moon a dark phase shell over its night side (MoonPhase).
        haloMat = Tex.cardMat(m.haloTex, 0xFFFFFF, 0.9f, com.pico.spatial.core.ecs.resource.BlendingMode.ADD).kept() // additive glow
        sun = Entity().apply { setName("sun"); enabled = false }.also { root.addChild(it) }
        halo = model(m.card, haloMat!!, "sunHalo", V3(0f, 0f, -0.006f)).also { it.scale(2 * Layout.SUN_R, 2 * Layout.SUN_R, 1f); sun!!.addChild(it) } // gate:allow primitive-hero the sun's soft halo, a painted glow texture behind the Tripo sun model
        moon = Entity().apply { setName("moon"); enabled = false }.also { root.addChild(it) }
        moonShade = model(m.shade, Kit.unlit(0x1A1F2A).kept(), "moonPhaseShell").also { moon!!.addChild(it) } // opaque night side (M8)
        tagMat = Kit.unlit(0xFFFFFF).kept()
        tag = model(m.tagQuad, tagMat!!, "tagPlate").also { it.enabled = false; root.addChild(it) }
        runCatching {
            val lc = DirectionalLightComponent(rgb4(0xFFF1DC), 0.8f, true)
            light = Entity().apply { setName("keyLight"); components.set(lc) }
            lightC = lc
            root.addChild(light!!)
            Log.i(TAG, "LIGHT directional light added in the volume (N3)")
        }.onFailure { Log.w(TAG, "LIGHT unavailable (N3): $it") }
        basicBuilt = true
        Log.i(TAG, "SCENE basics in ${SystemClock.elapsedRealtime() - tStart} ms")
    }

    /** [into]: where the clone's GLB materials go (the dimmed scenery by default; clouds / sun / smoke keep their own looks). */
    private fun calibrated(cal: ModelCal, tpl: Entity, into: MutableList<PhysicallyBasedMaterial> = glbMats): Entity {
        val node = Entity().apply { setName("cal:${cal.id}") }
        node.at(cal.offset)
        node.scale(cal.scale, cal.scaleY, cal.scale)
        val c = clone(tpl)
        node.addChild(c)
        collectGlbMats(c, into)
        return node
    }

    private fun collectGlbMats(e: Entity, into: MutableList<PhysicallyBasedMaterial> = glbMats) {
        e.components[ModelComponent::class.java]?.let { mc ->
            for (i in 0 until mc.materials.size) runCatching { (mc.materials.get(i) as? PhysicallyBasedMaterial)?.let { if (it !in into) into += it } }
        }
        e.getChildren().forEach { collectGlbMats(it, into) }
    }

    private fun setAllMaterials(e: Entity, mat: Material) {
        e.components[ModelComponent::class.java]?.let { mc -> for (i in 0 until mc.materials.size) mc.materials.set(i, mat) }
        e.getChildren().forEach { setAllMaterials(it, mat) }
    }

    /** Stage 2: the Tripo island, stone house, birch and lamp (calibrated, design assets), glows, vane, snow. */
    suspend fun installModels(load: suspend (String) -> Entity) {
        val tStart = SystemClock.elapsedRealtime()
        val island = load("island"); val islandSnow = load("island_snow"); val houseT = load("house"); val treeT = load("tree"); val lampT = load("lamp")
        // v0.1.4 (B12, user 11-14): the sky's own Tripo models
        val sunT = load("sun"); val moonT = load("moon"); val curlT = load("smoke_curl")
        val cloudT = ModelCalib.CLOUDS.map { load(it.id) }
        val io = Dispatchers.IO
        val m = withContext(io) {
            object {
                val physics = PhysicsMaterialResource().kept()
                val glowQuad = Kit.mesh(Pieces.quad(0.011f, 0.013f), "window").kept()
                val bulb = Kit.mesh(Pieces.sphere(0.0042f, 16, 10), "bulb").kept()
                val vaneBase = Kit.mesh(Pieces.vaneBase(), "vaneBase").kept()
                val vaneArrow = Kit.mesh(Pieces.vaneArrow(), "vaneArrow").kept()
                val roof = Kit.mesh(Pieces.roofSnow(), "roofSnow").kept()
                // gate:allow geometry invisible tap colliders for the house and the tree (design input.tapAsk)
                val houseBox = ShapeResource.createBox(Vector3(0.08f, 0.095f, 0.085f)).offsetByTranslation(Vector3(0f, 0.0475f, 0f)).kept()
                // gate:allow geometry invisible tap collider for the tree (crown + trunk)
                val treeBox = ShapeResource.createBox(Vector3(0.11f, 0.15f, 0.11f)).offsetByTranslation(Vector3(0f, 0.075f, 0f)).kept()
            }
        }
        root.addChild(Entity().apply { setName("island"); at(V3(0f, ModelCalib.ISLAND_BOTTOM, 0f)); addChild(calibrated(ModelCalib.ISLAND, island)) })
        // snow shell: the island mesh again, white, lifted 0-4 mm and pulled in 1.5 % so the rim stays grass
        val snowWhite = Kit.pbr(0xF4F6F8, 0.9f).kept().also { dimmable += it to 0xF4F6F8; snowCoverMat = it }
        snowIsland = Entity().apply { setName("snowIsland"); enabled = false }
        val shell = Entity().apply { at(ModelCalib.ISLAND.offset); scale(ModelCalib.ISLAND.scale * 0.985f, ModelCalib.ISLAND.scaleY, ModelCalib.ISLAND.scale * 0.985f) }
        val shellModel = clone(islandSnow); setAllMaterials(shellModel, snowWhite); shell.addChild(shellModel)
        snowIsland!!.addChild(shell)
        root.addChild(snowIsland!!)
        // house + its glows / roof snow / vane
        val h = Entity().apply { setName(Names.HOUSE); at(ModelCalib.HOUSE_AT) }
        h.addChild(calibrated(ModelCalib.HOUSE, houseT))
        holder(h, listOf(m.houseBox), m.physics)
        for ((i, p) in HOUSE_WINDOWS.withIndex()) {
            val mat = Kit.unlit(0x2A2620).kept()
            windowMats += mat
            h.addChild(model(m.glowQuad, mat, "window$i", p.first, p.second))
        }
        roofSnow = model(m.roof, snowWhite, "roofSnow").also { it.enabled = false; h.addChild(it) }
        val iron = Kit.pbr(0x1D1D1D, 0.5f, 0.6f).kept().also { dimmable += it to 0x1D1D1D }
        h.addChild(model(m.vaneBase, iron, "vaneBase", VANE_FOOT))
        vaneArrow = model(m.vaneArrow, iron, "vaneArrow", VANE_FOOT + V3(0f, 0.024f, 0f)).also { h.addChild(it) }
        root.addChild(h); house = h
        // tree (sways about its root)
        val t = Entity().apply { setName(Names.TREE); at(ModelCalib.TREE_AT) }
        t.addChild(calibrated(ModelCalib.TREE, treeT))
        holder(t, listOf(m.treeBox), m.physics)
        root.addChild(t); tree = t
        // lamp + bulb (emissive only, no point light: design lighting)
        val l = Entity().apply { setName("lamp"); at(ModelCalib.LAMP_AT) }
        l.addChild(calibrated(ModelCalib.LAMP, lampT))
        bulbMat = Kit.unlit(0x3A3630).kept()
        l.addChild(model(m.bulb, bulbMat!!, "bulb", V3(0f, ModelCalib.LAMP.size.y * 0.82f, 0f)))
        root.addChild(l)
        // v0.1.4 sky models: 6 clouds cycling the 3 cumulus shapes (each turned differently), the glowing sun with its rays
        // facing the user, the crater moon under its phase shell, the smoke curl on the chimney mouth
        val skyHolder = sky!!
        for (i in 0 until 6) {
            val cal = ModelCalib.CLOUDS[i % ModelCalib.CLOUDS.size]
            val e = Entity().apply { setName("cloud$i"); enabled = false }
            val turn = Entity().apply { at(V3.ZERO, Q4.axis(V3.UP, CLOUD_YAW[i])) }
            // the calibration puts the bottom centre on the origin; lift by half the height so the entity sits at its centre
            turn.addChild(calibrated(cal, cloudT[i % cloudT.size], cloudMats).also { it.at(cal.offset - V3(0f, cal.size.y / 2, 0f)) })
            e.addChild(turn)
            skyHolder.addChild(e)
            clouds += e
        }
        sunModel = calibrated(ModelCalib.SUN, sunT, sunMats).also { it.at(ModelCalib.SUN.offset - V3(0f, ModelCalib.SUN.size.y / 2, 0f)) }
        sun!!.addChild(Entity().apply { setName("sunSpin"); addChild(sunModel!!) }.also { sunModel = it })
        moon!!.addChild(calibrated(ModelCalib.MOON, moonT, ArrayList()).also { it.at(ModelCalib.MOON.offset - V3(0f, ModelCalib.MOON.size.y / 2, 0f)) })
        smokeCurl = Entity().apply { setName("smokeCurl"); enabled = false }
        smokeCurl!!.addChild(calibrated(ModelCalib.SMOKE_CURL, curlT, smokeMats))
        root.addChild(smokeCurl!!)
        lastCloudKey = -1
        Log.i(TAG, "SCENE models in ${SystemClock.elapsedRealtime() - tStart} ms, GLB materials ${glbMats.size}")
        modelsBuilt = true
        lastBright = -1f
    }

    // ---------------------------------------------------------------- textures (async, keyed)

    private fun refreshTexture(key: String, cur: String?, busy: Boolean, setBusy: (Boolean) -> Unit, make: () -> Bitmap, apply: (TextureResource) -> Unit) {
        if (key == cur || busy) return
        setBusy(true)
        scope.launch {
            val tex = withContext(Dispatchers.IO) { runCatching { TextureResource.create(make()) }.onFailure { Log.w(TAG, "TEX $key: $it") }.getOrNull() }
            if (tex != null) apply(tex)
            setBusy(false)
        }
    }

    private fun refreshReadout() {
        val tx = texts
        if (tx.read1.isEmpty()) return
        val key = "${tx.read1}|${tx.read2}|${tx.testF}"
        refreshTexture(key, readKey, readBusy, { readBusy = it }, { Tex.readout(tx.read1, tx.read2, tx.testF) }) { tex ->
            readMat?.setBaseColorTexture(tex); readTex?.let { runCatching { it.close() } }; readTex = tex; readKey = key
            Log.i(TAG, "READOUT \"${tx.read1} | ${tx.read2}\"")
        }
    }

    private fun refreshRingFace(s: Slice) {
        val tx = texts
        val key = "${s.key}|${s.endHour}|${tx.zh}|${tx.testF}"
        refreshTexture(key, ringFaceKey, ringBusy, { ringBusy = it }, {
            Tex.ringFace(s.hours.map { it.hod }, s.hours.map { it.dayIndex }, s.endHour, { d -> io.embodit.weatherscape.domain.Texts.ringDay(d, tx.zh) }, tx.testF)
        }) { tex ->
            ringFaceMat?.setBaseColorTexture(tex); ringFaceTex?.let { runCatching { it.close() } }; ringFaceTex = tex; ringFaceKey = key
            Log.i(TAG, "RINGFACE $key")
        }
    }

    private fun refreshKnob() {
        val word = texts.knob
        if (word.isEmpty()) return
        refreshTexture(word, knobKey, false, {}, { Tex.knob(word) }) { tex ->
            knobLabelMat?.setBaseColorTexture(tex); knobTex?.let { runCatching { it.close() } }; knobTex = tex; knobKey = word
        }
    }

    private fun refreshTag(text: String) {
        refreshTexture(text, tagShown, tagBusy, { tagBusy = it }, { Tex.tag(text) }) { tex ->
            tagMat?.setBaseColorTexture(tex); tagTex?.let { runCatching { it.close() } }; tagTex = tex; tagShown = text
            Log.i(TAG, "TAG \"$text\"")
        }
    }

    private fun applyWhite(mat: PhysicallyBasedMaterial?, l: WhiteLook.Look) {
        mat ?: return
        mat.setBaseColor(Color4(l.base[0], l.base[1], l.base[2], 1f))
        mat.setEmissiveColor(Color4(l.emissive[0], l.emissive[1], l.emissive[2], 1f))
    }

    /** A ◀ / ▶ press seen by the input layer (the push-in animation starts at once, before the flow answers). */
    fun pressed(name: String) { steps.firstOrNull { it.name == name }?.pressedAt = SystemClock.uptimeMillis() }

    /** ◀ / ▶: face texture in the app language, greyed at 0 h / the end (M18), pushed in 4 mm while pressed or held. */
    private fun updateSteps(now: Long, dt: Float, bright: Float) {
        val flow = graph.flow
        val tx = texts
        for (b in steps) {
            val key = "${b.d}|${tx.step}|${tx.testF}"
            refreshTexture(key, b.key, b.busy, { b.busy = it }, { Tex.step(b.d, tx.step, tx.testF) }) { tex ->
                b.faceMat?.setBaseColorTexture(tex); b.tex?.let { runCatching { it.close() } }; b.tex = tex; b.key = key
            }
            val on = flow.canStep(b.d)
            if (on != b.on || abs(bright - b.lastBright) > 0.01f) {
                val changed = on != b.on
                b.on = on; b.lastBright = bright
                b.bodyMat?.setBaseColor(if (on) rgb4(0xFFFFFF, bright) else rgb4(0x6E6E6E, bright))
                b.faceMat?.setBaseColor(if (on) rgb4(0xFFFFFF) else rgb4(0x8A8A8A))
                if (changed) Log.i(TAG, "STEP3D ${b.name} ${if (on) "on" else "greyed"}")
            }
            val down = flow.holding == b.d || (b.pressedAt >= 0 && now - b.pressedAt < 160)
            b.push = approach(b.push, if (down && on) 1f else 0f, dt, 0.04f)
            b.body?.at(V3(0f, 0f, -0.004f * b.push))
        }
    }

    // ---------------------------------------------------------------- per frame

    private fun approach(cur: Float, target: Float, dt: Float, tau: Float) = cur + (target - cur) * (1f - exp(-dt / tau))

    fun onFrame(dtIn: Float) {
        if (!basicBuilt) return
        val dt = dtIn.coerceIn(0f, 0.1f)
        val flow = graph.flow
        val pos = flow.step()
        val st = flow.value
        val now = SystemClock.uptimeMillis()
        val t = (now - t0) / 1000f
        val reduced = graph.reduced()
        refreshReadout(); refreshRingFace(st.slice); refreshKnob()
        val p = SceneModel.at(st.slice, pos, st.night)
        lastParams = p

        // ring + pin (the pin sits 15° past the end hour)
        ring?.at(V3(0f, Layout.RING_Y, 0f), Q4.axis(V3.UP, Layout.deg(pos * Ring.DEG_PER_H)))
        if (pinEnd != st.slice.endHour) {
            pinEnd = st.slice.endHour
            pin?.at(Layout.ringPoint(Layout.pinAngle(pinEnd), Layout.PIN_R, Layout.RING_H / 2))
        }
        // S01: the ring's rim breathes gold twice; S11: the knob breathes once while the window is closed
        val breath = if (st.breathAt >= 0 && st.phase == Phase.WELCOME && !reduced) { val e = (now - st.breathAt) % 4000; if (e < 2400) sin(PI.toFloat() * (e % 1200) / 1200f) else 0f } else 0f
        ringBandMat?.setEmissiveColor(Color4(0.55f * breath, 0.40f * breath, 0.10f * breath, 1f))
        val knobPulse = if (!graph.window.panelOpen && !reduced) { val e = now % 3000; if (e < 1200) sin(PI.toFloat() * e / 1200f) else 0f } else 0f
        knobMat?.setEmissiveColor(Color4(0.5f * knobPulse, 0.36f * knobPulse, 0.08f * knobPulse, 1f))
        updateSteps(now, dt, p.brightness)

        // brightness (design lighting fallback) + key light (N3)
        if (abs(p.brightness - lastBright) > 0.01f) {
            lastBright = p.brightness
            for ((mat, rgb) in dimmable) mat.setBaseColor(rgb4(rgb, p.brightness))
            // B24 whites: base dimmed with the scene, emissive >= 40 % of the colour (WhiteLook, ControlsTest)
            applyWhite(fogMatRef, WhiteLook.look(WhiteLook.FOG, p.brightness))
            // v0.1.4: crystals / wisps are unlit cards: white x the scene brightness, never below WhiteLook.CARD_MIN (B24)
            crystalMat?.setBaseColor(rgb4(WhiteLook.FLAKE, WhiteLook.crystalK(p.brightness)))
            wispMats.forEach { it.setBaseColor(rgb4(WhiteLook.SMOKE, WhiteLook.cardK(p.brightness))) }
            smokeMats.forEach { applyWhite(it, WhiteLook.look(WhiteLook.SMOKE, p.brightness)) }
            snowCoverMat?.let { val e = WhiteLook.emissiveK(p.brightness) * 0.5f; it.setEmissiveColor(Color4(0.96f * e, 0.965f * e, 0.97f * e, 1f)) }
            val g = Color4(p.brightness, p.brightness, p.brightness, 1f)
            for (mat in glbMats) runCatching { mat.setBaseColor(g) }
        }
        light?.at(V3.ZERO, Q4.fromTo(V3(0f, 0f, -1f), p.lightDir))
        lightC?.let { lc ->
            lc.intensity = p.lightIntensity
            lc.color = if (p.day > 0.5f) Color4(1f, 0.95f - 0.12f * p.lightWarm, 0.86f - 0.25f * p.lightWarm, 1f) else Color4(0.62f, 0.70f, 0.87f, 1f)
        }

        // clouds: count grows / shrinks in 0.8 s, drift with the wind, shade by sky, storm flash
        val flash = flow.flashOn(now) && !reduced
        val shadeKey = p.cloudShade * 1000 + (p.brightness * 100).toInt() + if (flash) 500 else 0
        if (shadeKey != lastCloudKey) {
            lastCloudKey = shadeKey
            // v0.1.2 (B24, user 11-13 "云怎么是黑的，应该是白云啊"): light greys only, with an emissive base so the PBR light
            // in a Shared Space volume can't turn them dark; the storm flash lights them bluish white
            val look = WhiteLook.cloud(p.cloudShade, p.brightness)
            for (cm in cloudMats) { applyWhite(cm, look); if (flash) cm.setEmissiveColor(Color4(0.95f, 0.98f, 1.1f, 1f)) }
        }
        for (i in 0 until clouds.size) {
            cloudScale[i] = approach(cloudScale[i], if (i < p.clouds) 1f else 0f, dt, 0.27f)
            val (cp, edge) = SceneModel.cloudPos(i, t, p.drift, p.downX, p.downZ)
            val s = cloudScale[i] * edge
            val c = clouds[i]
            c.enabled = s > 0.02f
            if (c.enabled) c.at(cp + V3(0f, if (p.cloudShade >= 2) -0.008f else 0f, 0f), null, s)
        }
        fogBands.forEachIndexed { i, e ->
            e.enabled = p.fog
            if (p.fog) e.at(V3(0f, FOG_Y[i], 0f), Q4.axis(V3.UP, (if (i == 0) 0.05f else -0.04f) * t + i * 2.6f))
        }

        // rain / snow: the sheet bottom stays over the island centre, the top leans downwind (<= 12°)
        val down = V3(p.downX, 0f, p.downZ)
        val tiltQ = PrecipYield.tiltQ(p.tiltDeg, p.downX, p.downZ)
        // q3-2: while a tag is up the sheets give way to it (holed copies); until they are built the sheets hide
        val tgUp = st.tag
        val wantHole = if (tgUp != null && p.precip != Precip.NONE && p.sheets > 0)
            "${tgUp.kind}|${p.precip}|${p.sheets}|${"%.2f".format(p.tiltDeg)}|${"%.3f".format(p.downX)}|${"%.3f".format(p.downZ)}" else null
        if (wantHole != null && wantHole != holeKey) buildHoles(wantHole, tgUp!!.kind, p.precip, p.sheets, tiltQ)
        val holesReady = wantHole != null && wantHole == holeKey && !holeBusy
        for (i in 0 until 4) {
            val onR = p.precip == Precip.RAIN && i < p.sheets
            rain[i].enabled = onR && wantHole == null
            val hole = if (holesReady) holeSheets.getOrNull(i) else null
            hole?.enabled = onR
            if (onR) {
                val off = ((t * 0.6f + i * 0.0125f) % Layout.RAIN_STEP)
                val at = V3(0f, Layout.PRECIP_Y0, 0f) + tiltQ.rotate(V3(0f, -off, 0f))
                rain[i].at(at, tiltQ); hole?.at(at, tiltQ)
            }
        }
        if (!holesReady) holeSheets.forEach { it.enabled = false }
        // v0.1.4 snow: crystal cards facing the user, turning slowly in their plane, swaying down the leaning cylinder;
        // flakes in front of an ask tag shrink away while it is up (Snowfall.yields, q3-2)
        val nFlakes = if (p.precip == Precip.SNOW) Snowfall.count(p.sheets) else 0
        val tagBoxUp = tgUp?.let { tagBox(it.kind) }
        for (i in 0 until flakes.size) {
            val e = flakes[i]
            if (i >= nFlakes) { flakeK[i] = 0f; e.enabled = false; continue }
            val fp = Snowfall.pose(i, if (reduced) t * 0.5f else t)
            val w = Snowfall.world(fp, tiltQ)
            val want = if (tagBoxUp != null && Snowfall.yields(w, tagBoxUp)) 0f else 1f
            flakeK[i] = if (want == 0f) approach(flakeK[i], 0f, dt, 0.03f) else approach(flakeK[i], 1f, dt, 0.15f)
            val sz = fp.size * flakeK[i]
            e.enabled = sz > 0.0008f
            if (e.enabled) { e.at(w, Q4.axis(V3(0f, 0f, 1f), fp.turn)); e.scale(sz, sz, 1f) }
        }

        if (!modelsBuilt) return
        // tree: sway about the root toward downwind (amplitude by wind level, 1 s change)
        ampCur = approach(ampCur, p.treeAmpDeg, dt, 0.35f)
        val amp = if (reduced) ampCur * 0.3f else ampCur
        val sway = amp * (0.6f + 0.4f * sin(2f * PI.toFloat() * t / 2.0f)) + amp * 0.15f * sin(5.1f * t)
        val axis = V3.UP.cross(down).let { if (it.length < 1e-4f) V3(1f, 0f, 0f) else it }
        tree?.at(ModelCalib.TREE_AT, Q4.axis(axis, Layout.deg(sway)))
        // vane: damped spring toward the wind source (0.8 s, small overshoot)
        var d = p.vaneYaw - vaneYaw
        while (d > PI) d -= (2 * PI).toFloat(); while (d < -PI) d += (2 * PI).toFloat()
        vaneVel += (d * 60f - vaneVel * 10f) * dt
        vaneYaw += vaneVel * dt
        vaneArrow?.at(VANE_FOOT + V3(0f, 0.024f, 0f), Q4.axis(V3.UP, vaneYaw))
        // smoke from the chimney (feels < 12°, dry), leaning downwind; it makes way for any ask tag (q1-1, Smoke)
        // v0.1.4: the Tripo smoke curl sits on the chimney mouth (sways a little), wisp cards rise from it and fade out
        smokeK = approach(smokeK, Smoke.target(p.smoke, st.tag != null), dt, Smoke.FADE_TAU)
        smokeCurl?.let { c ->
            c.enabled = smokeK > 0.02f
            if (c.enabled) c.at(Smoke.SOURCE, Q4.axis(V3.UP, 0.4f * sin(t * 0.7f)) * Q4.axis(V3(-p.downZ, 0f, p.downX).let { if (it.length < 1e-4f) V3(1f, 0f, 0f) else it }, Layout.deg(6f + 2f * p.windLevel)), smokeK)
        }
        for (i in 0 until Smoke.N) {
            val e = smoke[i]
            e.enabled = smokeK > 0.02f
            if (!e.enabled) continue
            val w = Smoke.wisp(i, t, p.downX, p.downZ, p.windLevel)
            wispMats[i].setOpacity(w.alpha * smokeK)
            e.at(w.c, Q4.axis(V3(0f, 0f, 1f), w.spin)); e.scale(w.size * smokeK, w.size * smokeK, 1f)
        }
        // night glows: windows and the lamp bulb (0.8 s)
        glow = approach(glow, if (p.isDay) 0f else 1f, dt, 0.27f)
        val win = rgb4(lerpRgb(0x2A2620, 0xFFC46B, glow))
        windowMats.forEach { it.setBaseColor(win) }
        bulbMat?.setBaseColor(rgb4(lerpRgb(0x3A3630, 0xFFD28A, glow)))
        // sun / moon on the arc
        // v0.1.4 sun: the Tripo sun (self-lit texture) turns slowly in its plane, its halo breathes; dimmer behind cloud
        sun?.enabled = p.sunUp
        if (p.sunUp) {
            sun?.at(p.sunPos)
            sunModel?.at(V3.ZERO, Q4.axis(V3(0f, 0f, 1f), if (reduced) 0f else -0.08f * t))
            val k = if (p.sunDim) 0.6f else 1f
            sunMats.forEach { it.setBaseColor(Color4(k, k, k, 1f)); it.setEmissiveColor(Color4(k, k, k, 1f)) }
            haloMat?.setOpacity((if (p.sunDim) 0.45f else 0.9f) * (1f + if (reduced) 0f else 0.06f * sin(t * 1.6f)).coerceAtMost(1f))
        }
        // moon: the crater model under its phase shell, turned for the slice hour's moon age (MoonPhase)
        moon?.enabled = p.moonUp
        if (p.moonUp) {
            moon?.at(p.moonPos)
            // the slice hour's local date; the built-in sample day ("D1 07:00") uses today's date
            val days = MoonPhase.epochDays(st.slice.at(p.hour).time) ?: (System.currentTimeMillis() / 86_400_000.0 + p.hour / 24.0)
            val age = MoonPhase.age(days)
            if (kotlin.math.abs(age - moonAge) > 0.02) {
                moonAge = age
                moonShade?.at(V3.ZERO, MoonPhase.shadeTurn(age))
                Log.i(TAG, "MOON age %.1f d, lit %.0f %%".format(age, 100 * MoonPhase.illumination(age)))
            }
        }
        // snow cover (1 s)
        snowCur = approach(snowCur, p.snowDepth, dt, 0.33f)
        snowIsland?.let { si -> si.enabled = snowCur > 0.02f; if (si.enabled) si.at(V3(0f, ModelCalib.ISLAND_BOTTOM + 0.004f * snowCur, 0f)) }
        roofSnow?.enabled = snowCur > 0.15f
        // pond ripples while it rains
        val nr = if (p.precip == Precip.RAIN) minOf(6, p.sheets + 2) else 0
        for (i in 0 until 6) {
            val e = ripples[i]
            if (i >= nr || reduced) { e.enabled = false; continue }
            val life = 0.9f
            val tt = t + i * 0.37f
            val cycle = (tt / life).toInt()
            val k = (tt % life) / life
            val h = ((cycle * 73 + i * 151) % 1000) / 1000f
            val h2 = ((cycle * 37 + i * 97) % 1000) / 1000f
            val rr = ModelCalib.POND_R * 0.6f * kotlin.math.sqrt(h)
            val a = h2 * 2f * PI.toFloat()
            e.enabled = true
            e.at(ModelCalib.POND_C + V3(rr * sin(a), 0.0012f, rr * kotlin.math.cos(a)), null, 0.002f + 0.012f * k)
        }
        // S05 tag: above the tapped thing, faces the user (+Z), rises 1 cm in 0.15 s, shrinks away in the last 0.3 s
        val tg = st.tag
        val tagText = texts.tag
        val tagEntity = tag
        if (tg != null && tagText != null && tagEntity != null) {
            refreshTag(tagText)
            val age = now - tg.at
            val anchor = tagAnchor(tg.kind)
            val rise = (age / 150f).coerceIn(0f, 1f)
            val shrink = if (age > 2500) (1f - (age - 2500) / 300f).coerceIn(0f, 1f) else 1f
            tagEntity.enabled = tagShown == tagText && shrink > 0.01f
            tagEntity.at(anchor + V3(0f, Layout.TAG_GAP + Layout.TAG_H / 2 - 0.01f + 0.01f * rise, 0f), Q4.ID, shrink)
        } else tagEntity?.enabled = false
    }

    /** q3-2: rebuilds the holed rain / snow sheets for tag [kind] (strands crossing in front of its box are left out). */
    private fun buildHoles(key: String, kind: Ask, precip: Precip, sheets: Int, tiltQ: Q4) {
        if (holeBusy) return
        holeBusy = true
        holeKey = key
        if (precip != Precip.RAIN) { holeKey = key; return } // v0.1.4: snow yields flake by flake (Snowfall.yields)
        val rainy = true
        val box = tagBox(kind)
        val mat: Material? = rainMatRef
        scope.launch {
            var kept = 0; var full = 0
            val meshes = withContext(Dispatchers.IO) {
                runCatching {
                    (0 until sheets.coerceAtMost(4)).map { i ->
                        val data = Pieces.rainSheet(3 + i, PrecipYield.rainSkip(tiltQ, box))
                        kept += data.vertexCount; full += Pieces.rainSheet(3 + i).vertexCount
                        Kit.mesh(data, "rainHole$i")
                    }
                }.onFailure { Log.w(TAG, "HOLES $key: $it") }.getOrNull()
            }
            holeSheets.forEach { runCatching { it.removeFromParent() } }; holeSheets.clear()
            holeMeshes.forEach { runCatching { it.close() } }; holeMeshes.clear()
            if (meshes != null && mat != null) {
                holeMeshes += meshes
                meshes.forEachIndexed { i, m -> holeSheets += model(m, mat, "precipHole$i").also { it.enabled = false; root.addChild(it) } }
                Log.i(TAG, "PRECIP yields to tag $kind: ${if (rainy) "rain" else "snow"} x $sheets keeps $kept / $full vertices")
            } else holeKey = null
            holeBusy = false
        }
    }

    /** Top centre of what a tag stands over (design tagsVsModels). */
    fun tagAnchor(k: Ask): V3 = when (k) {
        Ask.SKY -> V3(0f, Layout.CLOUD_Y1, 0f)
        Ask.TREE -> ModelCalib.TREE_AT + V3(0f, ModelCalib.TREE.size.y, 0f)
        Ask.HOUSE -> ModelCalib.HOUSE_AT + VANE_FOOT + V3(0f, 0.035f, 0f)
    }

    private fun lerpRgb(a: Int, b: Int, k: Float): Int {
        fun ch(s: Int) = ((a shr s and 255) + ((b shr s and 255) - (a shr s and 255)) * k).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Debug hit replay: the holder and its deepest child (the drawn mesh a ray lands on). */
    fun replayEntities(target: String): List<Entity> {
        val out = ArrayList<Entity>()
        fun deepest(e: Entity): Entity { var x = e; while (x.getChildren().isNotEmpty()) x = x.getChildren().first(); return x }
        fun visit(e: Entity) {
            if (runCatching { e.getName() }.getOrNull() == target && e.enabled) { out += e; deepest(e).takeIf { it !== e }?.let { out += it } }
            e.getChildren().forEach { visit(it) }
        }
        visit(root)
        return out
    }

    /** M8 instance list: every drawn entity, its material kind and whether anything is transparent. */
    fun instances(): String {
        val sb = StringBuilder()
        fun visit(e: Entity, depth: Int) {
            val mc = e.components[ModelComponent::class.java]
            if (mc != null) {
                val kinds = (0 until mc.materials.size).map { i -> runCatching { mc.materials.get(i).javaClass.simpleName }.getOrDefault("?") }
                sb.append("INSTANCE ${runCatching { e.getName() }.getOrDefault("?")} mats=$kinds enabled=${e.enabled}\n")
            }
            e.getChildren().forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        return sb.toString()
    }

    fun evidence(): String {
        val p = lastParams
        return "built=$basicBuilt/$modelsBuilt params=$p clouds=${cloudScale.joinToString { "%.2f".format(it) }} glow=$glow snow=$snowCur readout=\"$readKey\" ring=$ringFaceKey"
    }

    fun dispose() {
        synchronized(keep) { keep.forEach { runCatching { it.close() } }; keep.clear() }
        listOf(readTex, ringFaceTex, knobTex, tagTex).forEach { it?.let { t -> runCatching { t.close() } } }
        steps.forEach { b -> b.tex?.let { runCatching { it.close() } }; b.tex = null }
        holeMeshes.forEach { runCatching { it.close() } }; holeMeshes.clear()
    }

    companion object {
        /** House-local (calibrated, origin = bottom centre): the ridge foot of the vane at the end away from the chimney. */
        val VANE_FOOT = V3(0f, 0.0725f, -0.026f)
        /** v0.1.4: each cloud's turn about the vertical, so the 3 Tripo shapes never look repeated. */
        val CLOUD_YAW = floatArrayOf(0f, 0.5f, -0.35f, 2.9f, 3.5f, 2.6f)
        /** Fog wisp ring heights (scene y): just above the grass, under the house eaves. */
        val FOG_Y = floatArrayOf(-0.150f, -0.137f)
        /** Night window glows (position, turn): front gable window and two on the left wall (estimated from the model, ui-audit shots). */
        val HOUSE_WINDOWS: List<Pair<V3, Q4>> = listOf(
            V3(0.009f, 0.024f, 0.0425f) to Q4.ID,
            V3(-0.0405f, 0.026f, 0.017f) to Q4.axis(V3.UP, -(PI / 2).toFloat()),
            V3(-0.0405f, 0.026f, -0.018f) to Q4.axis(V3.UP, -(PI / 2).toFloat()),
        )

        /** The tag's box for the occlusion / volume unit tests. */
        fun tagBox(k: Ask): Aabb {
            val a = when (k) {
                Ask.SKY -> V3(0f, Layout.CLOUD_Y1, 0f)
                Ask.TREE -> ModelCalib.TREE_AT + V3(0f, ModelCalib.TREE.size.y, 0f)
                Ask.HOUSE -> ModelCalib.HOUSE_AT + VANE_FOOT + V3(0f, 0.035f, 0f)
            }
            return Layout.tagBox(a)
        }
    }
}
