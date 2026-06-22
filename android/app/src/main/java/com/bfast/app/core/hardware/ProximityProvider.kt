package com.bfast.app.core.hardware

/**
 * Common interface for proximity detection paths.
 * Allows graceful fallback from Android 16+ Channel Sounding (BT 6.0)
 * to commodity RSSI-based proximity.
 */
interface ProximityProvider {
    /** The proximity score (0-100) where >= 35 is considered "near". */
    val proximityScore: Float

    /** The stability score (0-100) of the signal. */
    val stabilityScore: Float

    /** The surge in signal strength (used for collision/tap correlation). */
    val rssiSurge: Float

    /** The device ID currently being tracked. */
    val scoredDeviceId: String?

    /** The ranging method currently in use (e.g., "CS", "RSSI"). */
    val rangingMethod: String

    /** Feed a new RSSI/ranging reading to the provider. */
    fun feedReading(deviceId: String, value: Int, timestampMs: Long)

    /** Reset the state for the given device or all devices. */
    fun reset(deviceId: String? = null)
}
