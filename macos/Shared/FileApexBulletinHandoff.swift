import AppKit
import Foundation

struct FileApexBulletinJob: Codable {
    var id: String
    var sharedText: String?
    var filePaths: [String]
    var status: String
    var message: String?

    static let statusPending = "pending"
    static let statusDone = "done"
    static let statusFailed = "failed"
}

enum FileApexBulletinHandoff {
    static func bulletinJobURL(jobId: String) -> URL? {
        var components = URLComponents()
        components.scheme = "fileapex"
        components.host = "bulletin"
        components.queryItems = [URLQueryItem(name: "job", value: jobId)]
        return components.url
    }

    static func supportDirectory() throws -> URL {
        let dir = FileApexPaths.realUserHomeDirectory
            .appendingPathComponent("Library/Application Support/com.fileapex", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func jobsDirectory() throws -> URL {
        let dir = try supportDirectory().appendingPathComponent("bulletin-jobs", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func jobFileURL(jobId: String) throws -> URL {
        try jobsDirectory().appendingPathComponent("\(jobId).json", isDirectory: false)
    }

    static func writePendingJob(id: String, sharedText: String?, filePaths: [String]) throws {
        let job = FileApexBulletinJob(
            id: id,
            sharedText: sharedText?.trimmingCharacters(in: .whitespacesAndNewlines),
            filePaths: filePaths,
            status: FileApexBulletinJob.statusPending,
            message: nil
        )
        let data = try JSONEncoder().encode(job)
        try data.write(to: jobFileURL(jobId: id), options: .atomic)
    }

    @discardableResult
    static func openMainApp(jobId: String) -> Bool {
        guard let url = bulletinJobURL(jobId: jobId) else { return false }
        if let appURL = NSWorkspace.shared.urlForApplication(withBundleIdentifier: FileApexPaths.mainBundleId) {
            let config = NSWorkspace.OpenConfiguration()
            config.activates = true
            var opened = false
            let lock = NSLock()
            let semaphore = DispatchSemaphore(value: 0)
            NSWorkspace.shared.open([url], withApplicationAt: appURL, configuration: config) { _, error in
                lock.lock()
                opened = (error == nil)
                lock.unlock()
                semaphore.signal()
            }
            _ = semaphore.wait(timeout: .now() + 8)
            lock.lock()
            let result = opened
            lock.unlock()
            if result { return true }
        }
        return NSWorkspace.shared.open(url)
    }

    static func submit(sharedText: String?, fileURLs: [URL]) throws {
        let jobId = UUID().uuidString
        var stagedPaths: [String] = []
        if !fileURLs.isEmpty {
            stagedPaths = try FileApexSendHandoff.stageFiles(fileURLs, jobId: jobId)
        }
        let trimmed = sharedText?.trimmingCharacters(in: .whitespacesAndNewlines)
        if (trimmed ?? "").isEmpty && stagedPaths.isEmpty {
            throw HandoffError.nothingToPost
        }
        try writePendingJob(id: jobId, sharedText: trimmed, filePaths: stagedPaths)
        guard openMainApp(jobId: jobId) else {
            throw HandoffError.mainAppDidNotOpen
        }
    }

    enum HandoffError: LocalizedError {
        case nothingToPost
        case mainAppDidNotOpen

        var errorDescription: String? {
            switch self {
            case .nothingToPost:
                return "Nothing to post on the Bulletin Board."
            case .mainAppDidNotOpen:
                return "Could not open FileApex."
            }
        }
    }
}
