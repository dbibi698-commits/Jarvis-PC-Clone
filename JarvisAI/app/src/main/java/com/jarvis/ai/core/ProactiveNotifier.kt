package com.jarvis.ai.core

/**
 * v1.4.0: intelligent predictive notifications — Jarvis learns when you do
 * what (AdaptiveLearning: tool usage by hour) and nudges you once an hour at
 * most, only when the prediction is confident (same tool 3+ times at this
 * hour). Never spams: one notification per hour, never repeats the same tip.
 */
object ProactiveNotifier {

    fun maybeNotify(ctx: android.content.Context) {
        if (!Prefs.proactiveNotifEnabled) return
        val hint = AdaptiveLearning.proactiveHint() ?: return
        val now = System.currentTimeMillis()
        if (now - Prefs.lastProactiveNotif < 3600_000L) return
        if (hint == Prefs.lastProactiveText) return
        Prefs.lastProactiveNotif = now
        Prefs.lastProactiveText = hint
        BackgroundWorker.notifyDone(ctx, "💡 Jarvis mone koriye dilo", hint)
    }
}
