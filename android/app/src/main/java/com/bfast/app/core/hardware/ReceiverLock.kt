package com.bfast.app.core.hardware

import android.util.Log

/**
 * Receiver Lock (Layer 2)
 *
 * Prevents flickering between multiple nearby receivers once a target
 * achieves sufficient BLE confidence. The lock is held for up to
 * [LOCK_DURATION_MS] and refreshed continuously while the receiver
 * remains within range.
 *
 * Once locked:
 *   - Other receivers are rejected.
 *   - The locked receiver's identity is stable for the UI.
 *   - The lock auto-expires if not refreshed (receiver moved away).
 */
class ReceiverLock {

    companion object {
        private const val TAG = "ReceiverLock"

        /** Lock expires after this duration without refresh (ms).
         *  15s: covers the 30s RECEIVER arm window even if BLE has multi-second gaps.
         *  Lock refresh fires on every scan result; 15s handles a ~10s BLE dead-zone. */
        const val LOCK_DURATION_MS = 15000L

        /** Minimum RSSI to acquire a lock — within ~1–2m.
         *  Was -68 (too strict for low-end phones with weaker BLE radios).
         *  -80 dBm ≈ 1-2 meters on most Android devices, including budget hardware. */
        const val MIN_LOCK_RSSI = -80

        /** Minimum consecutive stable readings to acquire lock.
         *  Was 3 — now 2 for faster acquisition on approach. */
        const val MIN_STABILITY_READINGS = 2

        /** Maximum RSSI variance for lock acquisition.
         *  Was 50 (7 dBm stddev²) — now 100 (10 dBm stddev²) for noisier low-end radios. */
        const val MAX_LOCK_VARIANCE = 100.0

        /** Time receiver must be stable before lock can be acquired (ms).
         *  Was 500ms — now 200ms so lock acquires quickly on approach. */
        const val MIN_STABLE_DURATION_MS = 200L

        /** Hysteresis: allow RSSI to dip this much below MIN_LOCK_RSSI before unlocking.
         *  -80 - 12 = -92: practically never unlocks once acquired (correct — let timeout handle it). */
        const val LOCK_HYSTERESIS_DB = 12
    }

    // ── Lock state (volatile for cross-thread visibility) ────────────────
    @Volatile var lockedDeviceId: String? = null
        private set
    @Volatile var lockedDisplayName: String? = null
        private set
    @Volatile var lockedSessionId: String? = null
        private set

    private var lockAcquiredAt: Long = 0L
    private var lastRefreshedAt: Long = 0L

    // ── Stability tracking for lock acquisition ─────────────────────────
    private data class StabilityEntry(val rssi: Int, val timestampMs: Long)
    private val stabilityBuffer = mutableMapOf<String, MutableList<StabilityEntry>>()

    // ════════════════════════════════════════════════════════════════════
    //  Core API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Attempt to lock onto a receiver device.
     *
     * @return true if this device is the locked receiver (newly locked or already locked).
     */
    fun tryLock(
        deviceId: String,
        displayName: String,
        sessionId: String,
        rssi: Int,
        timestampMs: Long
    ): Boolean {
        // Already locked to this device → refresh
        if (lockedDeviceId == deviceId) {
            return refresh(deviceId, rssi, timestampMs)
        }

        // Locked to a DIFFERENT device → reject unless lock expired
        if (lockedDeviceId != null && !isLockExpired(timestampMs)) {
            return false
        }

        // Not locked — attempt to acquire
        if (rssi < MIN_LOCK_RSSI) return false

        // Track stability
        val entries = stabilityBuffer.getOrPut(deviceId) { mutableListOf() }
        entries.add(StabilityEntry(rssi, timestampMs))

        // Evict entries older than 3 seconds
        entries.removeAll { timestampMs - it.timestampMs > 3000L }

        // Need enough readings
        if (entries.size < MIN_STABILITY_READINGS) return false

        // Check that we've been stable for long enough
        val firstEntry = entries.first()
        if (timestampMs - firstEntry.timestampMs < MIN_STABLE_DURATION_MS) return false

        // Check RSSI variance
        val mean = entries.map { it.rssi.toDouble() }.average()
        val variance = entries.map { val d = it.rssi - mean; d * d }.average()
        if (variance > MAX_LOCK_VARIANCE) return false

        // ═══ All conditions met — ACQUIRE LOCK ═══
        lockedDeviceId = deviceId
        lockedDisplayName = displayName
        lockedSessionId = sessionId
        lockAcquiredAt = timestampMs
        lastRefreshedAt = timestampMs

        // Clear stability buffers for other devices
        stabilityBuffer.keys.retainAll { it == deviceId }

        Log.i(TAG, "Receiver LOCKED: $displayName ($deviceId) " +
                "RSSI=$rssi variance=${variance.toInt()} readings=${entries.size}")
        return true
    }

    /**
     * Refresh the lock for the currently locked device.
     * Extends the lock duration. Returns false and unlocks if RSSI drops too low.
     */
    fun refresh(deviceId: String, rssi: Int, timestampMs: Long): Boolean {
        if (lockedDeviceId != deviceId) return false

        if (rssi < MIN_LOCK_RSSI - LOCK_HYSTERESIS_DB) {
            // Device moved too far — unlock
            Log.d(TAG, "Receiver LOST: RSSI=$rssi < ${MIN_LOCK_RSSI - LOCK_HYSTERESIS_DB}")
            unlock()
            return false
        }

        lastRefreshedAt = timestampMs
        return true
    }

    /** Whether a receiver is currently locked and the lock hasn't expired. */
    fun isLocked(): Boolean {
        return lockedDeviceId != null && !isLockExpired(System.currentTimeMillis())
    }

    /** How long the current lock has been held (ms). Returns 0 if not locked. */
    fun lockDurationMs(): Long {
        if (!isLocked()) return 0L
        return System.currentTimeMillis() - lockAcquiredAt
    }

    /** Explicitly release the lock and clear all stability state. */
    fun unlock() {
        if (lockedDeviceId != null) {
            Log.d(TAG, "Receiver UNLOCKED: $lockedDisplayName ($lockedDeviceId)")
        }
        lockedDeviceId = null
        lockedDisplayName = null
        lockedSessionId = null
        lockAcquiredAt = 0L
        lastRefreshedAt = 0L
        stabilityBuffer.clear()
    }

    private fun isLockExpired(now: Long): Boolean {
        return (now - lastRefreshedAt) > LOCK_DURATION_MS
    }
}
