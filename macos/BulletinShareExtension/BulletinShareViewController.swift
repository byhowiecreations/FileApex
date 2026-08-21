import AppKit
import UniformTypeIdentifiers

@objc(BulletinShareViewController)
final class BulletinShareViewController: NSViewController {
    private var securityScopedURLs: [URL] = []

    override func loadView() {
        view = NSView(frame: NSRect(x: 0, y: 0, width: 320, height: 120))
    }

    override func viewDidAppear() {
        super.viewDidAppear()
        Task { await postAndFinish() }
    }

    private struct ResolvedPayload {
        var text: String?
        var fileURLs: [URL]

        var hasContent: Bool {
            !(text?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true) || !fileURLs.isEmpty
        }
    }

    private func postAndFinish() async {
        let payload = await resolvePayload()
        guard payload.hasContent else {
            await MainActor.run { finish(success: false) }
            return
        }
        do {
            try FileApexBulletinHandoff.submit(sharedText: payload.text, fileURLs: payload.fileURLs)
            await MainActor.run { finish(success: true) }
        } catch {
            NSLog("FileApex BulletinShareExtension: \(error.localizedDescription)")
            await MainActor.run { finish(success: false) }
        }
    }

    private func resolvePayload() async -> ResolvedPayload {
        guard let items = extensionContext?.inputItems as? [NSExtensionItem] else {
            return ResolvedPayload(text: nil, fileURLs: [])
        }
        var textParts: [String] = []
        var fileURLs: [URL] = []
        for item in items {
            if let attributed = item.attributedContentText?.string,
               !attributed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                textParts.append(attributed.trimmingCharacters(in: .whitespacesAndNewlines))
            }
            guard let attachments = item.attachments else { continue }
            for provider in attachments {
                if let urlText = await loadURLString(from: provider),
                   !urlText.isEmpty,
                   !textParts.contains(urlText) {
                    textParts.append(urlText)
                }
                if let url = await loadFileURL(from: provider) {
                    fileURLs.append(url)
                }
            }
        }
        let merged = textParts.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        return ResolvedPayload(text: merged.isEmpty ? nil : merged, fileURLs: fileURLs)
    }

    private func loadURLString(from provider: NSItemProvider) async -> String? {
        if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
            if let url = await loadURL(from: provider, typeId: UTType.url.identifier) {
                return url.absoluteString
            }
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            if let text = await loadString(from: provider, typeId: UTType.plainText.identifier) {
                return text
            }
        }
        return nil
    }

    private func loadFileURL(from provider: NSItemProvider) async -> URL? {
        let typeIds = [
            UTType.fileURL.identifier,
            UTType.image.identifier,
            UTType.movie.identifier,
            UTType.item.identifier,
            UTType.data.identifier
        ]
        for typeId in typeIds where provider.hasItemConformingToTypeIdentifier(typeId) {
            if let url = await loadURL(from: provider, typeId: typeId), url.isFileURL {
                if url.startAccessingSecurityScopedResource() {
                    securityScopedURLs.append(url)
                }
                return url
            }
        }
        return nil
    }

    private func loadURL(from provider: NSItemProvider, typeId: String) async -> URL? {
        await withCheckedContinuation { continuation in
            provider.loadItem(forTypeIdentifier: typeId, options: nil) { item, _ in
                if let url = item as? URL {
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

    private func loadString(from provider: NSItemProvider, typeId: String) async -> String? {
        await withCheckedContinuation { continuation in
            provider.loadItem(forTypeIdentifier: typeId, options: nil) { item, _ in
                if let string = item as? String {
                    continuation.resume(returning: string)
                } else if let data = item as? Data, let string = String(data: data, encoding: .utf8) {
                    continuation.resume(returning: string)
                } else {
                    continuation.resume(returning: nil)
                }
            }
        }
    }

    private func finish(success: Bool) {
        securityScopedURLs.forEach { $0.stopAccessingSecurityScopedResource() }
        securityScopedURLs.removeAll()
        if success {
            extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
        } else {
            let error = NSError(
                domain: "com.fileapex.BulletinShareExtension",
                code: NSUserCancelledError,
                userInfo: [NSLocalizedDescriptionKey: "Cancelled"]
            )
            extensionContext?.cancelRequest(withError: error)
        }
    }
}
