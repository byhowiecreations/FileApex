/// What a parked socket was opened for. A plain socket must never serve a request that has a TLS route, and a socket
/// opened under one pin or TLS port must not serve a peer that has since been re-keyed or moved.
func lanPoolTag(tlsPort: UInt16?, pins: Set<String>) -> String {
    guard let tlsPort else { return "plain" }
    return "tls:\(tlsPort):" + pins.sorted().joined(separator: ",")
}
