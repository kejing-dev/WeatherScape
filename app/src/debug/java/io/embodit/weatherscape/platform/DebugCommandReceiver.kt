package io.embodit.weatherscape.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.weatherscape.domain.Ask
import io.embodit.weatherscape.domain.DefaultCities
import io.embodit.weatherscape.domain.Language
import io.embodit.weatherscape.domain.TempUnit
import io.embodit.weatherscape.ui.Page
import io.embodit.weatherscape.ui.Ui
import io.embodit.weatherscape.ui.scene.Names
import io.embodit.weatherscape.ui.scene.SceneHolder
import io.embodit.weatherscape.ui.scene.VolumeListener
import io.embodit.weatherscape.ui.scene.VolumeTargets
import io.embodit.weatherscape.ui.scene.gainOf
import io.embodit.weatherscape.ui.scene.input.BoardInputLog
import io.embodit.weatherscape.ui.scene.input.InputSource
import io.embodit.weatherscape.ui.scene.input.Offset3M
import io.embodit.weatherscape.ui.scene.input.resolveUp
import io.embodit.weatherscape.ui.scene.routeTap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only. Every command goes through the same WeatherFlow calls the volume and the window use (D29).
 *
 *   adb shell am broadcast -a io.embodit.weatherscape.DEBUG -p io.embodit.weatherscape --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S00..S11 · scrub <h> · drag "<metres> [eye-hand|hand-ray|controller] [ms]" (a real drag through the listener)
 *   back · strip <h> · city <name|sample> · now <h> (sample day, virtual now) · offline 1|0 · night 1|0|off · clock +1h|-1h
 *   lang zh|en|system · unit c|f · sound 0-3 · mute · tag sky|tree|house · flash · page weather|cities|settings · search "<q>"
 *   pick <i> (search result) · replace yes|no · exitask · closepanel · reopen · s00hold on|off · testf on|off
 *   replay "<holder> [src]" · hover <holder>|off · welcome · dump · evidence · instances
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        val f = a.split(" ").filter { it.isNotBlank() }
        Log.i(TAG, "CMD $cmd $a")
        fun later(ms: Long, block: () -> Unit) { graph.scope.launch { delay(ms); block() } }
        when (cmd) {
            "goto" -> when (a.uppercase()) {
                "S00" -> Log.i(TAG, "S00: s00hold on, force-stop and start, capture within 10 s")
                "S01" -> { Ui.page = Page.WEATHER; flow.debugWelcome() }
                "S02" -> { Ui.page = Page.WEATHER; flow.welcomeDone(); flow.debugSampleNow(0) }
                "S03" -> { Ui.page = Page.WEATHER; flow.welcomeDone(); flow.debugSampleNow(0); later(300) { flow.ringDragStart(1f); var k = 0; graph.scope.launch { while (k < 120) { flow.ringDragMove(0.0015f * k); k++; delay(16) } } } }
                "S04" -> { Ui.page = Page.WEATHER; flow.welcomeDone(); flow.debugSampleNow(0); later(200) { flow.debugScrub(15f) } }
                "S05" -> { Ui.page = Page.WEATHER; flow.welcomeDone(); flow.debugSampleNow(0); later(200) { flow.debugScrub(11f); later(300) { routeTap(graph, Names.TREE) } } }
                "S06" -> { flow.welcomeDone(); flow.clearSearch(); Ui.page = Page.CITIES }
                "S07" -> { flow.welcomeDone(); Ui.page = Page.CITIES; flow.searchInput("杭州") }
                "S08" -> { Ui.page = Page.WEATHER; flow.welcomeDone(); flow.debugOffline(true) }
                "S09" -> { flow.welcomeDone(); Ui.exitConfirm = false; Ui.page = Page.SETTINGS }
                "S10" -> { flow.welcomeDone(); Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
                "S11" -> graph.window.closeControlOnly()
                else -> Log.w(TAG, "unknown state $a")
            }
            "scrub" -> a.toFloatOrNull()?.let { flow.debugScrub(it) }
            "drag" -> {
                // a real drag through the volume listener (same path as a pinch / trigger on the ring)
                val m = f.getOrNull(0)?.toFloatOrNull() ?: 0.05f
                val src = InputSource.entries.firstOrNull { it.label == f.getOrNull(1) } ?: InputSource.HAND_RAY
                val ms = f.getOrNull(2)?.toLongOrNull() ?: 600L
                val scene = SceneHolder.scene ?: return
                val l = VolumeListener(graph, scene)
                l.onDragStart(Names.RING, src)
                graph.scope.launch {
                    val steps = (ms / 16).toInt().coerceAtLeast(2)
                    for (i in 1..steps) { l.onDragMove(Offset3M(m * i / steps, 0f, 0f)); delay(16) }
                    l.onDragEnd()
                    Log.i(TAG, "DRAG done m=$m src=${src.label} gain=${gainOf(src)}")
                }
            }
            "back" -> flow.backToNow()
            "strip" -> a.toIntOrNull()?.let { flow.gotoHour(it) }
            "city" -> if (a == "sample") flow.debugSample() else (DefaultCities.ALL.firstOrNull { it.nameEn.equals(a, true) || it.nameZh == a } ?: flow.value.cities.firstOrNull { it.nameEn.equals(a, true) || it.nameZh == a })?.let { flow.selectCity(it) }
            "now" -> a.toIntOrNull()?.let { flow.debugSampleNow(it) }
            "offline" -> flow.debugOffline(on)
            "night" -> flow.setNight(if (a == "off") null else on)
            "clock" -> { val h = a.removeSuffix("h").toIntOrNull() ?: 1; flow.debugClock(h * 3_600_000L) }
            "lang" -> graph.settings.update { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "unit" -> graph.settings.update { it.copy(unit = if (a.equals("f", true)) TempUnit.F else TempUnit.C) }
            "sound" -> a.toIntOrNull()?.let { v -> graph.settings.update { it.withSound(v) } }
            "mute" -> graph.settings.update { it.toggleMute() }
            "tag" -> routeTap(graph, when (a) { "sky" -> Names.SKY; "tree" -> Names.TREE; else -> Names.HOUSE })
            "ask" -> flow.ask(Ask.valueOf(a.uppercase()))
            "flash" -> flow.debugFlash(a.toLongOrNull() ?: 0)
            "page" -> Ui.page = when (a) { "cities" -> Page.CITIES; "settings" -> Page.SETTINGS; else -> Page.WEATHER }
            "search" -> { Ui.page = Page.CITIES; flow.searchInput(a) }
            "pick" -> a.toIntOrNull()?.let { i -> flow.value.search.results.getOrNull(i)?.let { flow.pickResult(it) } }
            "replace" -> if (on || a == "yes") flow.confirmReplace() else flow.cancelReplace()
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
            "closepanel" -> graph.window.closeControlOnly()
            "reopen" -> graph.window.reopen()
            "welcome" -> flow.welcomeDone()
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).apply()
            "testf" -> SceneHolder.testF = on
            "replay" -> {
                val scene = SceneHolder.scene ?: run { Log.w(TAG, "REPLAY not ready"); return }
                val src = InputSource.entries.firstOrNull { it.label == f.getOrNull(1) } ?: InputSource.HAND_RAY
                var last: String? = null
                val ents = scene.replayEntities(f.getOrNull(0).orEmpty())
                if (ents.isEmpty()) BoardInputLog.write(TAG, "REPLAY ${f.getOrNull(0)} src=${src.label} no entity")
                for (e in ents) {
                    val (t, path) = resolveUp(e, VolumeTargets())
                    BoardInputLog.write(TAG, "REPLAY ${f.getOrNull(0)} src=${src.label} entity=${e.getName()} path=$path -> target=$t")
                    last = t
                }
                if (ents.isNotEmpty()) VolumeListener(graph, scene).onTap(last, src, null)
            }
            "hover" -> SceneHolder.scene?.setHover(a.ifBlank { null }?.takeIf { it != "off" })
            "dump", "state" -> Log.i(TAG, "DUMP ${flow.dump()} page=${Ui.page} window=${graph.window.panelState}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence() ?: "no scene"}")
            "instances" -> SceneHolder.scene?.instances()?.lines()?.forEach { if (it.isNotBlank()) Log.i(TAG, it) }
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
    }
}
