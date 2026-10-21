import AppKit
import UniformTypeIdentifiers

/// What both Share extensions do with the items macOS hands them: turn a provider into a URL, keep the
/// security-scoped grant open while the extension works, and release it when the extension finishes.
final class ExtensionAttachmentAccess {
    private var scopedURLs: [URL] = []

    /// The first type in [typeIds] the provider can deliver as a URL. With [requireFileURL] a web link is skipped.
    func url(from provider: NSItemProvider, typeIds: [String], requireFileURL: Bool, logPrefix: String) async -> URL? {
        for typeId in typeIds where provider.hasItemConformingToTypeIdentifier(typeId) {
            guard let url = await Self.loadURL(from: provider, typeId: typeId, logPrefix: logPrefix) else { continue }
            if requireFileURL && !url.isFileURL { continue }
            if url.startAccessingSecurityScopedResource() {
                scopedURLs.append(url)
            }
            return url
        }
        return nil
    }

    static func loadURL(from provider: NSItemProvider, typeId: String, logPrefix: String) async -> URL? {
        await withCheckedContinuation { continuation in
            provider.loadItem(forTypeIdentifier: typeId, options: nil) { item, error in
                if let error {
                    NSLog("\(logPrefix) load failed: \(error.localizedDescription)")
                    continuation.resume(returning: nil)
                } else if let url = item as? URL {
                    continuation.resume(returning: url)
                } else if let data = item as? Data, let url = URL(dataRepresentation: data, relativeTo: nil) {
                    continuation.resume(returning: url)
                } else if let string = item as? String, let url = URL(string: string) {
                    continuation.resume(returning: url)
                } else {
                    continuation.resume(returning: nil)
                }
            }
        }
    }

    func releaseAll() {
        scopedURLs.forEach { $0.stopAccessingSecurityScopedResource() }
        scopedURLs.removeAll()
    }

    /// Ends the extension: completes the request, or cancels it with a user-cancelled error.
    func finish(_ context: NSExtensionContext?, errorDomain: String, success: Bool) {
        releaseAll()
        if success {
            context?.completeRequest(returningItems: nil, completionHandler: nil)
        } else {
            let error = NSError(
                domain: errorDomain,
                code: NSUserCancelledError,
                userInfo: [NSLocalizedDescriptionKey: AppCopy.shared.t("cancelled")]
            )
            context?.cancelRequest(withError: error)
        }
    }
}
