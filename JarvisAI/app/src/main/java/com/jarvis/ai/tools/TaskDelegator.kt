package com.jarvis.ai.tools

import android.content.Context
import com.jarvis.ai.core.BackgroundWorker
import com.jarvis.ai.core.GeminiChat
import com.jarvis.ai.core.Prefs
import java.io.File

/**
 * v1.3.9 — Autonomous agentic task delegation: Jarvis takes a task, runs it
 * in the background as its own worker (research, summarise, draft, plan),
 * saves the result to the workspace, and notifies the user when it is done.
 * The user keeps talking to Jarvis meanwhile — the delegated work does not
 * block the chat. Honest scope: one background worker per task, using the
 * same Gemini brain; it is not a swarm of independent agents.
 */
object TaskDelegator {
    fun delegate(ctx: Context, task: String, cb: (String) -> Unit) {
        if (task.isBlank()) { cb("Task-ta bolo — ki korte hobe?"); return }
        val slug = task.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "task" }
        val name = "delegated/$slug-${System.currentTimeMillis()}.md"
        BackgroundWorker.run(ctx, "delegate", {
            val result = GeminiChat.quick(
                task,
                "You are Jarvis's background research agent. Do the task thoroughly and write the result as clear Markdown with headings. Be complete but concise."
            ) ?: "Delegated task-ta sesh korte parlam na (Gemini reply dey ni)."
            val f = File(ctx.getExternalFilesDir(null), "workspace/$name").apply { parentFile?.mkdirs(); writeText("# Delegated task\n\n**Task:** $task\n\n$result\n") }
            BackgroundWorker.notifyDone(ctx, "Jarvis — task done", "$task → workspace/$name")
            "🤖 Task delegate korlam — background-e kaj cholche. Sesh hole notification debe, ar result thakbe workspace/$name-e."
        }, cb)
    }
}
