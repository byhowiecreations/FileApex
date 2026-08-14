import Darwin
import Foundation
import Network

public typealias FileApexLanPeerCallback = @convention(c) (
    UnsafePointer<CChar>?,
    Int32,
    UnsafePointer<CChar>?
) -> Void

/// Advertise and browse `_fileapex-ln._tcp` so a Finder/Dock launch is evaluated
/// by Local Network Privacy. HTTP I/O stays on a background queue.
enum LocalNetworkProbe {
    static let policyType = "_fileapex-ln._tcp"
    private static let peerType = "_fileapex._tcp"
    private static let policyDomain = "local."
    private static var peerBrowser: NWBrowser?
    private static var policyBrowser: NWBrowser?
    private static var listener: NWListener?
    private static let ioQueue = DispatchQueue(label: "com.fileapex.local-network-probe")
    private static let gate = NSLock()
    private static var peerCallback: FileApexLanPeerCallback?
    private static var resolving: [String: NWConnection] = [:]

    static func setPeerCallback(_ callback: FileApexLanPeerCallback?) {
        peerCallback = callback
        start()
    }

    static func start() {
        if Thread.isMainThread {
            startOnMain()
        } else {
            DispatchQueue.main.async { startOnMain() }
        }
    }

    private static func startOnMain() {
        gate.lock()
        defer { gate.unlock() }
        startPolicyListener()
        startPolicyBrowser()
        startPeerBrowser()
        ioQueue.async { triggerPrivacyAlert() }
    }

    private static func bonjourTcpParams() -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        let params = NWParameters(tls: nil, tcp: tcp)
        params.includePeerToPeer = true
        params.allowLocalEndpointReuse = true
        return params
    }

    /// Advertise `_fileapex-ln._tcp` on `local.` — this is the evaluation the
    /// policy daemon needs for GUI-launched processes.
    private static func startPolicyListener() {
        if listener != nil { return }
        do {
            let instance = try NWListener(using: bonjourTcpParams())
            instance.service = NWListener.Service(
                name: "FileApex",
                type: policyType,
                domain: policyDomain
            )
            instance.stateUpdateHandler = { state in
                NSLog("FileApex NWListener: \(String(describing: state))")
            }
            instance.serviceRegistrationUpdateHandler = { change in
                NSLog("FileApex NWListener service: \(String(describing: change))")
            }
            instance.newConnectionHandler = { connection in
                connection.cancel()
            }
            instance.start(queue: ioQueue)
            listener = instance
            NSLog("FileApex NWListener: advertising \(policyType) \(policyDomain)")
        } catch {
            NSLog("FileApex NWListener: %@", error.localizedDescription)
        }
    }

    /// Matching browse cycle for the same type. Results must be observed or
    /// macOS treats the browser as idle and skips policy evaluation.
    private static func startPolicyBrowser() {
        if policyBrowser != nil { return }
        let params = NWParameters()
        params.includePeerToPeer = true
        let instance = NWBrowser(
            for: .bonjour(type: policyType, domain: policyDomain),
            using: params
        )
        instance.stateUpdateHandler = { state in
            NSLog("FileApex LocalNetworkProbe policy: \(String(describing: state))")
        }
        instance.browseResultsChangedHandler = { results, _ in
            NSLog("FileApex LocalNetworkProbe policy results=%d", results.count)
        }
        instance.start(queue: ioQueue)
        policyBrowser = instance
    }

    private static func startPeerBrowser() {
        if peerBrowser != nil { return }
        let params = NWParameters()
        params.includePeerToPeer = true
        let instance = NWBrowser(
            for: .bonjour(type: peerType, domain: policyDomain),
            using: params
        )
        instance.stateUpdateHandler = { state in
            switch state {
            case .failed(let error):
                NSLog("FileApex LocalNetworkProbe: %@", error.localizedDescription)
            default:
                break
            }
        }
        instance.browseResultsChangedHandler = { results, _ in
            for result in results {
                resolve(result)
            }
        }
        instance.start(queue: ioQueue)
        peerBrowser = instance
        NSLog("FileApex LocalNetworkProbe: browsing \(peerType)")
    }

    private static func resolve(_ result: NWBrowser.Result) {
        guard case .service(let name, _, _, _) = result.endpoint else { return }
        if resolving[name] != nil { return }
        let connection = NWConnection(to: result.endpoint, using: bonjourTcpParams())
        resolving[name] = connection
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                deliverIfPossible(serviceName: name, connection: connection)
                connection.cancel()
                resolving[name] = nil
            case .failed, .cancelled:
                resolving[name] = nil
            default:
                break
            }
        }
        connection.start(queue: ioQueue)
    }

    private static func deliverIfPossible(serviceName: String, connection: NWConnection) {
        guard case .hostPort(let host, let port) = connection.currentPath?.remoteEndpoint else {
            return
        }
        let ip: String
        switch host {
        case .ipv4(let address):
            ip = String(describing: address).components(separatedBy: "%").first ?? String(describing: address)
        default:
            return
        }
        let portValue = Int32(port.rawValue)
        guard portValue > 0, !ip.isEmpty else { return }
        ip.withCString { ipPtr in
            serviceName.withCString { namePtr in
                peerCallback?(ipPtr, portValue, namePtr)
            }
        }
    }

    /// TN3179: connected UDP to link-local IPv6 triggers the Local Network alert
    /// without sending traffic. Runs off the main thread.
    private static func triggerPrivacyAlert() {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let start = ifaddr else { return }
        defer { freeifaddrs(start) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = start
        while let ptr = cursor {
            defer { cursor = ptr.pointee.ifa_next }
            let flags = Int32(bitPattern: ptr.pointee.ifa_flags)
            guard (flags & IFF_BROADCAST) != 0, (flags & IFF_LOOPBACK) == 0 else { continue }
            guard let sa = ptr.pointee.ifa_addr, sa.pointee.sa_family == sa_family_t(AF_INET6) else {
                continue
            }
            var addr6 = sa.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { $0.pointee }
            let b0 = addr6.sin6_addr.__u6_addr.__u6_addr8.0
            let b1 = addr6.sin6_addr.__u6_addr.__u6_addr8.1
            guard b0 == 0xfe, (b1 & 0xc0) == 0x80 else { continue }
            addr6.sin6_port = UInt16(9).bigEndian
            let fd = socket(AF_INET6, SOCK_DGRAM, 0)
            guard fd >= 0 else { continue }
            defer { close(fd) }
            _ = withUnsafePointer(to: &addr6) { ptr6 in
                ptr6.withMemoryRebound(to: sockaddr.self, capacity: 1) { saPtr in
                    connect(fd, saPtr, socklen_t(MemoryLayout<sockaddr_in6>.size))
                }
            }
        }
        NSLog("FileApex LocalNetworkProbe: privacy alert probe sent")
    }
}

@_cdecl("fileapex_lan_set_peer_callback")
public func fileapex_lan_set_peer_callback(_ callback: FileApexLanPeerCallback?) {
    LocalNetworkProbe.setPeerCallback(callback)
}
