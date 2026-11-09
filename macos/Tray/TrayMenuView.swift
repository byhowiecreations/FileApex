import AppKit
import SwiftUI

/// Layout numbers shared with the Windows tray (DesktopWindowsTrayMenu.kt). A Kotlin test keeps the two copies equal.
enum TrayLayout {
    static let columnWidth: CGFloat = 300
    static let rowHeight: CGFloat = 52
    static let chromeHeight: CGFloat = 150
    static let columnGap: CGFloat = 8
    static let padding: CGFloat = 12
    static let maxColumns = 3
    static let screenMargin: CGFloat = 16
}

/// Columns needed so every row is visible without scrolling.
func trayColumnCount(rowCount: Int, availableHeight: CGFloat) -> Int {
    let usable = availableHeight - TrayLayout.chromeHeight
    let perColumn = usable >= TrayLayout.rowHeight ? Int(usable / TrayLayout.rowHeight) : 1
    let columns = (rowCount + perColumn - 1) / perColumn
    return min(max(columns, 1), TrayLayout.maxColumns)
}

struct TrayMenuView: View {
    @ObservedObject private var deviceBridge = TrayDeviceBridge.shared
    @ObservedObject private var copy = AppCopy.shared
    @State private var selectedDeviceIds: Set<String> = []
    @State private var noteOpen = false
    @State private var noteText = ""

    let onLaunchApp: () -> Void
    let onQuitApp: () -> Void
    let onOpenDropBox: ([String]) -> Void
    let onRefreshDevices: () -> Void
    let onSendClipboard: (String) -> Void
    let onBrowse: (String) -> Void
    let onPostNote: (String) -> Void
    let onCheckBatteries: () -> Void

    private var availableHeight: CGFloat {
        (NSScreen.main?.visibleFrame.height ?? 800) - TrayLayout.screenMargin
    }

    var body: some View {
        let devices = deviceBridge.devices
        let columns = trayColumnCount(rowCount: devices.count, availableHeight: availableHeight)
        VStack(alignment: .leading, spacing: 10) {
            header
            if noteOpen {
                noteComposer
            }
            if devices.isEmpty {
                Text(copy.t("no_paired_devices"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 8)
            } else {
                deviceColumns(devices, columns: columns)
            }
            footer
        }
        .padding(TrayLayout.padding)
        .frame(width: CGFloat(columns) * TrayLayout.columnWidth + CGFloat(columns - 1) * TrayLayout.columnGap + TrayLayout.padding * 2)
        .background(
            LinearGradient(
                colors: [Color.accentColor.opacity(0.14), Color.clear],
                startPoint: .top,
                endPoint: .bottom
            )
        )
        .onAppear {
            selectedDeviceIds.removeAll()
            noteOpen = false
            noteText = ""
            onRefreshDevices()
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            if let icon = NSApp.applicationIconImage {
                Image(nsImage: icon)
                    .resizable()
                    .frame(width: 30, height: 30)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .help(copy.t("tray_app_tip"))
            }
            Text("FileApex")
                .font(.system(size: 17, weight: .bold))
                .lineLimit(1)
                .fixedSize()
                .help(copy.t("tray_app_tip"))
            Spacer(minLength: 4)
            HStack(spacing: 0) {
                headerAction(
                    systemName: "pin",
                    label: copy.t("tray_post"),
                    help: copy.t("tray_post_tip"),
                    action: { noteOpen.toggle() }
                )
                headerAction(systemName: "power", label: copy.t("tray_quit"), help: copy.t("quit_application"), action: onQuitApp)
                headerAction(
                    systemName: "arrow.up.forward.square",
                    label: copy.t("tray_open"),
                    help: copy.t("launch_full_app"),
                    action: onLaunchApp
                )
            }
        }
    }

    private func deviceColumns(_ devices: [TrayDeviceItem], columns: Int) -> some View {
        let perColumn = (devices.count + columns - 1) / columns
        let groups = stride(from: 0, to: devices.count, by: perColumn).map {
            Array(devices[$0..<min($0 + perColumn, devices.count)])
        }
        return HStack(alignment: .top, spacing: TrayLayout.columnGap) {
            ForEach(groups.indices, id: \.self) { index in
                VStack(spacing: 4) {
                    ForEach(groups[index]) { device in
                        DeviceRowView(
                            device: device,
                            isSelected: selectedDeviceIds.contains(device.deviceId),
                            onTap: { isCmdPressed in handleSelection(device: device, isCmdPressed: isCmdPressed) },
                            onSendClipboard: {
                                onSendClipboard(device.deviceId)
                            },
                            onBrowse: {
                                onBrowse(device.deviceId)
                            }
                        )
                    }
                }
                .frame(width: TrayLayout.columnWidth)
            }
        }
    }

    private var footer: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                Button(action: onCheckBatteries) {
                    Label(copy.t("check_batteries"), systemImage: "battery.100")
                        .font(.system(size: 12))
                }
                .buttonStyle(.bordered)
                .controlSize(.regular)
                .disabled(deviceBridge.checkingBatteries)
                .help(copy.t("tray_check_batteries_tip"))
                if selectedDeviceIds.count > 1 {
                    Button(action: openDropBoxForSelected) {
                        Text(copy.t("send_to_n_devices", "\(selectedDeviceIds.count)"))
                            .font(.system(size: 12, weight: .semibold))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.regular)
                    .help(copy.t("tray_send_multi_tip"))
                }
            }
            Text(copy.t("cmd_click_multi_select"))
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
                .help(copy.t("tray_multi_select_tip"))
        }
    }

    private var noteComposer: some View {
        HStack(spacing: 8) {
            TextField(copy.t("tray_post_hint"), text: $noteText)
                .textFieldStyle(.roundedBorder)
                .onSubmit(postNote)
            Button(action: postNote) {
                Text(copy.t("tray_post"))
                    .font(.system(size: 12, weight: .semibold))
            }
            .buttonStyle(.borderedProminent)
            .help(copy.t("tray_post_tip"))
        }
    }

    private func postNote() {
        let text = noteText
        noteText = ""
        noteOpen = false
        onPostNote(text)
    }

    private func headerAction(systemName: String, label: String, help: String, action: @escaping () -> Void) -> some View {
        VStack(spacing: 2) {
            Image(systemName: systemName)
                .font(.system(size: 16, weight: .medium))
            Text(label)
                .font(.system(size: 10))
                .foregroundStyle(.secondary)
        }
        .frame(width: 44)
        .padding(.vertical, 3)
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
    let onSendClipboard: () -> Void
    let onBrowse: () -> Void

    @ObservedObject private var copy = AppCopy.shared
    @State private var isHovered = false

    private var isFriend: Bool { device.iconKind == "Friend" }

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: trayIconSymbol(device.iconKind))
                .font(.system(size: 16))
                .foregroundStyle(device.isOnline ? Color.green : Color.secondary)
                .frame(width: 32, height: 32)
                .background(
                    RoundedRectangle(cornerRadius: 9)
                        .fill(device.isOnline ? Color.green.opacity(0.16) : Color.primary.opacity(0.06))
                )
                .help(copy.t(device.iconKey))
            VStack(alignment: .leading, spacing: 3) {
                Text(device.name)
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
                    .help(copy.t("tray_row_tip", device.name))
                HStack(spacing: 6) {
                    Text(device.isOnline ? copy.t("online") : copy.t("offline"))
                        .font(.system(size: 11))
                        .foregroundStyle(device.isOnline ? Color.secondary : Color.red)
                        .help(copy.t(device.isOnline ? "tray_online_tip" : "tray_offline_tip"))
                    if !device.batteryText.isEmpty {
                        Text(device.batteryText)
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(batteryColor)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 1)
                            .background(Capsule().fill(batteryColor.opacity(0.2)))
                            .help(copy.t("tray_battery_tip"))
                    }
                }
            }
            Spacer(minLength: 4)
            if !isFriend {
                rowIconButton(
                    systemName: "clipboard",
                    help: copy.t(device.isOnline ? "tray_send_clipboard_tip" : "tray_send_clipboard_offline_tip"),
                    action: onSendClipboard
                )
                rowIconButton(
                    systemName: "folder",
                    help: copy.t(device.isOnline ? "tray_browse_tip" : "tray_browse_offline_tip"),
                    action: onBrowse
                )
            }
        }
        .padding(.horizontal, 8)
        .frame(height: TrayLayout.rowHeight - 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .opacity(device.isOnline ? 1 : 0.55)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(rowBackground)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(isSelected ? Color.accentColor : Color.clear, lineWidth: 1)
        )
        .contentShape(Rectangle())
        .onHover { isHovered = $0 }
        .onTapGesture {
            onTap(NSEvent.modifierFlags.contains(.command))
        }
    }

    private func rowIconButton(systemName: String, help: String, action: @escaping () -> Void) -> some View {
        Image(systemName: systemName)
            .font(.system(size: 13))
            .frame(width: 30, height: 30)
            .background(RoundedRectangle(cornerRadius: 8).stroke(Color.primary.opacity(0.18)))
            .contentShape(Rectangle())
            .opacity(device.isOnline ? 1 : 0.35)
            .help(help)
            .onTapGesture {
                if device.isOnline { action() }
            }
    }

    private var batteryColor: Color {
        switch device.batteryTone {
        case "Good": return .green
        case "Low": return .orange
        default: return .red
        }
    }

    private var rowBackground: Color {
        if isSelected { return Color.accentColor.opacity(0.2) }
        if isHovered { return Color.primary.opacity(0.08) }
        return Color.primary.opacity(0.04)
    }
}

/// SF Symbol for a Kotlin `DeviceIconKind` name.
func trayIconSymbol(_ kind: String) -> String {
    switch kind {
    case "MacDesktop": return "laptopcomputer"
    case "WindowsPc", "GenericDesktop", "LinuxServer": return "desktopcomputer"
    case "Docker": return "server.rack"
    case "FoldablePhone": return "rectangle.split.2x1"
    case "FlipPhone": return "iphone.gen1"
    case "AndroidTablet": return "ipad"
    case "Friend": return "person.fill"
    default: return "iphone"
    }
}
