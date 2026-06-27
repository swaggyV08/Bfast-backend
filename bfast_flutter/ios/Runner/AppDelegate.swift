import Flutter
import UIKit

@UIApplicationMain
@objc class AppDelegate: FlutterAppDelegate {

    private var uwbHandler: UwbChannelHandler?
    private var bleHandler: BleAdvertiseHandler?

    override func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        GeneratedPluginRegistrant.register(with: self)

        guard let controller = window?.rootViewController as? FlutterViewController else {
            return super.application(application, didFinishLaunchingWithOptions: launchOptions)
        }

        let messenger = controller.binaryMessenger
        uwbHandler = UwbChannelHandler(messenger: messenger)
        bleHandler = BleAdvertiseHandler(messenger: messenger)

        return super.application(application, didFinishLaunchingWithOptions: launchOptions)
    }

    override func applicationWillTerminate(_ application: UIApplication) {
        uwbHandler?.stopRanging()
        bleHandler?.stopAdvertising()
    }
}
