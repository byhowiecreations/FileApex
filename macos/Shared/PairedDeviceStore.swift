import Foundation
import SQLite3

/// One paired peer row from Room's `paired_devices` table.
public struct PairedDevice: Identifiable, Hashable, Sendable {
    public var id: String { deviceId }
    public let deviceId: String
    public let deviceName: String
    public let lastKnownIp: String
    public let port: Int
    public let publicKeyHash: String
    public let rootPath: String

    public init(
        deviceId: String,
        deviceName: String,
        lastKnownIp: String,
        port: Int,
        publicKeyHash: String,
        rootPath: String
    ) {
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.lastKnownIp = lastKnownIp
        self.port = port
        self.publicKeyHash = publicKeyHash
        self.rootPath = rootPath
    }
}

/// Read-only access to the App Group `omninode.db` roster (Room schema v2).
public enum PairedDeviceStore {
    public static func loadDevices() throws -> [PairedDevice] {
        guard let dbURL = OmniNodeAppGroup.databaseURL else {
            throw StoreError.containerUnavailable
        }
        let path = dbURL.path
        guard FileManager.default.fileExists(atPath: path) else {
            return []
        }

        var db: OpaquePointer?
        let openFlags = SQLITE_OPEN_READONLY | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(path, &db, openFlags, nil) == SQLITE_OK, let db else {
            throw StoreError.openFailed(String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_close(db) }

        let sql = """
            SELECT deviceId, deviceName, lastKnownIp, port, publicKeyHash, rootPath
            FROM paired_devices
            ORDER BY deviceName COLLATE NOCASE ASC
            """
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else {
            throw StoreError.prepareFailed(String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(statement) }

        var devices: [PairedDevice] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            devices.append(
                PairedDevice(
                    deviceId: columnText(statement, 0),
                    deviceName: columnText(statement, 1),
                    lastKnownIp: columnText(statement, 2),
                    port: Int(sqlite3_column_int(statement, 3)),
                    publicKeyHash: columnText(statement, 4),
                    rootPath: columnText(statement, 5)
                )
            )
        }
        return devices
    }

    private static func columnText(_ statement: OpaquePointer, _ index: Int32) -> String {
        guard let cString = sqlite3_column_text(statement, index) else { return "" }
        return String(cString: cString)
    }

    public enum StoreError: LocalizedError {
        case containerUnavailable
        case openFailed(String)
        case prepareFailed(String)

        public var errorDescription: String? {
            switch self {
            case .containerUnavailable:
                return "App Group container is unavailable."
            case .openFailed(let message):
                return "Could not open omninode.db: \(message)"
            case .prepareFailed(let message):
                return "Could not query paired_devices: \(message)"
            }
        }
    }
}
