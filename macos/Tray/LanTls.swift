import CryptoKit
import Foundation
import Network
import Security

/// A peer reached over pinned mutual TLS. Kotlin pushes the table; a peer in it is never contacted over plain HTTP.
struct LanTlsRoute {
    let tlsPort: UInt16
    let pins: Set<String>
}

enum LanTls {
    private static let lock = NSLock()
    private static var routes: [String: LanTlsRoute] = [:]
    private static var identity: sec_identity_t?
    private static var mismatches: [String: String] = [:]
    private static let verifyQueue = DispatchQueue(label: "com.fileapex.lan-tls.verify")

    // DER prefix of a P-256 SubjectPublicKeyInfo; the 65-byte uncompressed point follows.
    private static let p256SpkiPrefix: [UInt8] = [
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01,
        0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00
    ]

    static func route(host: String, port: UInt16) -> LanTlsRoute? {
        lock.lock()
        defer { lock.unlock() }
        return routes["\(host):\(port)"]
    }

    static var hasIdentity: Bool {
        lock.lock()
        defer { lock.unlock() }
        return identity != nil
    }

    /// JSON: [{"host":"192.168.1.9","port":8080,"tlsPort":8443,"pins":["<hex>"]}]
    static func setRoutes(json: Data) -> Bool {
        guard let array = try? JSONSerialization.jsonObject(with: json) as? [[String: Any]] else { return false }
        var parsed: [String: LanTlsRoute] = [:]
        for entry in array {
            guard let host = entry["host"] as? String,
                  let port = entry["port"] as? Int,
                  let tlsPort = entry["tlsPort"] as? Int,
                  let pins = entry["pins"] as? [String],
                  (1...65535).contains(port), (1...65535).contains(tlsPort), !pins.isEmpty else { continue }
            parsed["\(host):\(port)"] = LanTlsRoute(tlsPort: UInt16(tlsPort), pins: Set(pins.map { $0.lowercased() }))
        }
        lock.lock()
        let previous = routes
        routes = parsed
        lock.unlock()
        LanHttpClient.logTls("routes set: \(parsed.count) entries")
        // Sockets parked before a peer was (un)pinned or re-keyed must not be reused.
        var changed = Set(previous.keys).symmetricDifference(parsed.keys)
        for (key, route) in parsed {
            if let before = previous[key], before.tlsPort != route.tlsPort || before.pins != route.pins {
                changed.insert(key)
            }
        }
        if !changed.isEmpty { LanHttpClient.dropPooled(keys: changed) }
        return true
    }

    /// Returns and clears the key a pinned peer last presented when it did not match.
    static func takeMismatch(key: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return mismatches.removeValue(forKey: key)
    }

    private static func recordMismatch(key: String, pin: String) {
        lock.lock()
        mismatches[key] = pin
        lock.unlock()
    }

    /// The identity lives only in memory; nothing is written to a keychain.
    static func setIdentity(p12: Data, password: String) -> Bool {
        var options: [String: Any] = [kSecImportExportPassphrase as String: password]
        // macOS 14 has no memory-only import; there the identity also lands in the login keychain.
        if #available(macOS 15.0, *) {
            options[kSecImportToMemoryOnly as String] = true
        }
        var items: CFArray?
        let status = SecPKCS12Import(p12 as CFData, options as CFDictionary, &items)
        guard status == errSecSuccess,
              let list = items as? [[String: Any]],
              let first = list.first,
              let raw = first[kSecImportItemIdentity as String] else {
            LanHttpClient.logTls("identity import failed status=\(status)")
            return false
        }
        LanHttpClient.logTls("identity imported")
        let created = sec_identity_create(raw as! SecIdentity)
        lock.lock()
        identity = created
        lock.unlock()
        return true
    }

    static func tlsOptions(for route: LanTlsRoute, key: String) -> NWProtocolTLS.Options? {
        lock.lock()
        let local = identity
        lock.unlock()
        guard let local else { return nil }
        let options = NWProtocolTLS.Options()
        let sec = options.securityProtocolOptions
        sec_protocol_options_set_min_tls_protocol_version(sec, .TLSv12)
        sec_protocol_options_set_local_identity(sec, local)
        let pins = route.pins
        sec_protocol_options_set_verify_block(sec, { _, trust, complete in
            let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
            guard let chain = SecTrustCopyCertificateChain(secTrust) as? [SecCertificate],
                  let leaf = chain.first,
                  let pin = spkiPin(leaf) else {
                complete(false)
                return
            }
            let accepted = pins.contains(pin)
            if !accepted { recordMismatch(key: key, pin: pin) }
            complete(accepted)
        }, verifyQueue)
        return options
    }

    static func spkiPin(_ certificate: SecCertificate) -> String? {
        guard let key = SecCertificateCopyKey(certificate),
              let attributes = SecKeyCopyAttributes(key) as? [CFString: Any],
              (attributes[kSecAttrKeyType] as? String) == (kSecAttrKeyTypeECSECPrimeRandom as String),
              (attributes[kSecAttrKeySizeInBits] as? Int) == 256,
              let point = SecKeyCopyExternalRepresentation(key, nil) as Data?,
              point.count == 65 else { return nil }
        let digest = SHA256.hash(data: Data(p256SpkiPrefix) + point)
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}

@_cdecl("fileapex_lan_tls_set_routes")
public func fileapex_lan_tls_set_routes(json: UnsafePointer<CChar>?) -> Int32 {
    guard let json else { return -1 }
    return LanTls.setRoutes(json: Data(String(cString: json).utf8)) ? 0 : -1
}

@_cdecl("fileapex_lan_tls_set_identity")
public func fileapex_lan_tls_set_identity(
    p12: UnsafePointer<UInt8>?,
    p12Len: Int32,
    password: UnsafePointer<CChar>?
) -> Int32 {
    guard let p12, p12Len > 0, let password else { return -1 }
    return LanTls.setIdentity(p12: Data(bytes: p12, count: Int(p12Len)), password: String(cString: password)) ? 0 : -1
}

@_cdecl("fileapex_lan_tls_take_mismatch")
public func fileapex_lan_tls_take_mismatch(
    host: UnsafePointer<CChar>?,
    port: Int32,
    out: UnsafeMutablePointer<CChar>?,
    outLen: Int32
) -> Int32 {
    guard let host, let out, outLen > 64,
          let pin = LanTls.takeMismatch(key: "\(String(cString: host)):\(port)") else { return -1 }
    pin.withCString { source in
        strncpy(out, source, Int(outLen) - 1)
        out[Int(outLen) - 1] = 0
    }
    return 0
}
