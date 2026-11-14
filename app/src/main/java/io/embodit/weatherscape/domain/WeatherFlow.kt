package io.embodit.weatherscape.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Page states S00-S04 (design states[]); S05-S11 are modifiers / window pages. */
enum class Phase(val id: String) { LOADING("S00"), WELCOME("S01"), NOW("S02"), SCRUB("S03"), LATER("S04") }

enum class Ask { SKY, TREE, HOUSE }

/** S05: one tag above the tapped thing for 2.5 s. */
data class Tag(val kind: Ask, val hour: Int, val at: Long)

enum class SearchStatus { IDLE, LOADING, RESULTS, NONE, ERROR }

data class Search(
    val query: String = "",
    val status: SearchStatus = SearchStatus.IDLE,
    val results: List<City> = emptyList(),
    /** S07 "full, replace?" in place: the picked result and the city it would replace (least recently viewed). */
    val replace: Pair<City, City>? = null,
)

data class WState(
    val phase: Phase = Phase.LOADING,
    val slice: Slice = Sample.slice(),
    val shown: Int = 0,
    val city: City? = null,
    val cities: List<City> = emptyList(),
    /** The system time zone is not in the built-in table (advice slot says "tap the city above"). */
    val noCity: Boolean = false,
    val tag: Tag? = null,
    val search: Search = Search(),
    val fetching: Boolean = false,
    /** The last fetch failed (offline / timeout): S08 modifier. */
    val offline: Boolean = false,
    /** Ring gold rim breathes twice from this uptime (S01). */
    val breathAt: Long = -1L,
    val night: Boolean? = null,
    /** City page: each saved city's temperature + weather code right now (one multi-coordinate request). */
    val nowTemps: Map<Long, Pair<Float, Int>> = emptyMap(),
)

/** Network side, swapped for a fake in unit tests. */
interface WeatherSource {
    suspend fun forecast(c: City): String
    suspend fun search(q: String, zh: Boolean): String
    suspend fun names(id: Long, zh: Boolean): String
    suspend fun currents(cs: List<City>): String
}

/** Saved forecasts (files/cache-<key>.json) and the city list. */
interface WeatherStore {
    fun loadForecast(key: String): Pair<String, Long>?
    fun saveForecast(key: String, json: String, fetchedAtMs: Long)
    fun loadCities(): Pair<List<City>, Long?>
    fun saveCities(cities: List<City>, currentId: Long?)
}

/**
 * The single state of the app (design states[] + weatherModel). The ring's position is high-rate and lives in [ring]
 * (read by the scene every frame, never through Compose); everything the windows show is in [state] and changes at
 * most once per whole hour (D38: readout, control window, 48 h cursor read the same [WState.shown]).
 * Every input path (volume drag / tap, window buttons, the 48 h strip, the debug broadcast) calls these functions.
 */
class WeatherFlow(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val wallMs: () -> Long,
    private val upMs: () -> Long,
    private val source: WeatherSource,
    private val store: WeatherStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit,
    private val zh: () -> Boolean,
    private val zone: () -> String,
    private val reduced: () -> Boolean,
    private val onSound: (Sound) -> Unit,
    private val log: (String) -> Unit,
) {
    companion object {
        const val FETCH_TIMEOUT_MS = 8_000L
        const val REFETCH_MS = 15 * 60_000L
        const val IDLE_BACK_MS = 30 * 60_000L
        const val TAG_MS = 2_500L
        /** One ◀ / ▶ hour turns in 220 ms (under the 250 ms hold repeat, so a held button moves smoothly at 4 h/s). */
        const val STEP_MS = 220L
        const val SEARCH_DEBOUNCE_MS = 400L
        const val SOUND_COOLDOWN_MS = 3_000L
        const val TICK_MIN_MS = 125L // at most 8 a second
        const val MIN_HOURS = 12
    }

    private val lock = Any()
    private val _state = MutableStateFlow(WState())
    val state: StateFlow<WState> = _state.asStateFlow()
    val ring = Ring()

    private var forecast: Forecast? = null
    private var forecastAt = 0L
    private val lastFetch = HashMap<String, Long>()
    private var fetchJob: Job? = null
    private var searchJob: Job? = null
    private var lastInput = 0L
    private var backgroundAt = -1L
    private var lastTickMs = 0L
    private var lastRainMs = -SOUND_COOLDOWN_MS
    private var lastWindMs = -SOUND_COOLDOWN_MS
    private var settledHandled = -1L
    private var flashStart = -1L
    private var thunderAt = -1L
    private var secondFlashAt = -1L
    private var virtualShiftMs = 0L
    private var forceOffline = false
    private var ready = false
    private var welcomeShown = false

    private fun set(f: (WState) -> WState) = synchronized(lock) { _state.value = f(_state.value) }
    val value: WState get() = _state.value
    fun now() = wallMs() + virtualShiftMs

    // ------------------------------------------------------------------ start / data

    fun start() {
        touch()
        val (saved, currentId) = runCatching { store.loadCities() }.getOrDefault(emptyList<City>() to null)
        var cities = saved
        var current = cities.firstOrNull { it.id == currentId } ?: cities.firstOrNull()
        var noCity = false
        if (current == null) {
            current = DefaultCities.forZone(zone())
            if (current != null) cities = listOf(current) else noCity = true
            runCatching { store.saveCities(cities, current?.id) }
        }
        set { it.copy(city = current, cities = cities, noCity = noCity) }
        log("FLOW start city=${current?.nameEn} zone=${zone()} cities=${cities.size}")
        showCached(current)
        current?.let { fetch(it, force = true) }
    }

    /** The scene's models are in: leave S00 (first run -> S01 how-to + ring breathing). */
    fun sceneReady() {
        if (ready) return
        ready = true
        val s = settings()
        set { it.copy(phase = if (!s.seenHowTo) Phase.WELCOME else Phase.NOW, breathAt = if (!s.seenHowTo) upMs() else -1L) }
        welcomeShown = !s.seenHowTo
        log("PHASE ${value.phase.id} (scene ready)")
    }

    private fun showCached(c: City?) {
        val cached = c?.let { runCatching { store.loadForecast(it.key) }.getOrNull() }
        val sl = cached?.let { (json, at) ->
            runCatching { OpenMeteo.parseForecast(json) }.getOrNull()?.let { f ->
                forecast = f; forecastAt = at
                OpenMeteo.slice(f, now(), Source.CACHE, ((now() - at) / 3_600_000L).toInt())
            }
        }
        applySlice(if (sl != null && sl.hours.size >= MIN_HOURS) sl else { forecast = null; Sample.slice() }, keepRing = false)
    }

    private fun applySlice(sl: Slice, keepRing: Boolean) {
        synchronized(lock) {
            ring.setEnd(sl.endHour)
            if (!keepRing) ring.jump(0f)
            _state.value = _state.value.copy(slice = sl, shown = ring.shown)
        }
        log("SLICE ${sl.source} hours=${sl.hours.size} end=${sl.endHour} first=${sl.hours.first().time} age=${sl.ageHours}")
    }

    /** Fetch [c]'s forecast (start, city change, foreground after 30 min). Same city within 15 min only with [force]. */
    fun fetch(c: City, force: Boolean = false) {
        val t = wallMs()
        if (!force && (lastFetch[c.key] ?: -REFETCH_MS * 2) > t - REFETCH_MS) return
        lastFetch[c.key] = t
        fetchJob?.cancel()
        set { it.copy(fetching = true) }
        fetchJob = scope.launch {
            val json = runCatching {
                if (forceOffline) error("debug offline")
                withTimeout(FETCH_TIMEOUT_MS) { withContext(io) { source.forecast(c) } }
            }
            val f = json.mapCatching { OpenMeteo.parseForecast(it) }
            if (value.city?.key != c.key) return@launch
            f.onSuccess { fc ->
                val at = wallMs()
                runCatching { withContext(io) { store.saveForecast(c.key, json.getOrThrow(), at) } }
                forecast = fc; forecastAt = at
                val sl = OpenMeteo.slice(fc, now(), Source.LIVE)
                if (sl != null && sl.hours.size >= MIN_HOURS) {
                    // S08 -> live: refresh quietly, the ring keeps its position
                    applySlice(sl, keepRing = true)
                    set { it.copy(fetching = false, offline = false) }
                    log("FETCH ok ${c.nameEn} ${sl.hours.size} h")
                } else {
                    set { it.copy(fetching = false, offline = true) }
                    log("FETCH short ${c.nameEn}")
                }
            }.onFailure { e ->
                set { it.copy(fetching = false, offline = true) }
                // the cached slice (if any) is already on; refresh its age
                if (value.slice.source == Source.CACHE) showCachedKeepRing(c)
                log("FETCH failed ${c.nameEn}: ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    private fun showCachedKeepRing(c: City) {
        val f = forecast ?: return
        val sl = OpenMeteo.slice(f, now(), Source.CACHE, ((now() - forecastAt) / 3_600_000L).toInt())
        if (sl != null && sl.hours.size >= MIN_HOURS) applySlice(sl, keepRing = true) else applySlice(Sample.slice(), keepRing = false)
        log("SLICE cache ${c.nameEn}")
    }

    /** Called every frame and on a 1 s timer: the city's local hour rolled over -> re-cut, keep the absolute time. */
    fun clockTick() {
        val f = forecast ?: return
        val st = value
        if (st.slice.source == Source.SAMPLE) return
        val key = OpenMeteo.localHourKey(now(), f.utcOffsetSec)
        val first = st.slice.hours.first().time
        if (key.take(13) == first.take(13)) return
        val src = if (st.slice.source == Source.LIVE && !st.offline) Source.LIVE else Source.CACHE
        val sl = OpenMeteo.slice(f, now(), src, ((now() - forecastAt) / 3_600_000L).toInt())
        if (sl == null || sl.hours.size < MIN_HOURS) { applySlice(Sample.slice(), keepRing = false); return }
        val steps = hoursBetween(first, sl.hours.first().time)
        synchronized(lock) {
            ring.setEnd(sl.endHour)
            if (st.phase == Phase.LATER || st.phase == Phase.SCRUB) ring.shift(-steps.toFloat()) else ring.jump(0f)
            _state.value = _state.value.copy(slice = sl, shown = ring.shown, phase = if (st.phase == Phase.LATER && ring.pos <= 0.01f) Phase.NOW else _state.value.phase)
        }
        log("CLOCK rolled $first -> ${sl.hours.first().time} (${steps} h) phase=${value.phase.id} ring=${ring.pos}")
    }

    private fun hoursBetween(a: String, b: String): Int = runCatching {
        val ta = java.time.LocalDateTime.parse(a); val tb = java.time.LocalDateTime.parse(b)
        java.time.Duration.between(ta, tb).toHours().toInt()
    }.getOrDefault(1)

    // ------------------------------------------------------------------ ring

    private fun touch() { lastInput = wallMs() }

    fun ringDragStart(gain: Float) {
        touch()
        synchronized(lock) {
            if (value.phase == Phase.LOADING) return
            ring.dragStart(gain, upMs())
            _state.value = _state.value.copy(phase = Phase.SCRUB, tag = null)
        }
        endWelcome("drag")
        flashStart = -1L
        log("RING drag start gain=$gain pos=${ring.pos}")
    }

    fun ringDragMove(travelM: Float) {
        synchronized(lock) { ring.dragMove(travelM, upMs()) }
        afterMove()
    }

    fun ringDragEnd() {
        touch()
        synchronized(lock) { ring.dragEnd(upMs()) }
        log("RING drag end pos=${"%.2f".format(ring.pos)} mode=${ring.mode}")
    }

    /** [回到现在] / the strip's "now" cell: the ring turns back the straight way (design tapReadout). */
    fun backToNow() {
        touch()
        synchronized(lock) {
            if (ring.pos <= 0.001f && !ring.moving) { _state.value = _state.value.copy(phase = Phase.NOW); return }
            ring.animateTo(0, upMs())
            _state.value = _state.value.copy(phase = Phase.SCRUB, tag = null)
        }
        log("RING back to now")
    }

    /** 48 h strip tap: turn to that hour in 0.6 s (D43 one-press path). */
    fun gotoHour(h: Int) {
        touch()
        if (h <= 0) { backToNow(); return }
        synchronized(lock) {
            ring.animateTo(h, upMs())
            _state.value = _state.value.copy(phase = Phase.SCRUB, tag = null)
        }
        endWelcome("strip")
        log("RING goto $h")
    }

    // ◀ / ▶ (C2 / M18, D53 3D buttons beside the ring): one hour per press, 4 h/s while held, greyed at the ends
    private val hold = HourHold()

    /** The hour ◀ / ▶ count from and whether each can still move (greyed at 0 h / the end, M18). */
    fun stepTarget(): Int = synchronized(lock) { ring.target }
    fun canStep(d: Int): Boolean = synchronized(lock) { if (d < 0) ring.target > 0 else ring.target < ring.endHour }

    /** One hour back (d = -1) or on (+1); back to 0 h settles in S02 (M18). Same call for the window and the 3D buttons. */
    fun stepHour(d: Int, why: String = "button"): Boolean {
        touch()
        val to: Int
        synchronized(lock) {
            if (value.phase == Phase.LOADING) return false
            to = (ring.target + d).coerceIn(0, ring.endHour)
            if (to == ring.target) return false
            ring.animateTo(to, upMs(), STEP_MS)
            _state.value = _state.value.copy(phase = Phase.SCRUB, tag = null)
        }
        endWelcome("step")
        log("RING step ${if (d > 0) "+1" else "-1"} -> $to ($why)")
        return true
    }

    /** Press-and-hold start (window ◀ ▶ or the 3D buttons): steps once now, repeats from [step]. */
    fun holdStart(d: Int, why: String) {
        val k = synchronized(lock) { hold.press(d, upMs()) }
        log("HOLD press $d ($why)")
        if (!stepHour(k, why)) holdEnd(why)
    }

    fun holdEnd(why: String) {
        if (hold.dir != 0) log("HOLD release ($why)")
        synchronized(lock) { hold.release(upMs()) }
    }

    /** A tap with no press seen first (the system tap path): one step unless the press already stepped. */
    fun holdTap(d: Int, why: String) {
        val k = synchronized(lock) { hold.tap(d, upMs()) }
        if (k != 0) stepHour(k, "$why tap") else log("HOLD tap folded into the press ($why)")
    }

    val holding: Int get() = hold.dir

    /** Debug "scrub <h>" holds the ring on [h] directly (same state path as a settled drag). */
    fun debugScrub(h: Float) {
        touch()
        synchronized(lock) { ring.jump(h); _state.value = _state.value.copy(shown = ring.shown, phase = if (ring.shown == 0) Phase.NOW else Phase.LATER, tag = null) }
        settledHandled = -1L
        onSettled(upMs())
        log("RING scrub $h -> shown ${ring.shown}")
    }

    private fun afterMove() {
        val shown = ring.shown
        if (shown != value.shown) onShownChanged(shown)
    }

    private fun onShownChanged(shown: Int) {
        val prev = value.slice.at(value.shown)
        val next = value.slice.at(shown)
        set { it.copy(shown = shown, tag = null) }
        val t = upMs()
        if (t - lastTickMs >= TICK_MIN_MS) { lastTickMs = t; onSound(Sound.TICK) }
        if (next.sky == Sky.RAIN || next.sky == Sky.STORM) {
            if (!(prev.sky == Sky.RAIN || prev.sky == Sky.STORM) && t - lastRainMs >= SOUND_COOLDOWN_MS) { lastRainMs = t; onSound(Sound.RAIN) }
        }
        val windy = { x: Hour -> x.sky == Sky.SNOW || Wind.level(x.wind) >= 3 }
        if (windy(next) && !windy(prev) && t - lastWindMs >= SOUND_COOLDOWN_MS) { lastWindMs = t; onSound(Sound.WIND) }
    }

    /** Per frame (ECS): advance the ring, publish hour changes, settle -> S02 / S04, run the storm flashes. */
    fun step(): Float {
        val t = upMs()
        val wasMoving: Boolean
        val drawn: Float
        synchronized(lock) {
            wasMoving = ring.moving
            drawn = ring.step(t)
        }
        val rep = synchronized(lock) { hold.tick(t) }
        if (rep != 0 && !stepHour(rep, "hold")) holdEnd("end reached")
        afterMove()
        if (wasMoving && !ring.moving) onSettled(t)
        // S05 expiry, S01 breathing, idle return
        val st = value
        if (st.tag != null && t - st.tag.at > TAG_MS + 300) set { it.copy(tag = null) }
        if (st.phase == Phase.LATER && wallMs() - lastInput > IDLE_BACK_MS) { log("IDLE 30 min -> back to now"); backToNow() }
        if (thunderAt in 0..t) { thunderAt = -1L; if (!reduced() && value.phase != Phase.SCRUB) onSound(Sound.THUNDER) }
        if (secondFlashAt in 0..t) { secondFlashAt = -1L; if (!reduced() && value.slice.at(value.shown).sky == Sky.STORM && value.phase != Phase.SCRUB) flashStart = t }
        return drawn
    }

    private fun onSettled(t: Long) {
        if (settledHandled == t) return
        settledHandled = t
        val wasAnim = value.phase == Phase.SCRUB
        val ph = if (ring.shown == 0 && ring.pos < 0.01f) Phase.NOW else Phase.LATER
        set { it.copy(phase = if (it.phase == Phase.WELCOME) it.phase else ph, shown = ring.shown) }
        log("RING settled at ${ring.shown} -> ${ph.id}")
        if (ph == Phase.NOW && wasAnim) onSound(Sound.CLACK)
        // storm: two flashes 0.3 s after stopping, a far thunder 1.5-4 s later (once), at most one more silent round
        flashStart = -1L; thunderAt = -1L; secondFlashAt = -1L
        if (value.slice.at(ring.shown).sky == Sky.STORM && !reduced()) {
            flashStart = t + 300
            thunderAt = t + 300 + 2_200
            secondFlashAt = t + 9_000
        }
    }

    /** Storm flash: two 80 ms flashes with a 120 ms gap from [flashStart]. */
    fun flashOn(t: Long = upMs()): Boolean {
        if (t < flashHoldUntil) return true
        if (flashStart < 0 || value.phase == Phase.SCRUB) return false
        val e = t - flashStart
        return e in 0 until 80 || e in 200 until 280
    }

    /** Debug "flash": one flash pair now (QA's "the frame of the flash"). */
    fun debugFlash(holdMs: Long = 0) { if (holdMs >= 80) flashHoldUntil = upMs() + holdMs else flashStart = upMs() - holdMs }
    private var flashHoldUntil = -1L

    // ------------------------------------------------------------------ ask (S05)

    fun ask(k: Ask) {
        touch()
        val st = value
        if (k == Ask.SKY && SceneModel.at(st.slice, st.shown.toFloat(), st.night).clouds == 0) return
        set { it.copy(tag = Tag(k, it.shown, upMs())) }
        onSound(Sound.PAPER)
        log("ASK $k hour=${st.shown}")
    }

    fun tagText(t: Tag, zh: Boolean, u: TempUnit): String {
        val x = value.slice.at(t.hour)
        return when (t.kind) { Ask.SKY -> Texts.tagSky(x, zh); Ask.TREE -> Texts.tagTree(x, zh, u); Ask.HOUSE -> Texts.tagHouse(x, zh, u) }
    }

    // ------------------------------------------------------------------ welcome

    fun welcomeDone() = endWelcome("button")

    private fun endWelcome(why: String) {
        if (value.phase != Phase.WELCOME && !welcomeShown) return
        welcomeShown = false
        onSettings(settings().copy(seenHowTo = true))
        set { it.copy(phase = if (it.phase == Phase.WELCOME) (if (ring.shown == 0 && !ring.moving) Phase.NOW else if (ring.moving) Phase.SCRUB else Phase.LATER) else it.phase, breathAt = -1L) }
        log("WELCOME done ($why) -> ${value.phase.id}")
    }

    /** Debug "goto S01": the first-run card again. */
    fun debugWelcome() {
        onSettings(settings().copy(seenHowTo = false))
        welcomeShown = true
        synchronized(lock) { ring.jump(0f); _state.value = _state.value.copy(phase = Phase.WELCOME, shown = 0, breathAt = upMs(), tag = null) }
    }

    // ------------------------------------------------------------------ cities (S06 / S07)

    fun selectCity(c: City) {
        touch()
        val viewed = c.copy(lastViewedMs = wallMs())
        val cities = value.cities.map { if (it.id == c.id) viewed else it }.let { l -> if (l.none { it.id == c.id }) l + viewed else l }
        set { it.copy(city = viewed, cities = cities, noCity = false, search = Search(), offline = false, tag = null) }
        runCatching { store.saveCities(cities, c.id) }
        synchronized(lock) { ring.jump(0f) }
        set { it.copy(phase = if (it.phase == Phase.WELCOME) it.phase else Phase.NOW, shown = 0) }
        showCached(viewed)
        fetch(viewed, force = true)
        log("CITY select ${c.nameEn}/${c.nameZh}")
    }

    fun searchInput(q: String) {
        searchJob?.cancel()
        val query = q
        if (query.trim().length < 2) { set { it.copy(search = Search(query)) }; return }
        set { it.copy(search = Search(query, SearchStatus.LOADING)) }
        val z = zh()
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val r = runCatching {
                if (forceOffline) error("debug offline")
                withTimeout(FETCH_TIMEOUT_MS) {
                    withContext(io) {
                        // "中文英文都行": Chinese characters are looked up in Chinese (v0.1.1: "西安" in English UI found only
                        // Xi'ancun), anything else in the UI language; nothing found -> the other language once
                        // v0.1.3 (q3-1 / K1): a Chinese query also asks its traditional spelling and English aliases
                        // (GeoNames lists 東京 / 倫敦, not 东京 / 伦敦); the first request is required, the rest best effort
                        val sz = OpenMeteo.searchZh(query, z)
                        var lang = sz
                        val qs = OpenMeteo.searchQueries(query, z)
                        val texts = listOf(source.search(qs.first(), sz)) + qs.drop(1).mapNotNull { q -> runCatching { source.search(q, sz) }.getOrNull() }
                        var list = OpenMeteo.parseSearchMany(texts, sz, query)
                        if (list.isEmpty()) { lang = !sz; list = OpenMeteo.parseSearch(source.search(query, !sz), !sz, query) }
                        // results found in the other language: fill the UI language's names so the rows read in it
                        if (lang != z) list = list.map { c -> runCatching { OpenMeteo.mergeNames(c, source.names(c.id, z), z) }.getOrDefault(c) }
                        list
                    }
                }
            }
            if (value.search.query != query) return@launch
            r.onSuccess { list -> set { it.copy(search = Search(query, if (list.isEmpty()) SearchStatus.NONE else SearchStatus.RESULTS, list)) } }
                .onFailure { e -> set { it.copy(search = Search(query, SearchStatus.ERROR)) }; log("SEARCH failed: ${e.message}") }
            log("SEARCH \"$query\" -> ${value.search.status} ${value.search.results.map { it.name(z) }}")
        }
    }

    /** City page opened: temperature + sky of every saved city now, in one request (design panels.cities). */
    fun refreshCityTemps() {
        val cs = value.cities
        if (cs.isEmpty() || forceOffline) return
        scope.launch {
            runCatching { withTimeout(FETCH_TIMEOUT_MS) { withContext(io) { OpenMeteo.parseCurrents(source.currents(cs)) } } }
                .onSuccess { list -> set { st -> st.copy(nowTemps = cs.zip(list).associate { (c, v) -> c.id to v }) } }
                .onFailure { log("TEMPS failed: ${it.message}") }
        }
    }

    fun clearSearch() { searchJob?.cancel(); set { it.copy(search = Search()) } }

    /** A search result tapped: save it (full -> ask to replace the least recently viewed, in place). */
    fun pickResult(c: City) {
        touch()
        val cities = value.cities
        if (cities.any { it.id == c.id }) { selectCity(cities.first { it.id == c.id }); return }
        if (cities.size >= 3) {
            val victim = cities.minBy { it.lastViewedMs }
            set { it.copy(search = it.search.copy(replace = c to victim)) }
            log("CITY full -> ask replace ${victim.nameEn} with ${c.nameEn}")
            return
        }
        addCity(c, null)
    }

    fun confirmReplace() {
        val (c, victim) = value.search.replace ?: return
        addCity(c, victim)
    }

    fun cancelReplace() = set { it.copy(search = it.search.copy(replace = null)) }

    private fun addCity(c: City, victim: City?) {
        val z = zh()
        val list = value.cities.filter { it.id != victim?.id } + c
        set { it.copy(cities = list) }
        selectCity(c)
        // fill the other language's names (design: store the id, fetch names via /v1/get)
        if (c.id > 0) scope.launch {
            runCatching { withTimeout(FETCH_TIMEOUT_MS) { withContext(io) { source.names(c.id, !z) } } }.onSuccess { txt ->
                val merged = OpenMeteo.mergeNames(c, txt, !z)
                val l2 = value.cities.map { if (it.id == c.id) merged.copy(lastViewedMs = it.lastViewedMs) else it }
                set { st -> st.copy(cities = l2, city = if (st.city?.id == c.id) l2.first { it.id == c.id } else st.city) }
                runCatching { store.saveCities(l2, value.city?.id) }
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle / debug

    fun onBackground() { backgroundAt = wallMs() }

    fun onForeground() {
        val away = if (backgroundAt > 0) wallMs() - backgroundAt else 0L
        backgroundAt = -1L
        if (away > IDLE_BACK_MS) { log("FOREGROUND after ${away / 60000} min -> now + refresh"); backToNow(); value.city?.let { fetch(it, force = true) } }
    }

    fun setNight(n: Boolean?) = set { it.copy(night = n) }

    /** Debug "clock +1h": move the virtual wall clock (the same rollover path as a real hour change). */
    fun debugClock(deltaMs: Long) { virtualShiftMs += deltaMs; clockTick() }

    /** Debug "offline 1|0": force fetch / search failures; 1 re-runs the start-up path with the network gone. */
    fun debugOffline(on: Boolean) {
        forceOffline = on
        val c = value.city
        if (on) { showCached(c); set { it.copy(offline = true) } } else c?.let { fetch(it, force = true) }
    }

    /** Debug "city sample": the built-in sample day, no city. */
    fun debugSample() {
        fetchJob?.cancel()
        forecast = null
        synchronized(lock) { ring.jump(0f) }
        set { it.copy(offline = false, phase = if (it.phase == Phase.LOADING || it.phase == Phase.WELCOME) it.phase else Phase.NOW) }
        applySlice(Sample.slice(), keepRing = false)
    }

    /** Debug "now <h>": the sample day with its virtual "now" moved to hour [h] (design S02 note). */
    fun debugSampleNow(h: Int) {
        fetchJob?.cancel(); forecast = null
        applySlice(Sample.slice(h.coerceIn(0, 47)), keepRing = false)
        set { it.copy(phase = if (it.phase == Phase.LOADING || it.phase == Phase.WELCOME) it.phase else Phase.NOW, shown = 0, offline = false) }
    }

    fun dump(): String {
        val s = value
        return "phase=${s.phase.id} shown=${s.shown} ring=${"%.2f".format(ring.pos)} mode=${ring.mode} end=${ring.end} src=${s.slice.source} age=${s.slice.ageHours} " +
            "city=${s.city?.nameEn} cities=${s.cities.map { it.nameEn }} offline=${s.offline} fetching=${s.fetching} tag=${s.tag?.kind} search=${s.search.status} night=${s.night}"
    }
}
