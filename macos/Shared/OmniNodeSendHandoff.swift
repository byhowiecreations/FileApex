import AppKit
import Foundation

/// Job handoff file written by Finder Sync / Share for the main OmniNode.app.
/// The main app runs [TransferManager.sendLocalPathsToDeviceIds] — same path as in-app Multi Copy.
struct OmniNodeSendJob: Codable {
    var id: String
    var filePaths: [String]
    var deviceIds: [String]
    var status: String
    var message: String?

    static let statusPending = "pending"
    static let statusRunning = "running"
    static let statusDone = "done"
    static let statusFailed = "failed"
}

/// Thin shell for macOS extensions. Never starts transfers itself — only stages files,
/// writes a pending job, and opens the main app with a normalized `omninode://send?job=` URI.
enum OmniNodeSendHandoff {
    /// Canonical deep link — identical for Finder Sync and Share Extension.
    static func sendJobURL(jobId: String) -> URL? {
        var components = URLComponents()
        components.scheme = "omninode"
        components.host = "send"
        components.queryItems = [URLQueryItem(name: "job", value: jobId)]
        return components.url
    }

    static func supportDirectory() throws -> URL {
        let dir = OmniNodePaths.realUserHomeDirectory
            .appendingPathComponent("Library/Application Support/com.omninode", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func jobsDirectory() throws -> URL {
        let dir = try supportDirectory().appendingPathComponent("send-jobs", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func stagingDirectory(jobId: String) throws -> URL {
        let dir = try supportDirectory()
            .appendingPathComponent("send-staging", isDirectory: true)
            .appendingPathComponent(jobId, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func jobFileURL(jobId: String) throws -> URL {
        try jobsDirectory().appendingPathComponent("\(jobId).json", isDirectory: false)
    }

    /// Regular files only — matches main-app Multi Copy (directories are not sent).
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

    /// Copy security-scoped / Finder-selected files into Application Support for the main app.
    static func stageFiles(_ urls: [URL], jobId: String) throws -> [String] {
        let files = regularFileURLs(from: urls)
        guard !files.isEmpty else {
            throw HandoffError.noSendableFiles
        }
        let staging = try stagingDirectory(jobId: jobId)
        var paths: [String] = []
        let fm = FileManager.default
        for url in files {
            let accessed = url.startAccessingSecurityScopedResource()
            defer {
                if accessed { url.stopAccessingSecurityScopedResource() }
            }
            var dest = staging.appendingPathComponent(url.lastPathComponent)
            if fm.fileExists(atPath: dest.path) {
                let stem = dest.deletingPathExtension().lastPathComponent
                let ext = dest.pathExtension
                var index = 1
                repeat {
                    let name = ext.isEmpty ? "\(stem) (\(index))" : "\(stem) (\(index)).\(ext)"
                    dest = staging.appendingPathComponent(name)
                    index += 1
                } while fm.fileExists(atPath: dest.path)
            }
            try fm.copyItem(at: url, to: dest)
            paths.append(dest.path)
        }
        return paths
    }

    static func writePendingJob(id: String, filePaths: [String], deviceIds: [String]) throws {
        let job = OmniNodeSendJob(
            id: id,
            filePaths: filePaths,
            deviceIds: deviceIds,
            status: OmniNodeSendJob.statusPending,
            message: nil
        )
        let data = try JSONEncoder().encode(job)
        try data.write(to: try jobFileURL(jobId: id), options: .atomic)
    }

    /**
     Single send entry used by Finder Sync and Share Extension alike:
     stage (unless already staged) → write pending job → open main app → wait for TransferManager.
     */
    static func submitSend(
        sourceURLs: [URL],
        deviceIds: [String],
        preStagedJobId: String? = nil,
        preStagedPaths: [String]? = nil
    ) async throws -> OmniNodeSendJob {
        guard !deviceIds.isEmpty else {
            throw HandoffError.failed("Select at least one destination device")
        }
        let jobId = preStagedJobId ?? UUID().uuidString
        let stagedPaths: [String]
        if let preStagedPaths, !preStagedPaths.isEmpty {
            stagedPaths = preStagedPaths
        } else {
            stagedPaths = try stageFiles(sourceURLs, jobId: jobId)
        }
        try writePendingJob(id: jobId, filePaths: stagedPaths, deviceIds: deviceIds)
        guard openMainApp(jobId: jobId) else {
            throw HandoffError.mainAppDidNotOpen
        }
        let finished = try await waitForCompletion(jobId: jobId)
        if finished.status == OmniNodeSendJob.statusFailed {
            throw HandoffError.failed(finished.message ?? "Send failed")
        }
        return finished
    }

    /// Open `/Applications` OmniNode.app with the normalized send deep link.
    @discardableResult
    static func openMainApp(jobId: String) -> Bool {
        guard let url = sendJobURL(jobId: jobId) else { return false }
        if let appURL = NSWorkspace.shared.urlForApplication(
            withBundleIdentifier: OmniNodePaths.mainBundleId
        ) {
            let config = NSWorkspace.OpenConfiguration()
            config.activates = true
            var opened = false
            let lock = NSLock()
            let semaphore = DispatchSemaphore(value: 0)
            NSWorkspace.shared.open([url], withApplicationAt: appURL, configuration: config) { _, error in
                lock.lock()
                opened = (error == nil)
                if let error {
                    NSLog("OmniNodeSendHandoff: open main app failed — \(error.localizedDescription)")
                }
                lock.unlock()
                semaphore.signal()
            }
            _ = semaphore.wait(timeout: .now() + 8)
            lock.lock()
            let result = opened
            lock.unlock()
            if result { return true }
        }
        // Fallback for ad-hoc / current/ trees where bundle-id lookup fails.
        return NSWorkspace.shared.open(url)
    }

    /// Poll until the main app marks the job done/failed (or timeout).
    static func waitForCompletion(jobId: String, timeoutSeconds: TimeInterval = 600) async throws -> OmniNodeSendJob {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            let job = try readJob(id: jobId)
            if job.status == OmniNodeSendJob.statusDone || job.status == OmniNodeSendJob.statusFailed {
                return job
            }
            try await Task.sleep(nanoseconds: 300_000_000)
        }
        throw HandoffError.timeout
    }

    static func readJob(id: String) throws -> OmniNodeSendJob {
        let data = try Data(contentsOf: try jobFileURL(jobId: id))
        return try JSONDecoder().decode(OmniNodeSendJob.self, from: data)
    }

    enum HandoffError: LocalizedError {
        case timeout
        case mainAppDidNotOpen
        case failed(String)
        case noSendableFiles

        var errorDescription: String? {
            switch self {
            case .timeout:
                return "Timed out waiting for OmniNode to finish sending."
            case .mainAppDidNotOpen:
                return "Could not open OmniNode. Install it in /Applications and try again."
            case .failed(let message):
                return message
            case .noSendableFiles:
                return "Select one or more files (folders are not supported)."
            }
        }
    }
}
