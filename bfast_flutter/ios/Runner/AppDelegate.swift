import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate {

    private var gattHandler: GattServerHandler?
    private var uwbHandler:  UwbChannelHandler?
    private var bleHandler:  BleAdvertiseHandler?

    override func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        GeneratedPluginRegistrant.register(with: self)

        guard let controller = window?.rootViewController as? FlutterViewController else {
            return super.application(application, didFinishLaunchingWithOptions: launchOptions)
        }

        let messenger = controller.binaryMessenger

        // GattServerHandler must be initialised first — BleAdvertiseHandler holds
        // a weak reference to it so it can store receiver advertising info.
        let gatt     = GattServerHandler(messenger: messenger)
        gattHandler  = gatt

        uwbHandler   = UwbChannelHandler(messenger: messenger)
        bleHandler   = BleAdvertiseHandler(messenger: messenger, gattHandler: gatt)

        return super.application(application, didFinishLaunchingWithOptions: launchOptions)
    }

    override func applicationWillTerminate(_ application: UIApplication) {
        uwbHandler?.stopRanging()
        bleHandler?.stopAdvertising()
        gattHandler?.cleanup()
    }
}
