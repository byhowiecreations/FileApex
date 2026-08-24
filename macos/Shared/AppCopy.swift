import Combine
import Foundation

/// Runtime copy exported by Kotlin [AppI18n.runtimeOverlayJson] for native Mac chrome.
public final class AppCopy: ObservableObject {
    public static let shared = AppCopy()

    private var strings: [String: String] = [:]
    private var plurals: [String: [String: String]] = [:]

    private init() {
        loadFromDisk()
    }

    public func t(_ key: String, _ args: String...) -> String {
        format(strings[key] ?? key, args)
    }

    public func plural(_ key: String, count: Int, _ args: String...) -> String {
        let forms = plurals[key] ?? [:]
        let quantity = count == 1 ? "one" : "other"
        let template = forms[quantity] ?? forms["other"] ?? strings[key] ?? key
        let resolved = args.isEmpty ? ["\(count)"] : args
        return format(template, resolved)
    }

    public func apply(json: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.objectWillChange.send()
            self.parse(json)
        }
        parse(json)
    }

    public func loadFromDisk() {
        let url = FileApexPaths.realUserHomeDirectory
            .appendingPathComponent("Library/Application Support/com.fileapex/i18n_runtime.json")
        guard let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .utf8) else { return }
        parse(text)
    }

    private func parse(_ json: String) {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        if let s = obj["strings"] as? [String: String] {
            strings = s
        }
        var parsedPlurals: [String: [String: String]] = [:]
        if let p = obj["plurals"] as? [String: [String: Any]] {
            for (key, forms) in p {
                var mapped: [String: String] = [:]
                for (quantity, value) in forms {
                    if let text = value as? String {
                        mapped[quantity] = text
                    }
                }
                parsedPlurals[key] = mapped
            }
        }
        plurals = parsedPlurals
    }

    private func format(_ template: String, _ args: [String]) -> String {
        var out = template
        for (index, arg) in args.enumerated() {
            out = out.replacingOccurrences(of: "%\(index + 1)$s", with: arg)
            out = out.replacingOccurrences(of: "%\(index + 1)$d", with: arg)
        }
        if args.count == 1 {
            out = out.replacingOccurrences(of: "%s", with: args[0])
            out = out.replacingOccurrences(of: "%d", with: args[0])
        }
        return out
    }
}
