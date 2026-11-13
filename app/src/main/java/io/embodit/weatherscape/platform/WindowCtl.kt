package io.embodit.weatherscape.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.weatherscape.ui.Containers

/**
 * Opens / reopens the control window beside the diorama (Placement.right in Main.kt). The app never reads the head pose
 * (design container.mrCapabilities): profile D44 correction, a Shared Space volume app does not promise "in front of
 * you"; [重开控制窗] closes the window, waits for ON_DESTROY and opens it again beside the diorama, and the user moves it
 * with the system handle. Exit closes both containers before the tasks are removed (D44).
 */
class WindowCtl {
    var panelState by mutableStateOf("never")
        private set
    val panelOpen: Boolean get() = panelState != "never" && panelState != "ON_DESTROY"

    private var navigator: SpatialNavigator? = null
    private var panelNavigator: SpatialNavigator? = null
    private var reopenPending = false
    private val main = Handler(Looper.getMainLooper())

    /** The diorama volume hands over its navigator (the window's own composition can't reopen itself). */
    fun attach(nav: SpatialNavigator) { navigator = nav }
    fun detach() { navigator = null }
    fun attachPanel(nav: SpatialNavigator) { panelNavigator = nav }

    /** Open the control window beside the diorama (first open, the knob in S11, the app icon again). */
    fun open(why: String) {
        if (panelOpen) return
        Log.i(TAG, "WINDOW open why=$why")
        main.post {
            runCatching { (navigator ?: panelNavigator)?.openWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }
                .onFailure { Log.e(TAG, "WINDOW open failed", it) }
        }
    }

    /** Settings [重开控制窗] (T17): close and reopen beside the diorama. */
    fun reopen() {
        if (!panelOpen) { open("reopen"); return }
        reopenPending = true
        Log.i(TAG, "WINDOW reopen close (beside the diorama)")
        runCatching { (navigator ?: panelNavigator)?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }
    }

    fun onPanelLifecycle(event: String) {
        panelState = event
        Log.i(TAG, "WINDOW lifecycle $event")
        if (reopenPending && event == "ON_DESTROY") { reopenPending = false; main.postDelayed({ open("reopen") }, 150) }
    }

    /** Debug: the system [×] on the control window (S11). */
    fun closeControlOnly() { runCatching { navigator?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) } }

    /** Exit (D26 / D44): close the control window and the diorama volume itself. */
    fun closeAll() {
        val nav = navigator ?: panelNavigator ?: return
        runCatching { nav.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }.onFailure { Log.w(TAG, "EXIT close control: $it") }
        runCatching { navigator?.closeWindowContainer() }.onFailure { Log.w(TAG, "EXIT close volume: $it") }
        Log.i(TAG, "EXIT closed all window containers")
    }
}
