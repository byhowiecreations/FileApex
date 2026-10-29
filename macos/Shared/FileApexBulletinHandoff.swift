import AppKit
import Foundation

/// Thin shell for the Bulletin extension: hands the shared text and files to the running app, which posts them.
enum FileApexBulletinHandoff {
    static func submit(sharedText: String?, fileURLs: [URL]) async throws {
        let text = sharedText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let files = FileApexSendHandoff.regularFileURLs(from: fileURLs)
        if text.isEmpty && files.isEmpty {
            throw HandoffError.nothingToPost
        }
        let reply = try await FileApexLocalApi.postBulletin(text: text, paths: files.map(\.path))
        if !reply.ok {
            throw HandoffError.failed(reply.message)
        }
    }

    enum HandoffError: LocalizedError {
        case nothingToPost
        case failed(String)

        var errorDescription: String? {
            switch self {
            case .nothingToPost:
                return AppCopy.shared.t("nothing_to_post_bulletin")
            case .failed(let message):
                return message.isEmpty ? AppCopy.shared.t("could_not_post_bulletin") : message
            }
        }
    }
}
