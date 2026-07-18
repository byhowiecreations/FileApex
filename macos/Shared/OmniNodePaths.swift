import Foundation

/// Shared on-disk locations for the OmniNode macOS app and its extensions.
/// Uses Application Support — not App Groups — so ad-hoc builds can share the roster DB.
public enum OmniNodePaths {
    public static let mainBundleId = "com.omninode"
    public static let finderSyncBundleId = "com.omninode.FinderSync"
    public static let shareExtensionBundleId = "com.omninode.ShareExtension"
    public static let databaseFileName = "omninode.db"

    /// `~/Library/Application Support/com.omninode/omninode.db`
    public static var databaseURL: URL {
        let home = FileManager.default.homeDirectoryForCurrentUser
        return home
            .appendingPathComponent("Library/Application Support", isDirectory: true)
            .appendingPathComponent(mainBundleId, isDirectory: true)
            .appendingPathComponent(databaseFileName)
    }

    /// Landing folder for files received by this Mac (matches Kotlin `defaultDownloadsDir`).
    public static var downloadsOmniNodeURL: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Downloads/OmniNode", isDirectory: true)
    }
}
