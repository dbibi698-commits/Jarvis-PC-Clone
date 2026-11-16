package com.jarvis.ai.core

/**
 * v1.3.9 — Real-time mood + nuance detection (on-device, keyword based, Bengali + English).
 * Runs on every user message in MainActivity.submit(); the result is stored in
 * Prefs.lastMood and injected into the Persona system prompt, so Jarvis actually
 * answers with matching warmth. humorLevel() tells the prompt how playful it
 * may be right now (sad/tired/angry -> gentle, no jokes; happy -> playful).
 */
object MoodDetector {
    data class Mood(val key: String, val label: String, val emoji: String)
    data class Nuance(val urgent: Boolean, val isQuestion: Boolean, val grateful: Boolean)

    private val happy = listOf("happy", "khushi", "খুশি", "valo lagche", "ভালো লাগছে", "bhalo lagche", "great", "awesome", "darun", "দারুণ", "moja", "মজা", "excited", "darun lagche", "খুব ভালো", "love it", "bhalo laglo", "অসাধারণ", "fantastic", "wonderful")
    private val sad = listOf("sad", "mon kharap", "মন খারাপ", "dukho", "দুঃখ", "kharap lagche", "খারাপ লাগছে", "depressed", "crying", "kanna", "কান্না", "lonely", "eka lagche", "একা লাগছে", "bhalo lagche na", "ভালো লাগছে না", "heartbroken", "মন ভাঙা")
    private val angry = listOf("angry", "rag", "রাগ", "ragi", "রাগি", "annoyed", "birokto", "বিরক্ত", "frustrated", "khep", "ক্ষেপ", "khepe", "ক্ষেপে", "osojjo", "অসহ্য", "bhalo lagche na ekdom")
    private val tired = listOf("tired", "klanto", "ক্লান্ত", "ghum", "ঘুম", "sleepy", "exhausted", "thaka", "থাকা", "ghum pacche", "ঘুম পাচ্ছে", "rest", "bishram", "বিশ্রাম")
    private val stressed = listOf("stress", "tension", "টেনশন", "chinta", "চিন্তা", "worried", "pressure", "pareshan", "পেরেশান", "chap", "চাপ", "bhoy", "ভয়", "anxious", "nervous")
    private val bored = listOf("bored", "boring", "birokto lagche", "ekgheye", "একঘেয়ে", "kicchu korar nai", "কিছু করার নাই")
    private val confused = listOf("confused", "bujhte parchi na", "বুঝতে পারছি না", "bujhi nai", "বুঝি নাই", "ki korbo", "কি করবো", "confusion")
    private val grateful = listOf("thank", "dhonnobad", "ধন্যবাদ", "shukriya", "শুকরিয়া", "thanks", "bhalo korecho", "ভালো করেছো")

    fun detect(text: String): Mood {
        val low = " $text ".lowercase()
        fun score(words: List<String>) = words.count { low.contains(it) }
        val scores = listOf(
            Mood("happy", "Happy", "😊") to score(happy),
            Mood("sad", "Sad", "😔") to score(sad),
            Mood("angry", "Angry", "😠") to score(angry),
            Mood("tired", "Tired", "😴") to score(tired),
            Mood("stressed", "Stressed", "😰") to score(stressed),
            Mood("bored", "Bored", "🥱") to score(bored),
            Mood("confused", "Confused", "🤔") to score(confused),
        )
        val best = scores.maxByOrNull { it.second }
        return if (best != null && best.second > 0) best.first else Mood("calm", "Calm", "🙂")
    }

    fun nuance(text: String): Nuance {
        val low = text.lowercase()
        val urgent = listOf("urgent", "jaldi", "জলদি", "ekhoni", "এখনি", "emergency", "taratar", "তাড়াতাড়ি", "immediately", "asap", "ekhuni").any { low.contains(it) }
        val isQuestion = text.trim().endsWith("?") || listOf("ki ", "কি ", "kemon", "কেমন", "kobe", "কবে", "kothay", "কোথায়", "why", "how", "what").any { low.contains(it) }
        val grateful = grateful.any { low.contains(it) }
        return Nuance(urgent, isQuestion, grateful)
    }

    /** How playful Jarvis may be right now. Sad / tired / angry / stressed -> gentle, no jokes. */
    fun humorLevel(moodKey: String): String = when (moodKey) {
        "happy" -> "playful — jokes and light teasing are welcome"
        "calm", "bored", "confused" -> "light — one small joke at most, keep it warm"
        else -> "gentle — no jokes right now, be caring and calm"
    }
}
