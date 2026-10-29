import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Share Extension host: resolves the picked files, then presents the device picker.
///
/// Triggered from Finder (right-click → Share → FileApex) or the system Share sheet.
/// Never streams bytes itself and copies nothing: [FileApexSendHandoff.submitSend] names the files and the main app’s
/// TransferManager reads them where they are.
@objc(ShareViewController)
final class ShareViewController: NSViewController {
    private var hosting: NSHostingController<DevicePickerView>?
    private let attachments = ExtensionAttachmentAccess()

    override func loadView() {
        self.view = NSView(frame: NSRect(x: 0, y: 0, width: 400, height: 380))
    }

    override func viewDidAppear() {
        super.viewDidAppear()
        guard hosting == nil else { return }
        Task { await prepareAndPresent() }
    }

    private func prepareAndPresent() async {
        AppCopy.shared.loadFromDisk()
        let urls = await resolveAttachmentURLs()
        guard !urls.isEmpty else {
            await MainActor.run {
                self.presentStagingFailure(AppCopy.shared.t("handoff_no_files"))
            }
            return
        }

        await MainActor.run {
            let model = DevicePickerModel(fileURLs: FileApexSendHandoff.regularFileURLs(from: urls))
            let root = DevicePickerView(model: model, title: AppCopy.shared.t("share_with_fileapex")) { [weak self] success in
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

    private func presentStagingFailure(_ message: String) {
        let alert = NSAlert()
        alert.messageText = "FileApex"
        alert.informativeText = message
        alert.alertStyle = .warning
        alert.addButton(withTitle: AppCopy.shared.t("ok"))
        alert.runModal()
        finish(success: false)
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
            UTType.image.identifier,
            UTType.movie.identifier,
            UTType.item.identifier,
            UTType.data.identifier
        ]
        return await attachments.url(
            from: provider,
            typeIds: typeIds,
            requireFileURL: false,
            logPrefix: "FileApex ShareExtension"
        )
    }

    private func finish(success: Bool) {
        attachments.finish(extensionContext, errorDomain: "com.fileapex.ShareExtension", success: success)
    }
}
