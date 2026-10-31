package com.seema.assistant

import android.content.Context
import android.util.Log
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File and code generation.
 *
 * SEEMA can write what the model produced into its own workspace, so "make me an HTML page" ends
 * with a real file on the phone instead of a wall of text read aloud. Everything lands in
 * filesDir/generated, which the File Vault page lists, and the work stream is switched on for the
 * duration so the HUD shows the code being written.
 *
 * Deliberately sandboxed: file names are sanitised and resolved under one directory, so a tool
 * argument can never escape the app's private storage.
 */
class SeemaCodeTools(private val context: Context) {

    private val root: File
        get() = File(context.applicationContext.filesDir, DIR).apply { if (!exists()) mkdirs() }

    @Tool(
        description = "Saves a generated file (HTML, CSS, JavaScript, Kotlin, Python, JSON or plain " +
            "text) into SEEMA's workspace so the user can open it later. Use this after generating " +
            "code, a web page, or any document the user asked you to create."
    )
    fun saveCodeFile(
        @Param("file name including extension, for example index.html") name: String,
        @Param("the complete file contents") content: String
    ): Map<String, String> {
        val safe = sanitise(name)
        if (safe.isEmpty()) return failure("That file name is not usable.")
        if (content.isBlank()) return failure("There is nothing to write into $safe.")

        SeemaWorkStream.begin("CODE GEN · $safe")
        return try {
            val target = File(root, safe)
            target.writeText(content, Charsets.UTF_8)
            Log.i(TAG, "Wrote ${target.length()} bytes to ${target.name}")
            mapOf(
                "status" to "ok",
                "file" to target.name,
                "bytes" to target.length().toString(),
                "location" to "File Vault"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not write $safe", e)
            failure("Could not write the file: ${e.message ?: "unknown error"}")
        } finally {
            SeemaWorkStream.end()
        }
    }

    @Tool(description = "Lists the files SEEMA has generated and saved in its workspace.")
    fun listGeneratedFiles(): Map<String, String> {
        val files = root.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) return mapOf("status" to "ok", "files" to "none yet")
        val stamp = SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH)
        val listing = files.take(MAX_LIST).joinToString("\n") { file ->
            "${file.name} (${file.length()} bytes, ${stamp.format(Date(file.lastModified()))})"
        }
        return mapOf("status" to "ok", "count" to files.size.toString(), "files" to listing)
    }

    /** Keeps the name inside the workspace: no separators, no traversal, no leading dots. */
    private fun sanitise(name: String): String = name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trimStart('.')
        .take(MAX_NAME_CHARS)

    private fun failure(message: String) = mapOf("status" to "error", "error" to message)

    private companion object {
        const val TAG = "SeemaCodeTools"
        const val DIR = "generated"
        const val MAX_NAME_CHARS = 64
        const val MAX_LIST = 25
    }
}
