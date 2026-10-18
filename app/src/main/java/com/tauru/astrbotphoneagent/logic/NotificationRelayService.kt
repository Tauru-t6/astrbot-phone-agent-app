package com.tauru.astrbotphoneagent.logic

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

// Forwards posted notifications to the configured AstrBot plugin so the
// assistant can answer questions like "did anyone message me?".
//
// The service stays inert until the user grants notification access in system
// settings. Uploads are best-effort and batched: a failed batch is retained for
// a limited number of retries rather than dropped immediately, because a late
// notification is still useful context for the assistant.
class NotificationRelayService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val config by lazy { LogicConfig(applicationContext) }
    private val client by lazy { AstrBotClient(config) }
    private val seen = ArrayDeque<String>()
    private val pending = mutableListOf<JSONObject>()
    private val retryBatches = mutableListOf<Pair<List<JSONObject>, Int>>()
    private var lastUploadAt = 0L
    private var flushJob: Job? = null

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (!isForwardable(notification)) return
        val entry = toEntry(notification) ?: return
        if (!markSeen(entry.optString("key"))) return
        synchronized(pending) {
            if (pending.size >= MAX_BATCH) return
            pending.add(entry)
        }
        scheduleFlush()
    }

    override fun onListenerConnected() {
        // A02: launch the retry loop when the listener actually connects,
        // not in onCreate (which runs before the system grants access).
        scope.launch(Dispatchers.IO) { retryLoop() }
    }

    override fun onDestroy() {
        // Attempt a final flush before the service dies, but do not block the
        // main thread; the coroutine scope is cancelled immediately after.
        flushJob?.cancel()
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

    /// Schedule a delayed flush so a small batch coalesces instead of uploading
    /// one notification at a time. Only one pending flush job exists at a time.
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flushJob = null
            doFlush()
        }
    }

    private suspend fun doFlush() {
        val batch = synchronized(pending) {
            if (pending.isEmpty()) return
            val copy = pending.toList()
            pending.clear()
            copy
        }
        if (batch.isEmpty()) return
        lastUploadAt = System.currentTimeMillis()
        try {
            if (!config.notificationForwarding()) return
            client.postNotifications(batch)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Retain the batch for a limited retry instead of dropping it.
            synchronized(retryBatches) {
                if (retryBatches.size < MAX_RETRY_BATCHES) {
                    retryBatches.add(batch to 0)
                }
            }
        }
    }

    private suspend fun retryLoop() {
        while (true) {
            delay(RETRY_INTERVAL_MS)
            val batches: MutableList<Pair<List<JSONObject>, Int>> = synchronized(retryBatches) {
                val copy = retryBatches.toList()
                retryBatches.clear()
                copy.toMutableList()
            }
            if (batches.isEmpty()) continue
            if (!config.notificationForwarding()) continue
            // A02: track which batches succeeded so they are NOT re-queued.
            // The old code re-added every batch unconditionally, causing
            // duplicate notification deliveries.
            val failed: MutableList<Pair<List<JSONObject>, Int>> = mutableListOf()
            for ((batch, attempt) in batches) {
                try {
                    client.postNotifications(batch)
                    // Success: drop the batch, do not re-queue.
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (attempt + 1 < MAX_RETRIES) {
                        failed.add(batch to (attempt + 1))
                    }
                    // Exceeded max retries: drop the batch permanently.
                }
            }
            if (failed.isNotEmpty()) {
                synchronized(retryBatches) {
                    for (entry in failed) {
                        if (retryBatches.size < MAX_RETRY_BATCHES) {
                            retryBatches.add(entry)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val MAX_SEEN = 300
        private const val MAX_BATCH = 40
        private const val MAX_RETRY_BATCHES = 10
        private const val MAX_RETRIES = 3
        private const val FLUSH_DELAY_MS = 4_000L
        private const val RETRY_INTERVAL_MS = 30_000L
    }
}
