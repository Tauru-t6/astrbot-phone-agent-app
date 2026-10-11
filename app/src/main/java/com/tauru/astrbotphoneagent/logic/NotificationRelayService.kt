package com.tauru.astrbotphoneagent.logic

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

// Forwards posted notifications to the configured AstrBot plugin so the
// assistant can answer questions like "did anyone message me?".
//
// The service stays inert until the user grants notification access in system
// settings. Uploads are best-effort and batched: a failed batch is dropped
// rather than retried, because a stale notification is worse than a missing one.
class NotificationRelayService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val config by lazy { LogicConfig(applicationContext) }
    private val client by lazy { AstrBotClient(config) }
    private val seen = ArrayDeque<String>()
    private val pending = mutableListOf<JSONObject>()
    private var lastUploadAt = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (!isForwardable(notification)) return
        val entry = toEntry(notification) ?: return
        if (!markSeen(entry.optString("key"))) return
        synchronized(pending) {
            if (pending.size >= MAX_BATCH) return
            pending.add(entry)
        }
        flushSoon()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun isForwardable(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        val notification = sbn.notification ?: return false
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        return true
    }

    private fun toEntry(sbn: StatusBarNotification): JSONObject? {
        val extras = sbn.notification?.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().trim()
        val text = (if (big.isNotEmpty()) big else extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()).trim()
        if (title.isEmpty() && text.isEmpty()) return null
        return JSONObject()
            .put("key", sbn.key ?: (sbn.packageName + "|" + sbn.postTime))
            .put("package", sbn.packageName)
            .put("title", title.take(200))
            .put("text", text.take(500))
            .put("posted_at", sbn.postTime / 1000.0)
            .put("is_ongoing", false)
    }

    // Bounded de-duplication so a re-posted notification is not sent twice.
    private fun markSeen(key: String): Boolean {
        if (key.isEmpty()) return false
        synchronized(seen) {
            if (seen.contains(key)) return false
            seen.addLast(key)
            while (seen.size > MAX_SEEN) seen.removeFirst()
        }
        return true
    }

    private fun flushSoon() {
        val now = System.currentTimeMillis()
        if (now - lastUploadAt < UPLOAD_INTERVAL_MS) return
        lastUploadAt = now
        val batch = synchronized(pending) {
            if (pending.isEmpty()) return
            val copy = pending.toList()
            pending.clear()
            copy
        }
        scope.launch {
            try {
                if (!config.notificationForwarding()) return@launch
                client.postNotifications(batch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Best effort: drop the batch and keep listening.
            }
        }
    }

    companion object {
        private const val MAX_SEEN = 300
        private const val MAX_BATCH = 40
        private const val UPLOAD_INTERVAL_MS = 4_000L
    }
}
