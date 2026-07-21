import Combine
import Foundation

/// Device row pushed from Kotlin [DesktopMacTrayCoordinator] (SSOT for roster + online state).
public struct TrayDeviceItem: Identifiable, Hashable, Codable, Sendable {
    public var id: String { deviceId }
    public let deviceId: String
    public let name: String
    public let isOnline: Bool

    enum CodingKeys: String, CodingKey {
        case deviceId = "id"
        case name
        case isOnline
    }
}

public final class TrayDeviceBridge: ObservableObject {
    public static let shared = TrayDeviceBridge()
    @Published public private(set) var devices: [TrayDeviceItem] = []

    private init() {}

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
