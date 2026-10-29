import AppKit
import Foundation

/// What the running FileApex app answers for the Finder extensions, over 127.0.0.1. Nothing is written to disk for the
/// extensions: the file paths, device ids and note text travel in the request, and every call carries the token the app
/// published in `local-api.json` when it started.
enum FileApexLocalApi {
    static let tokenHeader = "X-FileApex-Local-Token"

    struct Endpoint {
        let port: Int
        let token: String
    }

    struct Reply: Decodable {
        let ok: Bool
        let message: String
    }

    struct DeviceRow: Decodable {
        let deviceId: String
        let deviceName: String
        let ready: Bool
    }

    private struct Info: Decodable {
        let port: Int
        let token: String
    }

    private struct SendBody: Encodable {
        let paths: [String]
        let deviceIds: [String]
    }

    private struct BulletinBody: Encodable {
        let text: String
        let paths: [String]
    }

    enum ApiError: LocalizedError {
        case appNotReachable
        case refused
        case badAnswer
        case timeout

        var errorDescription: String? {
            switch self {
            case .appNotReachable, .refused:
                return AppCopy.shared.t("handoff_open_failed")
            case .badAnswer:
                return AppCopy.shared.t("send_failed")
            case .timeout:
                return AppCopy.shared.t("handoff_timeout")
            }
        }
    }

    static func readEndpoint() -> Endpoint? {
        guard let data = try? Data(contentsOf: FileApexPaths.localApiURL),
              let info = try? JSONDecoder().decode(Info.self, from: data),
              (1...65535).contains(info.port), !info.token.isEmpty else { return nil }
        return Endpoint(port: info.port, token: info.token)
    }

    /// The endpoint of a running app. A file left by an app that has quit does not answer, so the app is opened and the
    /// file read again until it does.
    static func endpoint(openingTheAppIfNeeded: Bool = true) async throws -> Endpoint {
        if let endpoint = readEndpoint(), await answers(endpoint) { return endpoint }
        guard openingTheAppIfNeeded, openMainApp() else { throw ApiError.appNotReachable }
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            try await Task.sleep(nanoseconds: 400_000_000)
            if let endpoint = readEndpoint(), await answers(endpoint) { return endpoint }
        }
        throw ApiError.appNotReachable
    }

    /// The devices the app marks Ready, sorted by name.
    static func readyDevices() async throws -> [DeviceRow] {
        let endpoint = try await endpoint()
        return try await fetchDevices(endpoint, timeout: 4)
            .filter(\.ready)
            .sorted { $0.deviceName.localizedCaseInsensitiveCompare($1.deviceName) == .orderedAscending }
    }

    static func send(paths: [String], deviceIds: [String]) async throws -> Reply {
        let endpoint = try await endpoint()
        let body = try JSONEncoder().encode(SendBody(paths: paths, deviceIds: deviceIds))
        return try await post(endpoint, path: "/api/v1/local/send", body: body)
    }

    static func postBulletin(text: String, paths: [String]) async throws -> Reply {
        let endpoint = try await endpoint()
        let body = try JSONEncoder().encode(BulletinBody(text: text, paths: paths))
        return try await post(endpoint, path: "/api/v1/local/bulletin", body: body)
    }

    private static func answers(_ endpoint: Endpoint) async -> Bool {
        (try? await fetchDevices(endpoint, timeout: 2)) != nil
    }

    private static func fetchDevices(_ endpoint: Endpoint, timeout: TimeInterval) async throws -> [DeviceRow] {
        let data = try await call(endpoint, path: "/api/v1/local/devices", method: "GET", body: nil, timeout: timeout)
        guard let rows = try? JSONDecoder().decode([DeviceRow].self, from: data) else { throw ApiError.badAnswer }
        return rows
    }

    private static func post(_ endpoint: Endpoint, path: String, body: Data) async throws -> Reply {
        let data = try await call(endpoint, path: path, method: "POST", body: body, timeout: 600)
        guard let reply = try? JSONDecoder().decode(Reply.self, from: data) else { throw ApiError.badAnswer }
        return reply
    }

    private static func call(_ endpoint: Endpoint, path: String, method: String, body: Data?, timeout: TimeInterval) async throws -> Data {
        guard let url = URL(string: "http://127.0.0.1:\(endpoint.port)\(path)") else { throw ApiError.badAnswer }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = timeout
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue(endpoint.token, forHTTPHeaderField: tokenHeader)
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch let error as URLError where error.code == .timedOut {
            throw ApiError.timeout
        } catch {
            throw ApiError.appNotReachable
        }
        switch (response as? HTTPURLResponse)?.statusCode {
        case 200: return data
        case 401, 403: throw ApiError.refused
        default: throw ApiError.badAnswer
        }
    }

    /// Opens FileApex.app, or reports that it could not.
    @discardableResult
    static func openMainApp() -> Bool {
        guard let appURL = NSWorkspace.shared.urlForApplication(withBundleIdentifier: FileApexPaths.mainBundleId) else {
            return false
        }
        let config = NSWorkspace.OpenConfiguration()
        config.activates = false
        let lock = NSLock()
        var opened = false
        let semaphore = DispatchSemaphore(value: 0)
        NSWorkspace.shared.openApplication(at: appURL, configuration: config) { _, error in
            lock.lock()
            opened = (error == nil)
            if let error {
                NSLog("FileApexLocalApi: open main app failed, \(error.localizedDescription)")
            }
            lock.unlock()
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + 8)
        lock.lock()
        defer { lock.unlock() }
        return opened
    }
}

/// Thin shell for the Share extension: names the files the user picked and asks the app to send them. The app reads
/// the files where they are; nothing is copied.
enum FileApexSendHandoff {
    /// Regular files only, matches main-app Multi Copy (directories are not sent).
    static func regularFileURLs(from urls: [URL]) -> [URL] {
        urls.compactMap { url in
            let resolved = url.resolvingSymlinksInPath().standardizedFileURL
            var isDir: ObjCBool = false
            guard FileManager.default.fileExists(atPath: resolved.path, isDirectory: &isDir),
                  !isDir.boolValue else {
                return nil
            }
            return resolved
        }
    }

    static func submitSend(sourceURLs: [URL], deviceIds: [String]) async throws -> FileApexLocalApi.Reply {
        guard !deviceIds.isEmpty else {
            throw HandoffError.failed(AppCopy.shared.t("select_destination_device"))
        }
        let files = regularFileURLs(from: sourceURLs)
        guard !files.isEmpty else {
            throw HandoffError.noSendableFiles
        }
        let reply = try await FileApexLocalApi.send(paths: files.map(\.path), deviceIds: deviceIds)
        if !reply.ok {
            throw HandoffError.failed(reply.message.isEmpty ? AppCopy.shared.t("send_failed") : reply.message)
        }
        return reply
    }

    enum HandoffError: LocalizedError {
        case failed(String)
        case noSendableFiles

        var errorDescription: String? {
            switch self {
            case .failed(let message):
                return message
            case .noSendableFiles:
                return AppCopy.shared.t("handoff_no_files")
            }
        }
    }
}
