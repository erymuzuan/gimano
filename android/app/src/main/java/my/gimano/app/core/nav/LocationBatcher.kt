package my.gimano.app.core.nav

import my.gimano.app.data.model.LocationPoint

/**
 * Buffers location pings while navigating: one sample every [minIntervalMs] (5 s), sent in
 * batches of at most [maxBatch] (12, per the API contract) or when [maxAgeMs] has passed.
 */
class LocationBatcher(
    private val minIntervalMs: Long = 5_000,
    private val maxBatch: Int = 12,
    private val maxAgeMs: Long = 30_000,
) {
    private val buffer = ArrayList<LocationPoint>()
    private var lastSampleMs = Long.MIN_VALUE / 2
    private var firstInBatchMs = 0L

    /** Returns a batch ready to send, or null. */
    fun offer(point: LocationPoint, nowMs: Long): List<LocationPoint>? {
        if (nowMs - lastSampleMs < minIntervalMs) return null
        lastSampleMs = nowMs
        if (buffer.isEmpty()) firstInBatchMs = nowMs
        buffer += point
        return if (buffer.size >= maxBatch || nowMs - firstInBatchMs >= maxAgeMs) drain() else null
    }

    fun drain(): List<LocationPoint>? {
        if (buffer.isEmpty()) return null
        val out = buffer.take(maxBatch)
        buffer.clear()
        return out
    }

    val size get() = buffer.size
}
