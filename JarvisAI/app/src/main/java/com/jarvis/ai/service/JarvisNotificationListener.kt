package com.jarvis.ai.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jarvis.ai.core.GeminiChat
import com.jarvis.ai.core.Memory
import com.jarvis.ai.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Reads incoming messages (WhatsApp, Messenger, Telegram, Instagram, SMS...) and replies in real time. */
class JarvisNotificationListener : NotificationListenerService() {

    data class Item(val pkg: String, val appLabel: String, val title: String, val text: String, val time: Long,
                    val action: Notification.Action?)

    companion object {
        @Volatile var connected = false
        val recent = java.util.Collections.synchronizedList(mutableListOf<Item>())
        private val lastReplied = mutableMapOf<String, String>()

        fun reply(ctx: Context, from: String, message: String): Boolean {
            val q = from.lowercase()
            val item = recent.lastOrNull { it.action != null && (it.title.lowercase().contains(q) || it.appLabel.lowercase().contains(q) || it.pkg.contains(q)) }
                ?: return false
            return send(ctx, item.action!!, message)
        }

        fun send(ctx: Context, action: Notification.Action, message: String): Boolean = runCatching {
            val ri = action.remoteInputs?.firstOrNull() ?: return false
            val intent = Intent()
            val b = Bundle().apply { putCharSequence(ri.resultKey, message) }
            RemoteInput.addResultsToIntent(arrayOf(ri), intent, b)
            action.actionIntent.send(ctx, 0, intent)
            true
        }.getOrDefault(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return
        val action = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val item = Item(sbn.packageName, label, title, text, System.currentTimeMillis(), action)
        recent.add(item); while (recent.size > 80) recent.removeAt(0)

        if (Prefs.autoReply && action != null && Prefs.apiKey.isNotBlank()) {
            val apps = Prefs.autoReplyApps.split(",").map { it.trim() }
            if (sbn.packageName !in apps) return
            val key = sbn.packageName + "|" + title
            if (lastReplied[key] == text) return
            lastReplied[key] = text
            scope.launch {
                val sys = "You are ${Prefs.assistantName}, replying to chat messages on behalf of the phone owner. " +
                    "Write ONE short, friendly, natural reply in the same language as the message (Bengali/Hindi/English/mixed). " +
                    "Never pretend to be the owner for money, OTPs or passwords. If something needs the owner, say they will reply soon. " +
                    Memory.memoryBlock()
                val reply = if (Prefs.autoReplyTemplate.isNotBlank()) {
                    Prefs.autoReplyTemplate.replace("{name}", title).replace("{message}", text).replace("{app}", label)
                } else GeminiChat.quick("Message from $title on $label:\n\"$text\"\nReply:", sys)?.trim()?.trim('"')
                if (!reply.isNullOrBlank() && send(this@JarvisNotificationListener, action, reply)) {
                    Memory.add("assistant", "Auto-replied to $title ($label): $reply", "auto")
                }
            }
        }
    }
}
