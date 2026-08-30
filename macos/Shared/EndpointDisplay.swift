import Foundation

/// UI-only host:port labels. Never use locale-aware number formatting for ports.
public enum EndpointDisplay {
    public static func format(ip: String, port: Int) -> String {
        let cleaned = ip.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty, port > 0 else { return cleaned }
        return "\(cleaned):\(port)"
    }
}

public extension PairedDevice {
    var endpointDisplayLabel: String {
        EndpointDisplay.format(ip: lastKnownIp, port: port)
    }
}
