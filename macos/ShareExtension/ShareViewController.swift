import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Share Extension host that presents the shared DevicePicker and streams uploads.
@objc(ShareViewController)
final class ShareViewController: NSViewController {
    private var hosting: NSHostingController<DevicePickerView>?
    private var securityScopedURLs: [URL] = []

    override func loadView() {
        self.view = NSView(frame: NSRect(x: 0, y: 0, width: 400, height: 380))
    }

    override func viewDidAppear() {
        super.viewDidAppear()
        guard hosting == nil else { return }
        Task { await prepareAndPresent() }
    }

    private func prepareAndPresent() async {
        let urls = await resolveAttachmentURLs()
        await MainActor.run {
            let model = DevicePickerModel(fileURLs: urls)
            let root = DevicePickerView(model: model, title: "Share with OmniNode") { [weak self] success in
                self?.finish(success: success)
            }
            let host = NSHostingController(rootView: root)
            self.hosting = host
            self.addChild(host)
            host.view.translatesAutoresizingMaskIntoConstraints = false
            self.view.addSubview(host.view)
            NSLayoutConstraint.activate([
                host.view.leadingAnchor.constraint(equalTo: self.view.leadingAnchor),
                host.view.trailingAnchor.constraint(equalTo: self.view.trailingAnchor),
                host.view.topAnchor.constraint(equalTo: self.view.topAnchor),
                host.view.bottomAnchor.constraint(equalTo: self.view.bottomAnchor)
            ])
        }
    }

    private func resolveAttachmentURLs() async -> [URL] {
        guard let items = extensionContext?.inputItems as? [NSExtensionItem] else {
            return []
        }
        var urls: [URL] = []
        for item in items {
            guard let attachments = item.attachments else { continue }
            for provider in attachments {
                if let url = await loadFileURL(from: provider) {
                    urls.append(url)
                }
            }
        }
        return urls
    }

    private func loadFileURL(from provider: NSItemProvider) async -> URL? {
        let typeIds = [
            UTType.fileURL.identifier,
            UTType.url.identifier,
            UTType.item.identifier,
            UTType.data.identifier
        ]
        for typeId in typeIds where provider.hasItemConformingToTypeIdentifier(typeId) {
            do {
                let url: URL? = try await withCheckedThrowingContinuation { continuation in
                    provider.loadItem(forTypeIdentifier: typeId, options: nil) { item, error in
                        if let error {
                            continuation.resume(throwing: error)
                            return
                        }
                        if let url = item as? URL {
                            continuation.resume(returning: url)
                        } else if let data = item as? Data,
                                  let url = URL(dataRepresentation: data, relativeTo: nil) {
                            continuation.resume(returning: url)
                        } else if let string = item as? String,
                                  let url = URL(string: string) {
                            continuation.resume(returning: url)
                        } else {
                            continuation.resume(returning: nil)
                        }
                    }
                }
                if let url {
                    if url.startAccessingSecurityScopedResource() {
                        securityScopedURLs.append(url)
                    }
                    return url
                }
            } catch {
                NSLog("OmniNode ShareExtension load failed: \(error.localizedDescription)")
            }
        }
        return nil
    }

    private func finish(success: Bool) {
        securityScopedURLs.forEach { $0.stopAccessingSecurityScopedResource() }
        securityScopedURLs.removeAll()
        if success {
            extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
        } else {
            let error = NSError(
                domain: "com.omninode.ShareExtension",
                code: NSUserCancelledError,
                userInfo: [NSLocalizedDescriptionKey: "Cancelled"]
            )
            extensionContext?.cancelRequest(withError: error)
        }
    }
}
