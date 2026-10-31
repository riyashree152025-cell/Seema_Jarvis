package com.seema.assistant

import android.content.Context
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool

/**
 * Voice tools that let SEEMA answer questions about what is on the screen right now
 * ("what does this message say?", "is my name on this form?").
 *
 * The tools are deliberately thin: they only read the text that
 * [SeemaAccessibilityService] has already captured into [SeemaScreenReader.lastScreenText], so
 * they return instantly, never block the caller and never start a screen read of their own.
 * Nothing is looked at until the user enables screen access; until then [readScreen] and
 * [findOnScreen] answer with status "needs_user" instead of failing. KSP generates
 * `generatedTools()`.
 */
class SeemaScreenTools(private val context: Context) {

    private val appContext: Context = context.applicationContext

    @Tool(description = "Reads the text on the screen the user is looking at, so you can answer questions about it.")
    fun readScreen(): Map<String, String> {
        val screen = SeemaScreenReader.lastScreenText
        if (screen.isBlank()) {
            if (!SeemaScreenReader.isEnabled(appContext)) return screenAccessOff()
            return mapOf(
                "status" to "ok",
                "screen" to "",
                "message" to "No screen text has been captured yet. Ask the user to open the app or " +
                    "screen they mean, then try again."
            )
        }
        return mapOf("status" to "ok", "screen" to screen)
    }

    @Tool(description = "Checks whether a piece of text is on the screen the user is looking at, with a short excerpt around it.")
    fun findOnScreen(@Param("text to look for") query: String): Map<String, String> {
        val needle = query.replace(WHITESPACE, " ").trim()
        if (needle.isEmpty()) return mapOf("status" to "error", "error" to "Tell me what to look for on the screen.")

        val screen = SeemaScreenReader.lastScreenText
        if (screen.isBlank()) {
            if (!SeemaScreenReader.isEnabled(appContext)) return screenAccessOff()
            return mapOf(
                "status" to "ok",
                "found" to "false",
                "message" to "No screen text has been captured yet. Ask the user to open the app or " +
                    "screen they mean, then try again."
            )
        }

        val at = screen.indexOf(needle, ignoreCase = true)
        if (at < 0) {
            return mapOf(
                "status" to "ok",
                "found" to "false",
                "message" to "\"$needle\" is not on the current screen."
            )
        }

        val start = (at - CONTEXT_RADIUS).coerceAtLeast(0)
        val end = (at + needle.length + CONTEXT_RADIUS).coerceAtMost(screen.length)
        val excerpt = buildString {
            if (start > 0) append("...")
            append(screen.substring(start, end).replace(WHITESPACE, " ").trim())
            if (end < screen.length) append("...")
        }
        return mapOf("status" to "ok", "found" to "true", "context" to excerpt)
    }

    /** Screen reading is off: Android needs one tap from the user, which is why this is not an error. */
    private fun screenAccessOff(): Map<String, String> = mapOf(
        "status" to "needs_user",
        "message" to "I cannot see the screen yet. Turn on Screen Context in the app settings, " +
            "and then switch SEEMA on in Settings, Accessibility."
    )

    private companion object {
        /** Chars kept on each side of a match, so the excerpt is about 80 characters wide. */
        const val CONTEXT_RADIUS = 40
        val WHITESPACE = Regex("\\s+")
    }
}
