package com.jarvis.ai.tools

import android.content.Context
import com.jarvis.ai.core.GeminiChat
import com.jarvis.ai.core.MoodDetector
import com.jarvis.ai.core.Prefs
import java.net.URLEncoder

/**
 * v1.4.0: mood-aware content recommendations — Jarvis reads your current mood
 * (MoodDetector) and suggests music / videos / activities that fit it, with
 * tappable YouTube searches. A Gemini line personalises it when a key is set.
 */
object MoodRecommend {

    private fun yt(q: String) = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")

    private val BY_MOOD: Map<String, List<String>> = mapOf(
        "happy" to listOf(
            "🎉 Party songs — ${yt("best party songs hindi")}",
            "💃 Dance videos — ${yt("bollywood dance songs")}",
            "📸 Aaj-er khushi-ta ekta reel/video-te dhoro"
        ),
        "sad" to listOf(
            "😂 Comedy — Kapil Sharma best moments — ${yt("kapil sharma best comedy")}",
            "🎵 Happy feel-good songs — ${yt("happy mood songs hindi")}",
            "📞 Kono priyo manush-ke call koro — mon halka hobe"
        ),
        "tired" to listOf(
            "🌙 Lofi chill beats — ${yt("lofi chill beats")}",
            "😴 20 minute power nap — alarm diye dicchi? \"remind me in 20 minutes\" bolo",
            "🚶 Halka hatahati + ek glass jol"
        ),
        "stressed" to listOf(
            "🧘 Meditation music — ${yt("meditation relaxing music")}",
            "🌬️ 4-7-8 breathing: 4 sec shash nao, 7 sec dhoro, 8 sec charo — 4 bar",
            "🎵 Calm piano — ${yt("calm piano relax")}"
        ),
        "bored" to listOf(
            "🎬 Interesting documentary — ${yt("best documentaries 2026")}",
            "🎮 Ekta notun game try koro — Play Store-e \"new games\" dekho",
            "📚 Kichu notun shikho — ${yt("learn something new today")}"
        ),
        "angry" to listOf(
            "🎵 Calm music — ${yt("calm music anger")}",
            "🚶 Ektu hente asho — rag kome jabe",
            "✍️ Mon-er kotha likhe felo — Jarvis save kore rakhbe"
        ),
        "calm" to listOf(
            "🎵 Soft acoustic — ${yt("soft acoustic songs")}",
            "📖 Ekta bhalo boi / podcast shono",
            "🌿 Baire ektu hawa kheye asho"
        )
    )

    fun recommend(ctx: Context, moodArg: String, cb: (String) -> Unit) {
        Thread {
            val mood = moodArg.trim().lowercase().ifBlank { Prefs.lastMood.ifBlank { "neutral" } }
            val key = mood.lowercase()
            val picks = BY_MOOD[key] ?: BY_MOOD["calm"]!!
            val sb = StringBuilder()
            val moodBn = when (key) {
                "happy" -> "khushi 😊"; "sad" -> "mon kharap 😔"; "tired" -> "klanto 😴"
                "stressed" -> "tension 😰"; "bored" -> "bored 😐"; "angry" -> "rag 😤"
                "calm" -> "shanto 😌"; else -> "neutral"
            }
            sb.append("🎭 Tomar mood ekhon: $moodBn — ei jonno suggest korchi:\n")
            picks.forEach { sb.append("• $it\n") }
            val aiLine = if (Prefs.apiKey.isNotBlank()) {
                GeminiChat.quick(
                    "The user is feeling $mood. Suggest ONE short personalised fun activity in 1 line (Bengali if the user speaks Bengali).",
                    "You are Jarvis, a warm personal assistant. One line only."
                )
            } else null
            if (!aiLine.isNullOrBlank()) sb.append("\n💡 Jarvis-er tip: $aiLine")
            cb(sb.toString().trimEnd())
        }.start()
    }
}
