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
        isLoading = true
        errorMessage = nil
        do {
            devices = try PairedDeviceStore.loadDevices()
            if devices.isEmpty {
                statusMessage = "No paired devices. Open OmniNode and pair a device first."
            } else {
                statusMessage = "\(fileURLs.count) item(s) · \(devices.count) paired device(s)"
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

    public func send() async -> Bool {
        guard canSend else { return false }
        isSending = true
        errorMessage = nil
        do {
            let targets = selectedDevices
            let files = fileURLs
            try await OmniNodeUploadClient.uploadFiles(files, to: targets) { [weak self] message in
                Task { @MainActor in
                    self?.statusMessage = message
                }
            }
            statusMessage = "Sent \(files.count) item(s) to \(targets.count) device(s)."
            isSending = false
            return true
        } catch {
            errorMessage = error.localizedDescription
            isSending = false
            return false
        }
    }
}