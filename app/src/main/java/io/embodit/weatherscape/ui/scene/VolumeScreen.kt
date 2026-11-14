package io.embodit.weatherscape.ui.scene

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.weatherscape.R
import io.embodit.weatherscape.domain.Ask
import io.embodit.weatherscape.domain.Layout
import io.embodit.weatherscape.domain.Texts
import io.embodit.weatherscape.domain.WeatherFlow
import io.embodit.weatherscape.platform.Graph
import io.embodit.weatherscape.platform.TAG
import io.embodit.weatherscape.ui.scene.input.BoardInputConfig
import io.embodit.weatherscape.ui.scene.input.BoardInputListener
import io.embodit.weatherscape.ui.scene.input.BoardInputLog
import io.embodit.weatherscape.ui.scene.input.InputSource
import io.embodit.weatherscape.ui.scene.input.Offset3M
import io.embodit.weatherscape.ui.scene.input.TargetResolver
import io.embodit.weatherscape.ui.scene.input.boardInput
import io.embodit.weatherscape.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Runs the volume's per-frame motion inside the ECS (never in Compose). */
class DioramaFrameSystem : System() {
    override fun update(context: SceneUpdateContext) { runCatching { SceneHolder.scene?.onFrame(context.deltaTime) }.onFailure { Log.e(TAG, "FRAME", it) } }
}

internal object SceneHolder {
    @Volatile var scene: DioramaScene? = null
    @Volatile var testF = false
}

/**
 * The diorama volume (default container). Opens the control window beside it, builds the scene in two stages (plinth /
 * ring / readout first, Tripo models next, design budget.startup), and routes every pinch / trigger / drag on a holder
 * (ring, knob, sky, tree, house) to [WeatherFlow] through the factory board-input template (D50: hand ray, controller
 * ray and eye-hand alike).
 */
@Composable
fun VolumeScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val st by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val scene = remember { DioramaScene(scope, graph) }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.open("start")
        onDispose { graph.window.detach() }
    }
    LaunchedEffect(scene) {
        val t0 = SystemClock.elapsedRealtime()
        while (!scene.ready && SystemClock.elapsedRealtime() - t0 < 15_000) delay(30)
        graph.sceneReady.value = true
        graph.flow.sceneReady()
        Log.i(TAG, "SCENE ready=${scene.ready} after ${SystemClock.elapsedRealtime() - t0} ms -> S00 done")
        // placement diagnostics at 1 / 3 / 5 s after ready (build.md: volume-only app, the system places the volume)
        for (s in listOf(1000L, 2000L, 2000L)) { delay(s); Log.i(TAG, "DIAG panel=${graph.window.panelState} phase=${graph.flow.value.phase.id} ${graph.flow.dump()}") }
    }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { Log.i(TAG, "LIFECYCLE foreground"); graph.flow.onForeground() }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> if (graph.window.panelState == "ON_DESTROY") graph.window.open("resume")
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    DisposableEffect(scene) {
        SceneHolder.scene = scene
        registerSystem<DioramaFrameSystem>()
        onDispose {
            unregisterSystem<DioramaFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            graph.sceneReady.value = false
            scene.dispose()
        }
    }

    // the scene's words, in the app language (D38: readout = window = same WState.shown)
    val zh = graph.zh(settings)
    val unit = graph.unit(settings)
    val loc = remember(context, settings.language) { context.localized(settings.language) }
    val (r1, r2) = Texts.readout(st.slice, st.shown, zh, unit)
    scene.texts = SceneTexts(r1, r2, loc.getString(R.string.knob), st.tag?.let { graph.flow.tagText(it, zh, unit) }, zh, SceneHolder.testF, loc.getString(R.string.step_hour))

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .boardInput(remember { VolumeTargets() }, remember(graph, scene) { VolumeListener(graph, scene) }, BoardInputConfig(dragStartM = 0.012f, logTag = TAG)),
        initial = { content, _ ->
            content.addEntity(scene.root)
            runCatching { scene.installBasics() }.onFailure { Log.e(TAG, "BASICS failed", it) }
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); delay(10_000) }
            val t0 = SystemClock.elapsedRealtime()
            val cache = HashMap<String, Entity>()
            runCatching {
                scene.installModels { n ->
                    cache.getOrPut(n) { withContext(NonCancellable + Dispatchers.Main) { Entity.loadSuspend("asset://models/$n.glb") }.also { it.enabled = false; content.addEntity(it) } }
                }
            }.onFailure { Log.e(TAG, "MODELS failed", it) }
            Log.i(TAG, "MODELS ready in ${SystemClock.elapsedRealtime() - t0} ms")
        },
        update = { _, _ -> },
    )
}

/** Hit entity (or an ancestor) -> the holder name the flow knows (5 design targets + the v0.1.2 ◀ ▶ buttons). */
internal class VolumeTargets : TargetResolver<String> by TargetResolver.byName(::targetOf) {
    companion object {
        fun targetOf(name: String): String? = name.takeIf { it in Names.ALL }

        /** Hit replay (build.md 命中回放): [path] = the hit node's name first, then its parents'. */
        fun resolvePath(path: List<String>): String? = io.embodit.weatherscape.ui.scene.input.resolvePath(path, ::targetOf)
    }
}

/** Gain per input (design input.ringDrag): controller / hand ray 1.0 (the ring follows the ray point), eye-hand pinch 2.5. */
internal fun gainOf(src: InputSource): Float = if (src == InputSource.EYE_HAND) 2.5f else 1.0f

/** Every resolved tap / drag goes through [routeTap] / [WeatherFlow] (the same calls the window and the debug broadcast make). */
internal class VolumeListener(private val graph: Graph, private val scene: DioramaScene) : BoardInputListener<String> {
    private var dragging = false

    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target")
        routeTap(graph, target)
    }

    /** ◀ / ▶ (D53): the press starts the hold (one hour now, 4 h/s after 0.45 s), the release stops it. */
    private var holdingStep = false

    override fun onPress(target: String?, source: InputSource) {
        val d = Names.stepDir(target) ?: return
        BoardInputLog.write(TAG, "INPUT press src=${source.label} target=$target")
        holdingStep = true
        scene.pressed(target!!)
        graph.flow.holdStart(d, "3D $target ${source.label}")
    }

    override fun onRelease() {
        if (!holdingStep) return
        holdingStep = false
        graph.flow.holdEnd("3D release")
    }

    // gate:allow shared-drag 用户 11-12 定天气境留 Shared Space，有 3D ◀ ▶ + 时间块点按路径
    override fun onDragStart(target: String, source: InputSource): Boolean {
        BoardInputLog.write(TAG, "INPUT drag src=${source.label} target=$target")
        if (Names.stepDir(target) != null) return true // a held button that moved a little: still the same press
        if (target != Names.RING) { routeTap(graph, target); return true }
        graph.flow.ringDragStart(gainOf(source))
        dragging = true
        return true
    }

    /** N1: the template reports volume axes (+X right); only the horizontal x travel turns the ring (N2: where you grab does not matter). */
    override fun onDragMove(totalM: Offset3M) {
        if (!dragging) return
        graph.flow.ringDragMove(totalM.x / Layout.SCALE) // world metres -> layout metres (q3-3 root scale)
        if (BoardInputLog.verbosePointer && SystemClock.uptimeMillis() % 10 == 0L) BoardInputLog.write(TAG, "RINGMOVE total=(%.3f, %.3f, %.3f)".format(totalM.x, totalM.y, totalM.z))
    }

    override fun onDragEnd() { if (dragging) graph.flow.ringDragEnd(); dragging = false }

    override fun onDragCancel() { if (dragging) graph.flow.ringDragEnd(); dragging = false }

    /** D50 hover: a hint only, never a state change. */
    override fun onHover(target: String?, source: InputSource) { scene.setHover(target) }
}

/** Holder name -> the one flow call. */
internal fun routeTap(graph: Graph, name: String?) {
    when (name) {
        Names.KNOB -> { Log.i(TAG, "S11 knob -> (re)open the control window"); graph.window.open("knob") }
        Names.STEP_BACK, Names.STEP_FWD -> { SceneHolder.scene?.pressed(name); graph.flow.holdTap(Names.stepDir(name)!!, "3D $name") }
        else -> routeFlow(graph.flow, name)
    }
}

/** [routeTap] without the Android graph (hit-replay unit tests drive a bare WeatherFlow through it). */
internal fun routeFlow(flow: WeatherFlow, name: String?) {
    when (name) {
        Names.SKY -> flow.ask(Ask.SKY)
        Names.TREE -> flow.ask(Ask.TREE)
        Names.HOUSE -> flow.ask(Ask.HOUSE)
        Names.STEP_BACK, Names.STEP_FWD -> flow.holdTap(Names.stepDir(name)!!, "3D $name")
        else -> Unit
    }
}
