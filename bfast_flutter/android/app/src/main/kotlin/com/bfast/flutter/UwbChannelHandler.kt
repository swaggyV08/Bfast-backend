package com.bfast.flutter

import android.content.Context
import android.os.Build
import android.util.Log
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*

/**
 * Handles UWB ranging via the Jetpack UWB library (androidx.core:core-uwb).
 * On devices without UWB hardware, [isUwbAvailable] returns false and all
 * operations are no-ops so the app degrades gracefully to BLE-only mode.
 */
class UwbChannelHandler(
    private val context: Context
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    companion object {
        private const val TAG = "UwbChannelHandler"
    }

    private var eventSink: EventChannel.EventSink? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var rangingJob: Job? = null

    // Lazy-check UWB availability to avoid crashing on unsupported devices
    private var uwbAvailable: Boolean? = null

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isUwbAvailable" -> result.success(checkUwbAvailability())

            "getLocalToken"  -> {
                if (!checkUwbAvailability()) { result.success(null); return }
                scope.launch {
                    val token = getLocalTokenInternal()
                    withContext(Dispatchers.Main) { result.success(token) }
                }
            }

            "startRanging" -> {
                if (!checkUwbAvailability()) { result.success(null); return }
                val peerToken = call.argument<String>("peerToken")
                if (peerToken == null) {
                    result.error("INVALID_ARGS", "peerToken is required", null); return
                }
                scope.launch {
                    startRangingInternal(peerToken)
                    withContext(Dispatchers.Main) { result.success(null) }
                }
            }

            "stopRanging" -> {
                stopRangingInternal()
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    private fun checkUwbAvailability(): Boolean {
        if (uwbAvailable != null) return uwbAvailable!!
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                uwbAvailable = false
                return false
            }
            // Try to load the UWB manager class — if absent, ClassNotFoundException
            Class.forName("androidx.core.uwb.UwbManager")
            val mgr = androidx.core.uwb.UwbManager.createInstance(context)
            uwbAvailable = true
            true
        } catch (e: Exception) {
            Log.d(TAG, "UWB not available: ${e.message}")
            uwbAvailable = false
            false
        }
    }

    private suspend fun getLocalTokenInternal(): String? {
        return try {
            val manager = androidx.core.uwb.UwbManager.createInstance(context)
            val session = manager.controllerSessionScope()
            session.localAddress.address.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "getLocalToken error: ${e.message}")
            null
        }
    }

    private suspend fun startRangingInternal(peerTokenHex: String) {
        stopRangingInternal()
        rangingJob = scope.launch {
            try {
                val manager = androidx.core.uwb.UwbManager.createInstance(context)
                val peerBytes = peerTokenHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val peerAddress = androidx.core.uwb.UwbAddress(peerBytes)
                val session     = manager.controllerSessionScope()

                val params = androidx.core.uwb.RangingParameters(
                    uwbConfigType  = 1,
                    sessionId      = 0x12345678,
                    subSessionId   = 0,
                    sessionKeyInfo = null,
                    subSessionKeyInfo = null,
                    complexChannel = androidx.core.uwb.UwbComplexChannel(9, 11),
                    peerDevices    = listOf(androidx.core.uwb.UwbDevice.createForAddress(peerBytes)),
                    updateRateType = androidx.core.uwb.RangingParameters.RANGING_UPDATE_RATE_FREQUENT,
                )

                session.prepareSession(params).collect { result ->
                    when (result) {
                        is androidx.core.uwb.RangingResult.RangingResultPosition -> {
                            val dist = result.position.distance?.value?.toDouble()
                            if (dist != null) {
                                withContext(Dispatchers.Main) { eventSink?.success(dist) }
                            }
                        }
                        is androidx.core.uwb.RangingResult.RangingResultPeerDisconnected ->
                            Log.d(TAG, "UWB peer disconnected")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "UWB ranging error: ${e.message}")
            }
        }
    }

    private fun stopRangingInternal() {
        rangingJob?.cancel()
        rangingJob = null
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { eventSink = events }
    override fun onCancel(arguments: Any?) { eventSink = null }

    fun cleanup() {
        stopRangingInternal()
        scope.cancel()
    }
}
