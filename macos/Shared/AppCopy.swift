import Combine
import Foundation

/// Runtime copy exported by Kotlin [AppI18n.runtimeOverlayJson] for native Mac chrome.
/// Share extensions also ship the XML catalogs in the .appex so first-run Share still has copy.
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
        if let data = try? Data(contentsOf: url),
           let text = String(data: data, encoding: .utf8),
           parse(text) {
            return
        }
        loadBundledFallback()
    }

    @discardableResult
    private func parse(_ json: String) -> Bool {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return false }
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
        return !strings.isEmpty
    }

    private func loadBundledFallback() {
        let name = bundledCatalogName()
        let url = Bundle.main.url(forResource: name, withExtension: "xml")
            ?? Bundle.main.url(forResource: "en", withExtension: "xml")
        guard let url, let xml = try? String(contentsOf: url, encoding: .utf8) else { return }
        parseXmlCatalog(xml)
    }

    private func bundledCatalogName() -> String {
        for lang in Locale.preferredLanguages {
            let lower = lang.lowercased()
            if lower.hasPrefix("zh") { return "zh-rCN" }
            if lower.hasPrefix("es") { return "es" }
            if lower.hasPrefix("en") { return "en" }
        }
        return "en"
    }

    private func parseXmlCatalog(_ xml: String) {
        let ns = xml as NSString
        let full = NSRange(location: 0, length: ns.length)
        var nextStrings: [String: String] = [:]
        var nextPlurals: [String: [String: String]] = [:]
        if let stringRe = try? NSRegularExpression(
            pattern: #"<string name="([^"]+)">([\s\S]*?)</string>"#,
            options: []
        ) {
            for match in stringRe.matches(in: xml, range: full) where match.numberOfRanges >= 3 {
                let key = ns.substring(with: match.range(at: 1))
                nextStrings[key] = unescapeXml(ns.substring(with: match.range(at: 2)))
            }
        }
        if let pluralRe = try? NSRegularExpression(
            pattern: #"<plurals name="([^"]+)">([\s\S]*?)</plurals>"#,
            options: []
        ),
           let itemRe = try? NSRegularExpression(
            pattern: #"<item quantity="([^"]+)">([\s\S]*?)</item>"#,
            options: []
           ) {
            for match in pluralRe.matches(in: xml, range: full) where match.numberOfRanges >= 3 {
                let key = ns.substring(with: match.range(at: 1))
                let body = ns.substring(with: match.range(at: 2))
                let bodyNs = body as NSString
                var forms: [String: String] = [:]
                let bodyRange = NSRange(location: 0, length: bodyNs.length)
                for item in itemRe.matches(in: body, range: bodyRange) where item.numberOfRanges >= 3 {
                    let quantity = bodyNs.substring(with: item.range(at: 1))
                    forms[quantity] = unescapeXml(bodyNs.substring(with: item.range(at: 2)))
                }
                if !forms.isEmpty {
                    nextPlurals[key] = forms
                }
            }
        }
        if !nextStrings.isEmpty {
            strings = nextStrings
            plurals = nextPlurals
        }
    }

    private func unescapeXml(_ raw: String) -> String {
        raw
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "\\'", with: "'")
            .replacingOccurrences(of: "&amp;", with: "&")
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
