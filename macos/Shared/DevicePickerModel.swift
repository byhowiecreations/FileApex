import Combine
import Foundation
import SwiftUI

@MainActor
public final class DevicePickerModel: ObservableObject {
    @Published public var devices: [PairedDevice] = []
    @Published public var selectedIds: Set<String> = []
    @Published public var statusMessage: String = ""
    @Published public var errorMessage: String?
    @Published public var isSending: Bool = false
    @Published public var isLoading: Bool = false

    /// Original / already-staged file URLs shown for status text.
    public let fileURLs: [URL]

    /// When set, files were copied into Application Support before the picker opened
    /// so the main-app TransferManager can stream real bytes.
    private let preStagedJobId: String?
    private let preStagedPaths: [String]?

    public init(
        fileURLs: [URL],
        preStagedJobId: String? = nil,
        preStagedPaths: [String]? = nil
    ) {
        self.fileURLs = fileURLs
        self.preStagedJobId = preStagedJobId
        self.preStagedPaths = preStagedPaths
    }

    public var selectedDevices: [PairedDevice] {
        devices.filter { selectedIds.contains($0.deviceId) }
    }

    public var canSend: Bool {
        let hasFiles = !(preStagedPaths?.isEmpty ?? true) || !fileURLs.isEmpty
        return !selectedIds.isEmpty && hasFiles && !isSending
    }

    public func reload() {
        isLoading = true
        errorMessage = nil
        do {
            devices = try PairedDeviceStore.loadDevices()
            let fileCount = preStagedPaths?.count ?? fileURLs.count
            if devices.isEmpty {
                statusMessage = "No paired devices. Open FileApex and pair a device first."
            } else {
                statusMessage = "\(fileCount) item(s) · \(devices.count) paired device(s)"
            }
        } catch {
            errorMessage = error.localizedDescription
            devices = []
        }
        isLoading = false
    }

    public func toggle(_ deviceId: String) {
        if selectedIds.contains(deviceId) {
            selectedIds.remove(deviceId)
        } else {
            selectedIds.insert(deviceId)
        }
    }

    /// Hand off to the main FileApex.app TransferManager (Share Extension only entry).
    public func send() async -> Bool {
        guard canSend else { return false }
        isSending = true
        errorMessage = nil
        statusMessage = "Handing off to FileApex…"
        do {
            let deviceIds = selectedDevices.map(\.deviceId)
            let finished = try await FileApexSendHandoff.submitSend(
                sourceURLs: fileURLs,
                deviceIds: deviceIds,
                preStagedJobId: preStagedJobId,
                preStagedPaths: preStagedPaths
            )
            statusMessage = finished.message
                ?? "Sent to \(deviceIds.count) device(s)."
            isSending = false
            return true
        } catch {
            errorMessage = error.localizedDescription
            isSending = false
            return false
        }
    }
}
