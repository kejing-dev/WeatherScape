package io.embodit.weatherscape.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.weatherscape.data.FileWeatherStore
import io.embodit.weatherscape.data.HttpWeatherSource
import io.embodit.weatherscape.data.SettingsStore
import io.embodit.weatherscape.domain.Settings
import io.embodit.weatherscape.domain.Sound
import io.embodit.weatherscape.domain.TempUnit
import io.embodit.weatherscape.domain.WeatherFlow
import io.embodit.weatherscape.mainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

const val TAG = "WeatherScape"

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        // board-input template: mirror input lines into files/input-log.txt (headset logcat drops lines)
        io.embodit.weatherscape.ui.scene.input.BoardInputLog.attach(this)
        graph = Graph(this)
        launch(::mainApp)
    }
}

/** The process-wide objects (native-window-move.md §3.4: state shared by the volume and the window lives here). */
class Graph(context: Context) {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(app)
    val sounds = SoundPlayer(app)
    val window = WindowCtl()
    /** S00 -> ready: the volume's models are in. */
    val sceneReady = MutableStateFlow(false)

    fun systemZh(): Boolean = Locale.getDefault().language == "zh"
    fun zh(s: Settings = settings.value): Boolean = s.zh(systemZh())
    fun unit(s: Settings = settings.value): TempUnit = s.unit(systemZh(), Locale.getDefault().country in setOf("US", "LR", "MM"))

    /** Reduced motion (design M5): the system animator scale is 0. */
    fun reduced(): Boolean = runCatching { AndroidSettings.Global.getFloat(app.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

    val flow = WeatherFlow(
        scope = scope,
        io = Dispatchers.IO,
        wallMs = { System.currentTimeMillis() },
        upMs = { SystemClock.uptimeMillis() },
        source = HttpWeatherSource(),
        store = FileWeatherStore(app),
        settings = { settings.value },
        onSettings = { settings.set(it) },
        zh = { zh() },
        reduced = { reduced() },
        onSound = { sounds.play(it) },
        log = { Log.i(TAG, it) },
    )

    init {
        scope.launch { settings.settings.collect { s -> sounds.level = s.sound } }
        flow.start()
        // the city's local hour can roll over while nothing moves
        scope.launch { while (true) { delay(1000); runCatching { flow.clockTick() } } }
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

/**
 * Short events from assets/sounds/<name>.wav (+ _2 / _3 variants) through a SoundPool (design sound.list). Recorded
 * (CC0) events rotate their variants with a 0.95-1.05 rate that never repeats back to back (D51); the same event never
 * overlaps itself and the sum of what is sounding stays under -1 dBFS (D51 补, MixHeadroom). No loops, no ambience (B24).
 */
class SoundPlayer(context: Context) {
    /** 0 off, 1 low, 2 mid, 3 high (Settings.PERCENT). */
    @Volatile var level = 2

    private val pool = SoundPool.Builder().setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<Sound, List<Int>>()
    private val meta = HashMap<Int, Pair<Long, Float>>()
    private val loaded = HashSet<Int>()
    private val random = Random(17)
    private val lastRate = HashMap<Sound, Float>()
    private val lastVariant = HashMap<Sound, Int>()
    private val playing = HashMap<Sound, Int>()
    private val active = HashMap<Int, Pair<Long, Float>>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (s in Sound.entries) {
            val base = s.name.lowercase()
            val found = ArrayList<Int>()
            for (name in listOf("$base.wav", "${base}_2.wav", "${base}_3.wav")) {
                runCatching {
                    val id = context.assets.openFd("sounds/$name").use { fd -> pool.load(fd, 1) }
                    found += id
                    meta[id] = context.assets.open("sounds/$name").use { readMeta(it.readBytes()) }
                }
            }
            if (found.isNotEmpty()) ids[s] = found else Log.w(TAG, "no sound for $s")
        }
    }

    fun play(s: Sound) {
        val lv = level
        if (lv <= 0) return
        val variants = ids[s] ?: return
        val vol = Settings.PERCENT[lv.coerceIn(0, 3)] / 100f
        var rate = 0.95f + random.nextFloat() * 0.10f
        val last = lastRate[s] ?: 1f
        if (abs(rate - last) < 0.01f) rate = if (rate < 1f) rate + 0.02f else rate - 0.02f
        lastRate[s] = rate
        var v = random.nextInt(variants.size)
        if (v == lastVariant[s] && variants.size > 1) v = (v + 1) % variants.size
        lastVariant[s] = v
        val id = variants[v]
        if (synchronized(loaded) { id !in loaded }) return
        val now = SystemClock.uptimeMillis()
        synchronized(active) {
            playing.remove(s)?.let { pool.stop(it); active.remove(it) }
            active.entries.removeAll { it.value.first <= now }
            val (durMs, peak) = meta[id] ?: (500L to 0.9f)
            val others = active.values.sumOf { it.second.toDouble() }.toFloat()
            val gain = MixHeadroom.gain(vol * peak, others)
            val stream = pool.play(id, vol * gain, vol * gain, 1, 0, rate.coerceIn(0.5f, 2f))
            if (stream != 0) {
                playing[s] = stream
                active[stream] = (now + (durMs / rate).toLong()) to vol * gain * peak
            }
        }
        Log.i(TAG, "SOUND $s v$v vol=$vol")
    }

    fun release() = runCatching { pool.release() }

    companion object {
        const val MAX_STREAMS = 6

        /** Duration (ms) and peak (linear) of a 16-bit PCM WAV. */
        fun readMeta(b: ByteArray): Pair<Long, Float> {
            var i = 12
            var rate = 22050; var ch = 1
            while (i + 8 <= b.size) {
                val id = String(b, i, 4)
                val n = (b[i + 4].toInt() and 255) or ((b[i + 5].toInt() and 255) shl 8) or ((b[i + 6].toInt() and 255) shl 16) or ((b[i + 7].toInt() and 255) shl 24)
                if (id == "fmt ") {
                    ch = (b[i + 10].toInt() and 255) or ((b[i + 11].toInt() and 255) shl 8)
                    rate = (b[i + 12].toInt() and 255) or ((b[i + 13].toInt() and 255) shl 8) or ((b[i + 14].toInt() and 255) shl 16)
                }
                if (id == "data") {
                    var peak = 0
                    var j = i + 8
                    val end = minOf(b.size - 1, i + 8 + n)
                    while (j < end) { val v = abs(((b[j + 1].toInt() shl 8) or (b[j].toInt() and 255)).toShort().toInt()); if (v > peak) peak = v; j += 2 }
                    return (n / 2L / ch * 1000 / rate) to peak / 32768f
                }
                i += 8 + n + (n and 1)
            }
            return 500L to 0.9f
        }
    }
}

/** The mix limiter rule, kept pure for the unit test: the summed peaks of everything sounding stay <= -1 dBFS. */
object MixHeadroom {
    const val CEILING = 0.891f // -1 dBFS

    fun gain(newPeak: Float, othersPeak: Float): Float {
        if (newPeak <= 0f) return 1f
        val room = CEILING - othersPeak
        return (room / newPeak).coerceIn(0.15f, 1f)
    }
}
