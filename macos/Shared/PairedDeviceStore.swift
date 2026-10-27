import Foundation

/// One paired device the share sheet can send to. The running FileApex app owns the database and tells us only this.
public struct PairedDevice: Identifiable, Hashable, Sendable {
    public var id: String { deviceId }
    public let deviceId: String
    public let deviceName: String

    public init(deviceId: String, deviceName: String) {
        self.deviceId = deviceId
        self.deviceName = deviceName
    }
}

/// Share sheet includes a paired row only when the app already marked that device Ready.
public enum ShareRoster {
    public static func isListed(
        isRemoved: Bool,
        deviceId: String,
        readyDeviceIds: Set<String>
    ) -> Bool {
        !isRemoved && readyDeviceIds.contains(deviceId)
    }

    public static func loadReadyDeviceIds() -> Set<String> {
        let url = FileApexPaths.readyDeviceIdsURL
        guard let data = try? Data(contentsOf: url),
              let ids = try? JSONDecoder().decode([String].self, from: data) else {
            return []
        }
        return Set(ids)
    }
}

/// Asks the running FileApex app for its devices over 127.0.0.1. The extension never opens the database or holds a key.
public enum PairedDeviceStore {
    private struct Row: Decodable {
        let deviceId: String
        let deviceName: String
    }

    private struct LocalApi: Decodable {
        let port: Int
    }

    public static func loadDevices() async throws -> [PairedDevice] {
        guard let port = localApiPort(),
              let url = URL(string: "http://127.0.0.1:\(port)/api/v1/web/devices") else {
            throw StoreError.appNotReachable
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 4
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            throw StoreError.appNotReachable
        }
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let rows = try? JSONDecoder().decode([Row].self, from: data) else {
            throw StoreError.badAnswer
        }
        let ready = ShareRoster.loadReadyDeviceIds()
        return rows
            .filter { ShareRoster.isListed(isRemoved: false, deviceId: $0.deviceId, readyDeviceIds: ready) }
            .map { PairedDevice(deviceId: $0.deviceId, deviceName: $0.deviceName) }
            .sorted { $0.deviceName.localizedCaseInsensitiveCompare($1.deviceName) == .orderedAscending }
    }

    private static func localApiPort() -> Int? {
        guard let data = try? Data(contentsOf: FileApexPaths.localApiURL),
              let info = try? JSONDecoder().decode(LocalApi.self, from: data),
              (1...65535).contains(info.port) else { return nil }
        return info.port
    }

    public enum StoreError: LocalizedError {
        case appNotReachable
        case badAnswer

        public var errorDescription: String? {
            switch self {
            case .appNotReachable:
                return "Could not reach FileApex. Open the app and try again."
            case .badAnswer:
                return "FileApex did not return its devices."
            }
        }
    }
}
