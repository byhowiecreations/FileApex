import AppKit
import UserNotifications

public typealias FriendsNotificationOpen = @convention(c) (UnsafePointer<CChar>) -> Void

private final class FriendsNotifications: NSObject, UNUserNotificationCenterDelegate {
    static let shared = FriendsNotifications()
    var onOpen: FriendsNotificationOpen?
    private var pending = [String: UUID]()

    func post(id: String, title: String, body: String) {
        let token = UUID()
        pending[id] = token
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            DispatchQueue.main.async {
                guard self.pending[id] == token else { return }
                guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else {
                    self.pending.removeValue(forKey: id)
                    return
                }
                let content = UNMutableNotificationContent()
                content.title = title
                content.body = body
                content.sound = .default
                center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil)) { error in
                    if let error = error as NSError? {
                        NSLog("Friends notification failed (%@:%ld)", error.domain, error.code)
                    }
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 120) {
                    if self.pending[id] == token { self.remove(id: id) }
                }
            }
        }
    }

    func remove(id: String) {
        pending.removeValue(forKey: id)
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [id])
        center.removeDeliveredNotifications(withIdentifiers: [id])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let id = response.notification.request.identifier
        DispatchQueue.main.async {
            if self.pending.removeValue(forKey: id) != nil, response.actionIdentifier == UNNotificationDefaultActionIdentifier {
                id.withCString { self.onOpen?($0) }
            }
            completionHandler()
        }
    }
}

@_cdecl("fileapex_friends_notifications_install")
public func installFriendsNotifications(_ callback: @escaping FriendsNotificationOpen) -> Int32 {
    guard Bundle.main.bundleIdentifier != nil else { return 0 }
    DispatchQueue.main.async {
        FriendsNotifications.shared.onOpen = callback
        UNUserNotificationCenter.current().delegate = FriendsNotifications.shared
    }
    return 1
}

@_cdecl("fileapex_friends_notifications_permission")
public func requestFriendsNotificationPermission() {
    DispatchQueue.main.async {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, error in
            if let error = error as NSError? { NSLog("Friends notification permission failed (%@:%ld)", error.domain, error.code) }
        }
    }
}

@_cdecl("fileapex_friends_notifications_post")
public func postFriendsNotification(_ id: UnsafePointer<CChar>, _ title: UnsafePointer<CChar>, _ body: UnsafePointer<CChar>) {
    let key = String(cString: id), heading = String(cString: title), text = String(cString: body)
    DispatchQueue.main.async { FriendsNotifications.shared.post(id: key, title: heading, body: text) }
}

@_cdecl("fileapex_friends_notifications_remove")
public func removeFriendsNotification(_ id: UnsafePointer<CChar>) {
    let key = String(cString: id)
    DispatchQueue.main.async { FriendsNotifications.shared.remove(id: key) }
}
