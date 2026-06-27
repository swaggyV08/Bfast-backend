package com.bfast.flutter

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private lateinit var uwbHandler:     UwbChannelHandler
    private lateinit var bleAdHandler:   BleAdvertiseHandler
    private lateinit var gattHandler:    GattServerHandler

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val messenger = flutterEngine.dartExecutor.binaryMessenger

        uwbHandler   = UwbChannelHandler(this)
        bleAdHandler = BleAdvertiseHandler(this)
        gattHandler  = GattServerHandler(this)

        MethodChannel(messenger, "com.bfast.app/uwb")
            .setMethodCallHandler(uwbHandler)

        EventChannel(messenger, "com.bfast.app/uwb/events")
            .setStreamHandler(uwbHandler)

        MethodChannel(messenger, "com.bfast.app/ble_advertise")
            .setMethodCallHandler(bleAdHandler)

        MethodChannel(messenger, "com.bfast.app/gatt_server")
            .setMethodCallHandler(gattHandler)

        EventChannel(messenger, "com.bfast.app/gatt_server/events")
            .setStreamHandler(gattHandler)
    }

    override fun onDestroy() {
        super.onDestroy()
        uwbHandler.cleanup()
        bleAdHandler.stopAdvertising()
        gattHandler.cleanup()
    }
}
