package com.jarvis.ai.tools

import android.content.Context
import java.io.File

/**
 * v1.4.0: offline document translation — a built-in Bengali <-> English
 * dictionary, so common words and phrases translate with NO internet.
 * Honest limit: word-by-word + phrase matching only — full sentence
 * translation needs the online Gemini translator (translate tool).
 */
object OfflineTranslator {

    private val EN_TO_BN: Map<String, String> = mapOf(
        "hello" to "হ্যালো", "hi" to "হাই", "thank you" to "ধন্যবাদ", "thanks" to "ধন্যবাদ",
        "please" to "দয়া করে", "sorry" to "দুঃখিত", "yes" to "হ্যাঁ", "no" to "না",
        "good" to "ভালো", "bad" to "খারাপ", "big" to "বড়", "small" to "ছোট",
        "happy" to "খুশি", "sad" to "দুঃখিত", "hot" to "গরম", "cold" to "ঠান্ডা",
        "new" to "নতুন", "old" to "পুরনো", "fast" to "দ্রুত", "slow" to "ধীরে",
        "beautiful" to "সুন্দর", "easy" to "সহজ", "hard" to "কঠিন",
        "i" to "আমি", "you" to "তুমি", "he" to "সে", "she" to "সে", "we" to "আমরা",
        "they" to "তারা", "this" to "এটা", "that" to "ওটা", "here" to "এখানে",
        "there" to "ওখানে", "where" to "কোথায়", "when" to "কখন", "why" to "কেন",
        "how" to "কেমন", "what" to "কী", "who" to "কে",
        "eat" to "খাওয়া", "drink" to "পান করা", "go" to "যাওয়া", "come" to "আসা",
        "do" to "করা", "want" to "চাওয়া", "like" to "পছন্দ করা", "love" to "ভালোবাসা",
        "need" to "দরকার", "sleep" to "ঘুমানো", "wake" to "জাগা", "work" to "কাজ করা",
        "play" to "খেলা", "read" to "পড়া", "write" to "লেখা", "speak" to "বলা",
        "listen" to "শোনা", "see" to "দেখা", "give" to "দেওয়া", "take" to "নেওয়া",
        "buy" to "কেনা", "open" to "খোলা", "close" to "বন্ধ করা",
        "water" to "জল", "food" to "খাবার", "rice" to "ভাত", "tea" to "চা",
        "coffee" to "কফি", "milk" to "দুধ", "home" to "বাড়ি", "house" to "বাড়ি",
        "phone" to "ফোন", "money" to "টাকা", "time" to "সময়", "day" to "দিন",
        "night" to "রাত", "morning" to "সকাল", "evening" to "সন্ধ্যা",
        "today" to "আজ", "tomorrow" to "আগামীকাল", "yesterday" to "গতকাল",
        "friend" to "বন্ধু", "mother" to "মা", "father" to "বাবা", "brother" to "ভাই",
        "sister" to "বোন", "son" to "ছেলে", "daughter" to "মেয়ে", "child" to "শিশু",
        "people" to "মানুষ", "man" to "লোক", "woman" to "মহিলা",
        "one" to "এক", "two" to "দুই", "three" to "তিন", "four" to "চার",
        "five" to "পাঁচ", "six" to "ছয়", "seven" to "সাত", "eight" to "আট",
        "nine" to "নয়", "ten" to "দশ",
        "car" to "গাড়ি", "road" to "রাস্তা", "school" to "স্কুল", "market" to "বাজার",
        "hospital" to "হাসপাতাল", "doctor" to "ডাক্তার", "book" to "বই",
        "song" to "গান", "movie" to "সিনেমা", "rain" to "বৃষ্টি", "sun" to "সূর্য",
        "moon" to "চাঁদ", "sky" to "আকাশ", "tree" to "গাছ", "flower" to "ফুল"
    )

    private val PHRASES_EN_TO_BN: Map<String, String> = mapOf(
        "how are you" to "তুমি কেমন আছো", "i love you" to "আমি তোমাকে ভালোবাসি",
        "good morning" to "সুপ্রভাত", "good night" to "শুভরাত্রি",
        "thank you very much" to "অনেক ধন্যবাদ", "see you later" to "পরে দেখা হবে",
        "what is your name" to "তোমার নাম কী", "my name is" to "আমার নাম",
        "where are you" to "তুমি কোথায়", "i am fine" to "আমি ভালো আছি",
        "i don't know" to "আমি জানি না", "come here" to "এখানে এসো",
        "go home" to "বাড়ি যাও", "eat food" to "খাবার খাও", "drink water" to "জল খাও"
    )

    private val BN_TO_EN: Map<String, String> by lazy {
        val m = linkedMapOf<String, String>()
        for ((en, bn) in EN_TO_BN) if (!m.containsKey(bn)) m[bn] = en
        for ((en, bn) in PHRASES_EN_TO_BN) if (!m.containsKey(bn)) m[bn] = en
        m
    }

    fun translate(ctx: Context, input: String, target: String): String {
        var text = input.trim()
        var fromFile: String? = null
        if (text.endsWith(".txt", true) || text.endsWith(".md", true)) {
            val f = File(ctx.getExternalFilesDir(null), "workspace/$text")
            if (f.exists()) { text = f.readText(); fromFile = f.name } else {
                val alt = File(text)
                if (alt.exists()) { text = alt.readText(); fromFile = alt.name }
            }
        }
        if (text.isBlank()) return "Kichu lekho ba file-er naam dao — jemon: \"hello\" ba notes.txt."
        val toBn = target.lowercase().let { it.contains("bengali") || it.contains("bangla") || it.contains("bn") }
        val out = if (toBn) toBengali(text) else toEnglish(text)
        val note = "\n\n📴 Offline mode — word-by-word translation (internet thakle 'translate' tool-e full sentence hobe)."
        return if (fromFile != null) {
            val ext = if (toBn) ".bn.txt" else ".en.txt"
            val outFile = File(ctx.getExternalFilesDir(null), "workspace/${fromFile.removeSuffix(".txt").removeSuffix(".md")}$ext")
            outFile.parentFile?.mkdirs()
            outFile.writeText(out)
            "✅ File translate kore dilam (offline): ${outFile.name}\n\n$out$note"
        } else "$out$note"
    }

    private fun toBengali(text: String): String {
        var s = " $text "
        val phrases = PHRASES_EN_TO_BN.entries.sortedByDescending { it.key.length }
        for ((en, bn) in phrases) {
            s = s.replace(Regex("(?i)\\b${Regex.escape(en)}\\b"), " $bn ")
        }
        return s.split(Regex("((?<=\\s)|(?=\\s))")).joinToString("") { tok ->
            val low = tok.lowercase()
            EN_TO_BN[low]?.let { keepCase(tok, it) } ?: tok
        }.trim()
    }

    private fun toEnglish(text: String): String {
        var s = " $text "
        val phrases = BN_TO_EN.entries.filter { it.key.contains(" ") }.sortedByDescending { it.key.length }
        for ((bn, en) in phrases) {
            s = s.replace(bn, " $en ")
        }
        return s.split(Regex("((?<=\\s)|(?=\\s))")).joinToString("") { tok ->
            BN_TO_EN[tok] ?: tok
        }.trim()
    }

    private fun keepCase(orig: String, translated: String): String {
        if (orig.isEmpty()) return translated
        return if (orig[0].isUpperCase()) translated else translated
    }
}
