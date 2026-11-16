package com.jarvis.ai.core

import org.json.JSONObject

/**
 * v1.4.0: family wake words — each family member gets their own wake word,
 * e.g. "ammu dakche" -> Ammu, "baba suno" -> Baba. When Jarvis hears that
 * wake word it knows who is talking and greets them by name (Prefs.lastSpeaker).
 * Stored as JSON in Prefs.familyWakeWordsJson: { "wake word": "Person Name" }.
 */
object FamilyWakeWords {

    fun load(): Map<String, String> = runCatching {
        val json = Prefs.familyWakeWordsJson.trim()
        if (json.isEmpty()) return emptyMap()
        val obj = JSONObject(json)
        val map = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k.lowercase()] = obj.optString(k, "")
        }
        map
    }.getOrDefault(emptyMap())

    private fun save(map: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        Prefs.familyWakeWordsJson = obj.toString()
    }

    /** All family wake words, for the hotword listener. */
    fun allWords(): List<String> = load().keys.toList()

    /** Longest matching wake word's person for the heard text, or null. */
    fun matchPerson(heard: String): String? {
        val low = heard.lowercase()
        val hit = load().entries
            .filter { (word, _) -> word.isNotBlank() && low.contains(word) }
            .maxByOrNull { it.key.length } ?: return null
        return hit.value.ifBlank { null }
    }

    fun add(word: String, person: String): String {
        val w = word.trim().lowercase()
        val p = person.trim()
        if (w.isBlank()) return "Wake word-ta bolo — jemon: \"ammu dakche\"."
        if (p.isBlank()) return "Kar wake word? Naam-ta bolo — jemon: Ammu, Baba, Didi."
        val map = load().toMutableMap()
        map[w] = p
        save(map)
        return "✅ \"$w\" ekhon $p-er wake word! $p \"$w\" bollei Jarvis jagbe ar $p bole dakbe."
    }

    fun remove(word: String): String {
        val w = word.trim().lowercase()
        val map = load().toMutableMap()
        if (!map.containsKey(w)) return "\"$w\" wake word hisebe save kora nei."
        val person = map.remove(w) ?: ""
        save(map)
        return "✅ \"$w\" (${person}) wake word list theke tule dilam."
    }

    fun listText(): String {
        val map = load()
        if (map.isEmpty()) return "Kono family wake word save kora nei. \"add_wake_word\" bole add koro — jemon: wake word \"ammu dakche\", person Ammu."
        val sb = StringBuilder("👨‍👩‍👧 Family wake words:\n")
        for ((w, p) in map) sb.append("• \"$w\" → $p\n")
        return sb.toString().trimEnd()
    }
}
