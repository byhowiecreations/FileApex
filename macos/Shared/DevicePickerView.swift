import SwiftUI

/// Compact device roster sheet shared by Finder Sync and the Share Extension.
public struct DevicePickerView: View {
    @ObservedObject private var model: DevicePickerModel
    private let title: String
    private let onFinished: (Bool) -> Void

    public init(
        model: DevicePickerModel,
        title: String = "Send with OmniNode",
        onFinished: @escaping (Bool) -> Void
    ) {
        self.model = model
        self.title = title
        self.onFinished = onFinished
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.headline)
            Text(model.statusMessage)
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)

            if model.isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, minHeight: 120)
            } else if model.devices.isEmpty {
                Text("Pair devices in the OmniNode app, then try again.")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 120, alignment: .center)
            } else {
                List {
                    ForEach(model.devices) { device in
                        Button {
                            model.toggle(device.deviceId)
                        } label: {
                            HStack {
                                Image(systemName: model.selectedIds.contains(device.deviceId)
                                      ? "checkmark.circle.fill"
                                      : "circle")
                                    .foregroundStyle(
                                        model.selectedIds.contains(device.deviceId)
                                        ? Color.accentColor
                                        : Color.secondary
                                    )
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(device.deviceName)
                                        .foregroundStyle(.primary)
                                    Text("\(device.lastKnownIp):\(device.port)")
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .listStyle(.inset)
                .frame(minHeight: 180)
            }

            if let error = model.errorMessage {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .fixedSize(horizontal: false, vertical: true)
            }

            HStack {
                Button("Cancel") {
                    onFinished(false)
                }
                .keyboardShortcut(.cancelAction)
                .disabled(model.isSending)

                Spacer()

                Button {
                    Task {
                        let ok = await model.send()
                        if ok {
                            onFinished(true)
                        }
                    }
                } label: {
                    if model.isSending {
                        ProgressView()
                            .controlSize(.small)
                            .padding(.horizontal, 8)
                    } else {
                        Text("Send")
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(!model.canSend)
            }
        }
        .padding(16)
        .frame(minWidth: 360, minHeight: 320)
        .onAppear { model.reload() }
    }
}
