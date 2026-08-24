import AppKit
import SwiftUI

struct TrayMenuView: View {
    @ObservedObject private var deviceBridge = TrayDeviceBridge.shared
    @ObservedObject private var copy = AppCopy.shared
    @State private var selectedDeviceIds: Set<String> = []

    let onLaunchApp: () -> Void
    let onQuitApp: () -> Void
    let onOpenDropBox: ([String]) -> Void
    let onRefreshDevices: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(copy.t("devices"))
                    .font(.headline)
                Spacer()
                headerIcon(systemName: "power", help: copy.t("quit_application"), action: onQuitApp)
                headerIcon(
                    systemName: "arrow.up.forward.square",
                    help: copy.t("launch_full_app"),
                    action: onLaunchApp
                )
            }
            .padding(.bottom, 4)

            Divider()

            if deviceBridge.devices.isEmpty {
                Text(copy.t("no_paired_devices"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 8)
            } else {
                VStack(spacing: 4) {
                    ForEach(deviceBridge.devices) { device in
                        DeviceRowView(
                            device: device,
                            isSelected: selectedDeviceIds.contains(device.deviceId),
                            onTap: { isCmdPressed in
                                handleSelection(device: device, isCmdPressed: isCmdPressed)
                            }
                        )
                    }
                }
            }

            if selectedDeviceIds.count > 1 {
                Button(action: openDropBoxForSelected) {
                    Text(copy.t("send_to_n_devices", "\(selectedDeviceIds.count)"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .padding(.top, 4)
            } else {
                Text(copy.t("cmd_click_multi_select"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.top, 4)
            }
        }
        .padding(12)
        .fixedSize()
        .onAppear {
            onRefreshDevices()
        }
    }

    @ViewBuilder
    private func headerIcon(systemName: String, help: String, action: @escaping () -> Void) -> some View {
        Image(systemName: systemName)
            .font(.system(size: 14, weight: .medium))
            .foregroundStyle(.primary)
            .frame(width: 28, height: 28)
            .contentShape(Rectangle())
            .help(help)
            .onTapGesture(perform: action)
    }

    private func handleSelection(device: TrayDeviceItem, isCmdPressed: Bool) {
        if isCmdPressed {
            if selectedDeviceIds.contains(device.deviceId) {
                selectedDeviceIds.remove(device.deviceId)
            } else {
                selectedDeviceIds.insert(device.deviceId)
            }
        } else {
            selectedDeviceIds = [device.deviceId]
            openDropBoxForSelected()
        }
    }

    private func openDropBoxForSelected() {
        let ids = Array(selectedDeviceIds)
        guard !ids.isEmpty else { return }
        onOpenDropBox(ids)
        selectedDeviceIds.removeAll()
    }
}

struct DeviceRowView: View {
    let device: TrayDeviceItem
    let isSelected: Bool
    let onTap: (Bool) -> Void

    @ObservedObject private var copy = AppCopy.shared
    @State private var isHovered = false

    var body: some View {
        HStack {
            Image(systemName: "iphone")
                .foregroundStyle(device.isOnline ? Color.green : Color.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(device.name)
                    .font(.subheadline)
                    .bold()
                    .lineLimit(1)
                Text(device.isOnline ? copy.t("online") : copy.t("offline"))
                    .font(.caption)
                    .foregroundStyle(device.isOnline ? Color.secondary : Color.red)
            }
            Spacer()
            if isSelected {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(Color.accentColor)
            }
        }
        .padding(6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 6)
                .fill(rowBackground)
        )
        .contentShape(Rectangle())
        .onHover { isHovered = $0 }
        .onTapGesture {
            onTap(NSEvent.modifierFlags.contains(.command))
        }
    }

    private var rowBackground: Color {
        if isSelected { return Color.accentColor.opacity(0.15) }
        if isHovered { return Color.primary.opacity(0.08) }
        return Color.clear
    }
}
