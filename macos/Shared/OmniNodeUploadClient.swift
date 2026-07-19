import Foundation

/**
 OmniNode transfer client for App Extensions.

 Mirrors `OmniNodeClient` (Ktor on the JVM main app):
 - GET  /api/v1/identity
 - POST /api/v1/files/upload?targetPath=…

 App Extensions cannot host the JVM/Ktor runtime, so this uses URLSession while
 preserving the same wire protocol, paths, and concurrency model.
 */
public enum OmniNodeUploadClient {
    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 120
        config.timeoutIntervalForResource = 60 * 60
        config.waitsForConnectivity = true
        return URLSession(configuration: config)
    }()

    public struct NodeIdentity: Decodable, Sendable {
        public let deviceId: String
        public let deviceName: String
        public let rootPath: String
        public let port: Int
        public let downloadsPath: String
        public let pinRequired: Bool

        enum CodingKeys: String, CodingKey {
            case deviceId, deviceName, rootPath, port, downloadsPath, pinRequired
        }

        public init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            deviceId = try container.decode(String.self, forKey: .deviceId)
            deviceName = try container.decode(String.self, forKey: .deviceName)
            rootPath = try container.decode(String.self, forKey: .rootPath)
            port = try container.decode(Int.self, forKey: .port)
            downloadsPath = try container.decodeIfPresent(String.self, forKey: .downloadsPath) ?? ""
            pinRequired = try container.decodeIfPresent(Bool.self, forKey: .pinRequired) ?? false
        }
    }

    public static func fetchIdentity(host: String, port: Int) async throws -> NodeIdentity {
        guard let url = URL(string: "http://\(host):\(port)/api/v1/identity") else {
            throw UploadError.invalidURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("OmniNode-macOS-Extension", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await session.data(for: request)
        try throwIfNeeded(response, data: data, context: "identity")
        return try JSONDecoder().decode(NodeIdentity.self, from: data)
    }

    /// Streams a local file to a peer Downloads/OmniNode landing zone (or identity downloadsPath).
    public static func uploadFile(
        host: String,
        port: Int,
        localFileURL: URL,
        destinationRoot: String,
        pin: String? = nil
    ) async throws {
        let fileName = localFileURL.lastPathComponent
        let targetPath = joinPath(destinationRoot, fileName)
        var components = URLComponents(string: "http://\(host):\(port)/api/v1/files/upload")
        var query: [URLQueryItem] = [URLQueryItem(name: "targetPath", value: targetPath)]
        if let pin, !pin.isEmpty {
            query.append(URLQueryItem(name: "pin", value: pin))
        }
        components?.queryItems = query
        guard let url = components?.url else { throw UploadError.invalidURL }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.setValue("OmniNode-macOS-Extension", forHTTPHeaderField: "User-Agent")
        request.setValue("close", forHTTPHeaderField: "Connection")
        if let values = try? localFileURL.resourceValues(forKeys: [.fileSizeKey]),
           let size = values.fileSize {
            request.setValue(String(size), forHTTPHeaderField: "Content-Length")
        }

        let (data, response) = try await session.upload(for: request, fromFile: localFileURL)
        try throwIfNeeded(response, data: data, context: "upload \(fileName)")
    }

    /// Concurrent fan-out of [files] to every selected [devices] (same idea as Multi Copy + Ktor).
    public static func uploadFiles(
        _ files: [URL],
        to devices: [PairedDevice],
        progress: (@Sendable (String) -> Void)? = nil
    ) async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            for device in devices {
                group.addTask {
                    progress?("Contacting \(device.deviceName)…")
                    let identity = try await fetchIdentity(host: device.lastKnownIp, port: device.port)
                    let root = identity.downloadsPath.trimmingCharacters(in: .whitespacesAndNewlines)
                    let destinationRoot: String
                    if root.isEmpty {
                        destinationRoot = fallbackDownloadsPath(rootPath: device.rootPath)
                    } else {
                        destinationRoot = root
                    }
                    for file in files {
                        progress?("Sending \(file.lastPathComponent) → \(device.deviceName)")
                        try await uploadFile(
                            host: device.lastKnownIp,
                            port: device.port,
                            localFileURL: file,
                            destinationRoot: destinationRoot
                        )
                    }
                }
            }
            try await group.waitForAll()
        }
    }

    private static func fallbackDownloadsPath(rootPath: String) -> String {
        let normalized = rootPath.replacingOccurrences(of: "\\", with: "/")
        if normalized.lowercased().contains("/download") {
            return normalized
                .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                .appending("/OmniNode")
        }
        // Peer is likely a Mac — land under their Downloads/OmniNode equivalent path hint.
        return (normalized as NSString).appendingPathComponent("Downloads/OmniNode")
    }

    private static func joinPath(_ root: String, _ name: String) -> String {
        let trimmed = root.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if trimmed.isEmpty { return "/\(name)" }
        if root.hasSuffix("/") { return root + name }
        return "\(root)/\(name)"
    }

    private static func throwIfNeeded(_ response: URLResponse?, data: Data, context: String) throws {
        guard let http = response as? HTTPURLResponse else {
            throw UploadError.badResponse(context)
        }
        guard (200..<300).contains(http.statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? ""
            if http.statusCode == 403 {
                throw UploadError.pinRequired
            }
            throw UploadError.httpStatus(http.statusCode, context, body)
        }
    }

    public enum UploadError: LocalizedError {
        case invalidURL
        case badResponse(String)
        case httpStatus(Int, String, String)
        case pinRequired

        public var errorDescription: String? {
            switch self {
            case .invalidURL:
                return "Invalid peer URL."
            case .badResponse(let context):
                return "No HTTP response (\(context))."
            case .httpStatus(let code, let context, let body):
                return "Transfer failed (\(code)) during \(context). \(body)"
            case .pinRequired:
                return "PIN required — open OmniNode on the destination and unlock, or disable PIN."
            }
        }
    }
}
