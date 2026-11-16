package com.jarvis.ai.core

/**
 * v1.3.9 — Hyper-personalized context retention across sessions.
 * When Jarvis closes, it saves a one-line summary of the session (what the
 * user last talked about). When it opens again, the home screen greets the
 * user with that continuity — "Kal tumi X niye kotha bolchile" — and the
 * Persona prompt already carries the long-term memory. Nothing leaves the
 * phone; the summary is stored in Prefs.
 */
object SessionContext {
    fun saveSessionSummary() {
        runCatching {
            val hist = Memory.history()
            val lastUser = hist.lastOrNull { it.role == "user" }?.text?.take(120) ?: return
            Prefs.lastSessionSummary = "${System.currentTimeMillis()}|$lastUser"
        }
    }

    fun openingNote(): String? {
        val raw = Prefs.lastSessionSummary
        if (raw.isBlank()) return null
        val parts = raw.split("|", limit = 2)
        if (parts.size < 2) return null
        val ts = parts[0].toLongOrNull() ?: return null
        val ageHours = (System.currentTimeMillis() - ts) / 3600000
        if (ageHours > 48) return null
        val whenText = when {
            ageHours < 1 -> "ektu age"
            ageHours < 24 -> "ajke"
            else -> "kal"
        }
        return "👋 $whenText tumi bolechile: \"${parts[1]}\" — abar ki korte pari?"
    }
}
