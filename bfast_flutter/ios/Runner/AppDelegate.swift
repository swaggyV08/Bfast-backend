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
        let result = super.application(application, didFinishLaunchingWithOptions: launchOptions)

        guard let controller = window?.rootViewController as? FlutterViewController else {
            return result
        }
        let messenger = controller.binaryMessenger

        let gatt    = GattServerHandler(messenger: messenger)
        gattHandler = gatt

        if #available(iOS 14.0, *) {
            uwbHandler = UwbChannelHandler(messenger: messenger)
        }
        bleHandler = BleAdvertiseHandler(messenger: messenger, gattHandler: gatt)

        return result
    }

    override func applicationWillTerminate(_ application: UIApplication) {
        uwbHandler?.stopRanging()
        bleHandler?.stopAdvertising()
        gattHandler?.cleanup()
    }
}
