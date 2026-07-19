import Darwin
import Foundation

/// Shared on-disk locations for the OmniNode macOS app and its extensions.
/// Uses Application Support — not App Groups — so ad-hoc builds can share the roster DB.
public enum OmniNodePaths {
    public static let mainBundleId = "com.omninode"
    public static let shareExtensionBundleId = "com.omninode.ShareExtension"
    public static let databaseFileName = "omninode.db"

    /// Real user home from the passwd database — never the App Extension container home.
    /// `FileManager.homeDirectoryForCurrentUser` / `NSHomeDirectory()` resolve inside
    /// `~/Library/Containers/<bundle-id>/Data` when App Sandbox is on.
    public static var realUserHomeDirectory: URL {
        if let pw = getpwuid(getuid()), let dir = pw.pointee.pw_dir {
            return URL(fileURLWithPath: String(cString: dir), isDirectory: true)
        }
        // Fallbacks that still avoid Containers when possible.
        if let env = ProcessInfo.processInfo.environment["HOME"],
           !env.contains("/Library/Containers/") {
            return URL(fileURLWithPath: env, isDirectory: true)
        }
        return URL(fileURLWithPath: "/Users/\(NSUserName())", isDirectory: true)
    }

    /// Forced global path: `~/Library/Application Support/com.omninode/omninode.db`
    /// Always under the real user home from passwd — never a sandboxed container.
    public static var databaseURL: URL {
        let url = realUserHomeDirectory
            .appendingPathComponent("Library/Application Support", isDirectory: true)
            .appendingPathComponent(mainBundleId, isDirectory: true)
            .appendingPathComponent(databaseFileName, isDirectory: false)
        precondition(
            !url.path.contains("/Library/Containers/"),
            "OmniNodePaths.databaseURL must not resolve inside an App Extension container"
        )
        return url
    }

    /// Landing folder for files received by this Mac (matches Kotlin `defaultDownloadsDir`).
    public static var downloadsOmniNodeURL: URL {
        realUserHomeDirectory.appendingPathComponent("Downloads/OmniNode", isDirectory: true)
    }
}
