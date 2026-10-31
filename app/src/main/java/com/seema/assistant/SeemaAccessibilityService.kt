package com.seema.assistant

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The last text SEEMA saw on screen, plus whether the user has switched screen access on.
 *
 * Everything here lives in memory only and is overwritten on every new screen. It is read by
 * the voice tools (see `SeemaScreenTools`) when the user asks something about the screen.
 */
object SeemaScreenReader {

    /** Newest captured screen text; empty until a window event has been read. */
    @Volatile
    var lastScreenText: String = ""

    /** Package name of the app that screen text came from, or empty when unknown. */
    @Volatile
    var lastPackage: String = ""

    /**
     * True when the user has turned SEEMA on under Settings -> Accessibility.
     *
     * This only reads the system setting; it does not (and cannot) switch access on, because
     * Android only lets the user do that by hand.
     */
    fun isEnabled(context: Context): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(
                context.applicationContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull()
        if (enabled.isNullOrEmpty()) return false
        val service = SeemaAccessibilityService::class.java
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            val component = ComponentName.unflattenFromString(entry) ?: continue
            if (component.packageName != context.applicationContext.packageName) continue
            // The setting normally holds the fully qualified class name; accept the short form too.
            if (component.className == service.name || component.className == service.simpleName) return true
        }
        return false
    }
}

/**
 * Reads what is on the user's screen so SEEMA can answer questions about it.
 *
 * Screen text is collected on the fly, kept in memory for the current session only
 * ([SeemaScreenReader.lastScreenText]), and never written to disk. Reading the screen this way
 * never sends data anywhere by itself: nothing leaves the phone, is uploaded, or is shared with
 * another app until a voice tool such as `readScreen` asks for it during a conversation with the
 * user. The user stays in control the whole time, because Android only enables this service after
 * they switch SEEMA on under Settings -> Accessibility, and it can be switched off again at any
 * moment ([SeemaScreenReader.isEnabled] reports the current state, [openSettings] opens the list).
 */
class SeemaAccessibilityService : AccessibilityService() {

    @Volatile
    private var connected = false

    /** Set while a walk is in flight, so a burst of events cannot queue up behind each other. */
    private val busy = AtomicBoolean(false)

    /** One daemon thread for the tree walk; the accessibility callback itself stays quick. */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "seema-screen-reader").apply { isDaemon = true }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        Log.i(TAG, "Screen access connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> capture(event.packageName?.toString())
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected = false
        // Do not leave the last screen lying around after the user switches access off.
        SeemaScreenReader.lastScreenText = ""
        SeemaScreenReader.lastPackage = ""
        worker.shutdownNow()
        super.onDestroy()
    }

    /**
     * Grabs the active window's root on the service thread (cheap and most reliable here), then
     * walks it on [worker]: the walk can touch a few hundred nodes, which has no business
     * running on the main thread. Overlapping events are dropped rather than queued.
     */
    private fun capture(packageName: String?) {
        if (!connected) return
        // Overlapping events are dropped rather than queued, before anything expensive happens.
        if (!busy.compareAndSet(false, true)) return
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root == null) {
            busy.set(false)
            return
        }
        try {
            worker.execute {
                try {
                    val text = collectText(root)
                    if (text.isNotEmpty()) {
                        SeemaScreenReader.lastScreenText = text
                        SeemaScreenReader.lastPackage = packageName.orEmpty()
                    }
                } catch (t: Throwable) {
                    // A window can disappear mid-walk; never let that reach the framework.
                    Log.w(TAG, "Screen capture failed", t)
                } finally {
                    busy.set(false)
                }
            }
        } catch (t: Throwable) {
            // Executor already shut down (service being destroyed).
            busy.set(false)
            Log.w(TAG, "Screen capture skipped", t)
        }
    }

    /**
     * Breadth-first walk of [root], collecting visible labels. Capped at [MAX_NODES] nodes and
     * [MAX_CHARS] characters so a huge list or web page cannot stall the phone. Empty and
     * repeated lines are skipped; nodes are never recycled (the platform does that itself, and
     * manual recycling is what makes tree walks crash).
     */
    private fun collectText(root: AccessibilityNodeInfo): String {
        val lines = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var chars = 0

        fun add(value: CharSequence?) {
            if (chars >= MAX_CHARS) return
            val line = value?.toString()?.replace(WHITESPACE, " ")?.trim().orEmpty()
            if (line.isEmpty() || !lines.add(line)) return
            chars += line.length + 1
        }

        while (queue.isNotEmpty() && visited < MAX_NODES && chars < MAX_CHARS) {
            val node = queue.poll() ?: continue
            visited++
            add(node.text)
            add(node.contentDescription)
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                queue.add(child)
            }
        }
        return lines.joinToString("\n")
    }

    companion object {
        private const val TAG = "SeemaScreen"
        private const val MAX_NODES = 300
        private const val MAX_CHARS = 4_000
        private val WHITESPACE = Regex("\\s+")

        /** Opens the system accessibility list, where the user switches SEEMA on or off. */
        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { Log.w(TAG, "Could not open accessibility settings", it) }
        }
    }
}
