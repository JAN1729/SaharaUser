package com.sahara.user.data.api

/** Incremental framing supports both the existing JSON frames and standard SSE data lines. */
class EventFrames {
    private val lines = mutableListOf<String>()
    fun push(line: String): String? {
        val trimmed = line.trimEnd('\r')
        if (trimmed.isBlank()) return finish()
        if (trimmed.startsWith(":")) return null
        if (trimmed.startsWith("event:") || trimmed.startsWith("id:")) return null
        lines.add(if (trimmed.startsWith("data:")) trimmed.removePrefix("data:").trimStart() else trimmed)
        return null
    }
    fun finish(): String? {
        if (lines.isEmpty()) return null
        return lines.joinToString("\n").also { lines.clear() }
    }
}
