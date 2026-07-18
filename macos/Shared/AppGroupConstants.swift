import Foundation

/// Shared constants for OmniNode macOS app + extensions.
public enum OmniNodeAppGroup {
    public static let identifier = "group.com.omninode"
    public static let mainBundleId = "com.omninode"
    public static let finderSyncBundleId = "com.omninode.FinderSync"
    public static let shareExtensionBundleId = "com.omninode.ShareExtension"
    public static let databaseFileName = "omninode.db"

    /// Absolute path to the App Group SQLite roster used by Room on the main app.
    public static var databaseURL: URL? {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: identifier
        ) else {
            // Unsigned / CLT builds: fall back to the same on-disk path the JVM uses.
            let home = FileManager.default.homeDirectoryForCurrentUser
            return home
                .appendingPathComponent("Library/Group Containers/\(identifier)/Database/\(databaseFileName)")
        }
        return container
            .appendingPathComponent("Database", isDirectory: true)
            .appendingPathComponent(databaseFileName)
    }

    /// Landing folder for files received by this Mac (matches Kotlin `defaultDownloadsDir`).
    public static var downloadsOmniNodeURL: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Downloads/OmniNode", isDirectory: true)
    }
}
