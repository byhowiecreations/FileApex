import AppKit
import Foundation

/// Job handoff file written by Finder Sync / Share for the main OmniNode.app to execute
/// via `FileTransferService.multiCopyToDevices` (same path as in-app Multi Copy).
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

enum OmniNodeSendHandoff {
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

    /// Copy security-scoped / extension-temp files into Application Support for the main app.
    static func stageFiles(_ urls: [URL], jobId: String) throws -> [String] {
        let staging = try stagingDirectory(jobId: jobId)
        var paths: [String] = []
        let fm = FileManager.default
        for url in urls {
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
            if fm.fileExists(atPath: dest.path) {
                try fm.removeItem(at: dest)
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

    static func readJob(id: String) throws -> OmniNodeSendJob {
        let data = try Data(contentsOf: try jobFileURL(jobId: id))
        return try JSONDecoder().decode(OmniNodeSendJob.self, from: data)
    }

    /// Open the main app; it runs Multi Copy for this job id.
    @discardableResult
    static func openMainApp(jobId: String) -> Bool {
        guard let url = URL(string: "omninode://send?job=\(jobId)") else { return false }
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

    enum HandoffError: LocalizedError {
        case timeout
        case mainAppDidNotOpen
        case failed(String)

        var errorDescription: String? {
            switch self {
            case .timeout:
                return "Timed out waiting for OmniNode to finish sending."
            case .mainAppDidNotOpen:
                return "Could not open OmniNode. Install it in /Applications and try again."
            case .failed(let message):
                return message
            }
        }
    }
}
