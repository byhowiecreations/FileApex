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

    /// The files the user picked, read by the main app where they are.
    public let fileURLs: [URL]

    public init(fileURLs: [URL]) {
        self.fileURLs = fileURLs
    }

    public var selectedDevices: [PairedDevice] {
        devices.filter { selectedIds.contains($0.deviceId) }
    }

    public var canSend: Bool {
        !selectedIds.isEmpty && !fileURLs.isEmpty && !isSending
    }

    public func reload() {
        Task { await load() }
    }

    private func load() async {
        AppCopy.shared.loadFromDisk()
        isLoading = true
        errorMessage = nil
        do {
            devices = try await PairedDeviceStore.loadDevices()
            let fileCount = fileURLs.count
            if devices.isEmpty {
                statusMessage = AppCopy.shared.t("no_paired_open_fileapex")
            } else {
                statusMessage = "\(AppCopy.shared.plural("file_count", count: fileCount)) · \(AppCopy.shared.plural("n_paired_devices", count: devices.count))"
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
        statusMessage = AppCopy.shared.t("handing_off_to_fileapex")
        do {
            let deviceIds = selectedDevices.map(\.deviceId)
            let finished = try await FileApexSendHandoff.submitSend(sourceURLs: fileURLs, deviceIds: deviceIds)
            statusMessage = finished.message.isEmpty
                ? AppCopy.shared.plural("sent_to_n_devices", count: deviceIds.count, "\(deviceIds.count)")
                : finished.message
            isSending = false
            return true
        } catch {
            errorMessage = error.localizedDescription
            isSending = false
            return false
        }
    }
}
