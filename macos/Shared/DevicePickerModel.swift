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

    /// Stage files + hand off to the main OmniNode.app Multi Copy stack (same as in-app send).
    public func send() async -> Bool {
        guard canSend else { return false }
        isSending = true
        errorMessage = nil
        statusMessage = "Handing off to OmniNode…"
        let jobId = UUID().uuidString
        do {
            let deviceIds = selectedDevices.map(\.deviceId)
            let stagedPaths = try OmniNodeSendHandoff.stageFiles(fileURLs, jobId: jobId)
            try OmniNodeSendHandoff.writePendingJob(
                id: jobId,
                filePaths: stagedPaths,
                deviceIds: deviceIds
            )
            guard OmniNodeSendHandoff.openMainApp(jobId: jobId) else {
                throw OmniNodeSendHandoff.HandoffError.mainAppDidNotOpen
            }
            statusMessage = "Sending via OmniNode…"
            let finished = try await OmniNodeSendHandoff.waitForCompletion(jobId: jobId)
            if finished.status == OmniNodeSendJob.statusFailed {
                throw OmniNodeSendHandoff.HandoffError.failed(
                    finished.message ?? "Send failed"
                )
            }
            statusMessage = finished.message
                ?? "Sent \(stagedPaths.count) item(s) to \(deviceIds.count) device(s)."
            isSending = false
            return true
        } catch {
            errorMessage = error.localizedDescription
            isSending = false
            return false
        }
    }
}
