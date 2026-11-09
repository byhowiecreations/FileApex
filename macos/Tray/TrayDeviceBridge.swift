import Combine
import Foundation

/// Device row pushed from Kotlin [DesktopMacTrayCoordinator].
public struct TrayDeviceItem: Identifiable, Hashable, Codable, Sendable {
    public var id: String { deviceId }
    public let deviceId: String
    public let name: String
    public let isOnline: Bool
    /// A `DeviceIconKind` name from Kotlin, or "Friend".
    public let iconKind: String
    /// Text key for the device type, used as the tooltip of the type icon.
    public let iconKey: String
    /// Last Check Batteries result such as "82% 14:02", empty when there is none.
    public let batteryText: String
    /// "Good", "Low" or "Critical" for [batteryText], empty when there is none.
    public let batteryTone: String

    enum CodingKeys: String, CodingKey {
        case deviceId = "id"
        case name
        case isOnline
        case iconKind
        case iconKey
        case batteryText
        case batteryTone
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        deviceId = try c.decode(String.self, forKey: .deviceId)
        name = try c.decode(String.self, forKey: .name)
        isOnline = try c.decode(Bool.self, forKey: .isOnline)
        iconKind = try c.decodeIfPresent(String.self, forKey: .iconKind) ?? "AndroidPhone"
        iconKey = try c.decodeIfPresent(String.self, forKey: .iconKey) ?? "icon_android_phone"
        batteryText = try c.decodeIfPresent(String.self, forKey: .batteryText) ?? ""
        batteryTone = try c.decodeIfPresent(String.self, forKey: .batteryTone) ?? ""
    }
}

public final class TrayDeviceBridge: ObservableObject {
    public static let shared = TrayDeviceBridge()
    @Published public private(set) var devices: [TrayDeviceItem] = []
    /// True while Kotlin is asking the devices for their battery. Kotlin owns this state.
    @Published public private(set) var checkingBatteries = false

    private init() {}

    public func setCheckingBatteries(_ value: Bool) {
        let apply = { self.checkingBatteries = value }
        if Thread.isMainThread {
            apply()
        } else {
            DispatchQueue.main.async(execute: apply)
        }
    }

    public func replaceDevices(from json: String) {
        let apply = {
            guard let data = json.data(using: .utf8) else {
                self.devices = []
                return
            }
            let decoded = (try? JSONDecoder().decode([TrayDeviceItem].self, from: data)) ?? []
            if self.devices != decoded {
                self.devices = decoded
            } else {
                self.objectWillChange.send()
            }
        }
        if Thread.isMainThread {
            apply()
        } else {
            DispatchQueue.main.async(execute: apply)
        }
    }
}
