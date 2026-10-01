package com.clean.x

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object AppLogger {
    private const val TAG = "XApp"
    private const val MAX_LOGS = 600
    private val logs = ConcurrentLinkedQueue<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, msg: String) {
        val timestamp = synchronized(timeFormat) {
            timeFormat.format(Date())
        }
        val entry = "[$timestamp] $tag: $msg"
        Log.d(tag, msg)
        logs.add(entry)
        while (logs.size > MAX_LOGS) {
            logs.poll()
        }
    }

    fun getAllLogs(): String {
        return logs.joinToString("\n")
    }

    fun saveToFile(context: Context): File? {
        return try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, "x_debug_log.txt")
            file.writeText(getAllLogs())
            file
        } catch (e: Exception) {
            Log.e(TAG, "Error saving log to file: ${e.message}")
            null
        }
    }
}
