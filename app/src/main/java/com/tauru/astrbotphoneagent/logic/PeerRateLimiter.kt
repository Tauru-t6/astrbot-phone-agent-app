package com.tauru.astrbotphoneagent.logic

import java.util.ArrayDeque

/** Bounded sliding window using a monotonic clock; idle peers cannot leak forever. */
internal class PeerRateLimiter(
    private val limit: Int = 240,
    private val windowMs: Long = 60_000,
    private val maxPeers: Int = 256,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val peers = linkedMapOf<String, ArrayDeque<Long>>()

    @Synchronized
    fun isLimited(address: String): Boolean {
        val now = clockMs()
        val entries = peers.entries.iterator()
        while (entries.hasNext()) {
            val times = entries.next().value
            while (times.isNotEmpty() && now - times.first >= windowMs) times.removeFirst()
            if (times.isEmpty()) entries.remove()
        }
        // Never evict an active peer and reset its quota just to admit a new one.
        val times = peers[address] ?: if (peers.size >= maxPeers) return true
            else ArrayDeque<Long>().also { peers[address] = it }
        if (times.size >= limit) return true
        times.addLast(now)
        return false
    }
}
