import Foundation

/// A share destination supplied by the running app. Friend ids have the local-only friend: prefix.
public struct PairedDevice: Identifiable, Hashable, Sendable {
    public var id: String { deviceId }
    public let deviceId: String
    public let deviceName: String

    public init(deviceId: String, deviceName: String) {
        self.deviceId = deviceId
        self.deviceName = deviceName
    }
}

/// Asks the running FileApex app for the devices that are Ready, over 127.0.0.1. The extension never opens the database
/// or holds a key.
public enum PairedDeviceStore {
    public static func loadDevices() async throws -> [PairedDevice] {
        try await FileApexLocalApi.readyDevices().map { PairedDevice(deviceId: $0.deviceId, deviceName: $0.deviceName) }
    }
}
