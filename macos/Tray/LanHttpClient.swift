import Darwin
import Foundation
import Network

/// Peer HTTP over NWConnection so Finder/Dock launches register with the macOS
/// local-network policy daemon. Java BSD sockets are blocked in that context.
enum LanHttpClient {
    private static let queue = DispatchQueue(label: "com.fileapex.lan-http", attributes: .concurrent)
    private static let slots = DispatchSemaphore(value: 6)
    private static let streamBufferBytes = 256 * 1024
    private static let logLock = NSLock()

    static func execute(
        method: String,
        urlString: String,
        contentType: String?,
        body: Data?,
        timeoutMs: Int
    ) -> (status: Int, body: Data)? {
        guard let target = Target(urlString: urlString) else {
            log("bad url \(urlString)")
            return nil
        }
        var header = "\(method) \(target.path) HTTP/1.1\r\n"
        header += "Host: \(target.host):\(target.port)\r\n"
        header += "Connection: close\r\n"
        header += "Accept: */*\r\n"
        if let contentType, !contentType.isEmpty {
            header += "Content-Type: \(contentType)\r\n"
            header += "Content-Length: \(body?.count ?? 0)\r\n"
        }
        header += "\r\n"
        var request = Data(header.utf8)
        if let body, !body.isEmpty {
            request.append(body)
        }
        log("\(method) \(target.host):\(target.port)\(target.path)")
        return transact(target: target, request: request, timeoutMs: timeoutMs)
    }

    static func uploadFile(
        urlString: String,
        contentType: String?,
        filePath: String,
        offsetBytes: UInt64,
        timeoutMs: Int
    ) -> (status: Int, body: Data)? {
        guard let target = Target(urlString: urlString) else { return nil }
        let fileURL = URL(fileURLWithPath: filePath)
        guard let handle = try? FileHandle(forReadingFrom: fileURL) else {
            log("upload missing file \(filePath)")
            return nil
        }
        defer { try? handle.close() }
        let remaining: UInt64
        do {
            let end = try handle.seekToEnd()
            if offsetBytes > end {
                log("upload offset \(offsetBytes) past size \(end)")
                return nil
            }
            remaining = end - offsetBytes
            try handle.seek(toOffset: offsetBytes)
        } catch {
            log("upload seek failed \(error.localizedDescription)")
            return nil
        }
        var header = "POST \(target.path) HTTP/1.1\r\n"
        header += "Host: \(target.host):\(target.port)\r\n"
        header += "Connection: close\r\n"
        header += "Content-Type: \(contentType?.isEmpty == false ? contentType! : "application/octet-stream")\r\n"
        header += "Content-Length: \(remaining)\r\n\r\n"
        log("UPLOAD \(target.host):\(target.port)\(target.path) offset=\(offsetBytes) bytes=\(remaining)")
        return transact(
            target: target,
            request: Data(header.utf8),
            timeoutMs: timeoutMs,
            extraSender: { connection, done in
                sendFile(handle, on: connection, completion: done)
            }
        )
    }

    static func downloadFile(
        urlString: String,
        destinationPath: String,
        timeoutMs: Int
    ) -> Int? {
        guard let target = Target(urlString: urlString) else { return nil }
        var header = "GET \(target.path) HTTP/1.1\r\n"
        header += "Host: \(target.host):\(target.port)\r\n"
        header += "Connection: close\r\n"
        header += "Accept: application/octet-stream\r\n\r\n"
        log("GET-FILE \(target.host):\(target.port)\(target.path)")
        return transactToFile(
            target: target,
            request: Data(header.utf8),
            destinationPath: destinationPath,
            timeoutMs: timeoutMs
        )
    }

    private struct Target {
        let host: String
        let port: UInt16
        let path: String

        init?(urlString: String) {
            guard let url = URL(string: urlString), let host = url.host, !host.isEmpty else {
                return nil
            }
            self.host = host
            let resolvedPort = url.port ?? 80
            guard resolvedPort > 0, resolvedPort <= Int(UInt16.max) else { return nil }
            self.port = UInt16(resolvedPort)
            var path = url.path.isEmpty ? "/" : url.path
            if let query = url.query, !query.isEmpty {
                path += "?" + query
            }
            self.path = path
        }
    }

    private static func transact(
        target: Target,
        request: Data,
        timeoutMs: Int,
        extraSender: ((NWConnection, @escaping (Bool) -> Void) -> Void)? = nil
    ) -> (status: Int, body: Data)? {
        guard let port = NWEndpoint.Port(rawValue: target.port) else { return nil }
        slots.wait()
        defer { slots.signal() }
        let host: NWEndpoint.Host = IPv4Address(target.host).map { .ipv4($0) } ?? NWEndpoint.Host(target.host)
        let connection = NWConnection(
            to: .hostPort(host: host, port: port),
            using: unicastTcpParams()
        )
        let lock = DispatchSemaphore(value: 0)
        let stateLock = NSLock()
        var result: (Int, Data)?
        var finished = false
        func finish(_ value: (Int, Data)?) {
            stateLock.lock()
            let shouldComplete = !finished
            if shouldComplete {
                finished = true
                result = value
            }
            stateLock.unlock()
            guard shouldComplete else { return }
            connection.cancel()
            lock.signal()
        }

        connection.pathUpdateHandler = { path in
            if path.status == .unsatisfied {
                log("path unsatisfied \(target.host):\(target.port) \(unsatisfiedText(path))")
            }
        }
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                connection.send(content: request, isComplete: extraSender == nil, completion: .contentProcessed { error in
                    if let error {
                        log("send failed \(error.localizedDescription)")
                        finish(nil)
                        return
                    }
                    let afterHeaders = {
                        receiveHttp(on: connection, finish: finish)
                    }
                    if let extraSender {
                        extraSender(connection) { ok in
                            if ok {
                                afterHeaders()
                            } else {
                                finish(nil)
                            }
                        }
                    } else {
                        afterHeaders()
                    }
                })
            case .waiting(let error):
                let reason = unsatisfiedText(connection.currentPath)
                log("waiting \(target.host):\(target.port) \(error.localizedDescription) \(reason)")
                // Stay waiting so Allow on the system dialog can complete the path.
            case .failed(let error):
                log("connect failed \(target.host):\(target.port) \(error.localizedDescription)")
                finish(nil)
            default:
                break
            }
        }
        connection.start(queue: queue)
        let seconds = max(TimeInterval(timeoutMs) / 1000.0, 0.25) + 1.0
        _ = lock.wait(timeout: .now() + seconds)
        stateLock.lock()
        let timedOut = !finished
        stateLock.unlock()
        if timedOut {
            log("timeout \(target.host):\(target.port)")
            connection.cancel()
        }
        if result == nil {
            log("no response \(target.host):\(target.port)")
        } else if let result {
            log("status \(result.0) \(target.host):\(target.port)")
        }
        return result
    }

    private static func unicastTcpParams() -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let params = NWParameters(tls: nil, tcp: tcp)
        params.includePeerToPeer = false
        params.allowLocalEndpointReuse = true
        params.preferNoProxies = true
        if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options {
            ip.version = .v4
        }
        if let local = lanIpv4() {
            params.requiredLocalEndpoint = .hostPort(host: .ipv4(local), port: .any)
        }
        return params
    }

    private static func lanIpv4() -> IPv4Address? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let start = ifaddr else { return nil }
        defer { freeifaddrs(start) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = start
        var found: IPv4Address?
        while let ptr = cursor {
            defer { cursor = ptr.pointee.ifa_next }
            let flags = Int32(bitPattern: ptr.pointee.ifa_flags)
            guard (flags & IFF_UP) != 0, (flags & IFF_LOOPBACK) == 0 else { continue }
            guard let sa = ptr.pointee.ifa_addr, sa.pointee.sa_family == sa_family_t(AF_INET) else {
                continue
            }
            var addr = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
            var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            inet_ntop(AF_INET, &addr.sin_addr, &buf, socklen_t(INET_ADDRSTRLEN))
            let text = String(cString: buf)
            guard let ipv4 = IPv4Address(text), isPrivateLan(text) else { continue }
            found = ipv4
            break
        }
        return found
    }

    private static func isPrivateLan(_ ip: String) -> Bool {
        let parts = ip.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4 else { return false }
        if parts[0] == 10 { return true }
        if parts[0] == 192 && parts[1] == 168 { return true }
        if parts[0] == 172 && parts[1] >= 16 && parts[1] <= 31 { return true }
        return false
    }

    private static func unsatisfiedText(_ path: NWPath?) -> String {
        guard let path else { return "no-path" }
        return String(describing: path.unsatisfiedReason)
    }

    private static func sendFile(
        _ handle: FileHandle,
        on connection: NWConnection,
        completion: @escaping (Bool) -> Void
    ) {
        let chunk: Data
        do {
            chunk = try handle.read(upToCount: streamBufferBytes) ?? Data()
        } catch {
            log("upload read failed \(error.localizedDescription)")
            completion(false)
            return
        }
        if chunk.isEmpty {
            connection.send(content: nil, isComplete: true, completion: .contentProcessed { error in
                completion(error == nil)
            })
            return
        }
        connection.send(content: chunk, isComplete: false, completion: .contentProcessed { error in
            if let error {
                log("upload chunk failed \(error.localizedDescription)")
                completion(false)
                return
            }
            sendFile(handle, on: connection, completion: completion)
        })
    }

    private static func transactToFile(
        target: Target,
        request: Data,
        destinationPath: String,
        timeoutMs: Int
    ) -> Int? {
        guard let port = NWEndpoint.Port(rawValue: target.port) else { return nil }
        slots.wait()
        defer { slots.signal() }
        let host: NWEndpoint.Host = IPv4Address(target.host).map { .ipv4($0) } ?? NWEndpoint.Host(target.host)
        let connection = NWConnection(
            to: .hostPort(host: host, port: port),
            using: unicastTcpParams()
        )
        let lock = DispatchSemaphore(value: 0)
        let stateLock = NSLock()
        var result: Int?
        var finished = false
        func finish(_ value: Int?) {
            stateLock.lock()
            let shouldComplete = !finished
            if shouldComplete {
                finished = true
                result = value
            }
            stateLock.unlock()
            guard shouldComplete else { return }
            connection.cancel()
            lock.signal()
        }

        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                connection.send(content: request, isComplete: true, completion: .contentProcessed { error in
                    if let error {
                        log("send failed \(error.localizedDescription)")
                        finish(nil)
                        return
                    }
                    receiveHttpToFile(
                        on: connection,
                        destinationPath: destinationPath,
                        headerBuffer: Data(),
                        finish: finish
                    )
                })
            case .waiting(let error):
                let reason = unsatisfiedText(connection.currentPath)
                log("waiting \(target.host):\(target.port) \(error.localizedDescription) \(reason)")
            case .failed(let error):
                log("connect failed \(target.host):\(target.port) \(error.localizedDescription)")
                finish(nil)
            default:
                break
            }
        }
        connection.start(queue: queue)
        let seconds = max(TimeInterval(timeoutMs) / 1000.0, 0.25) + 1.0
        _ = lock.wait(timeout: .now() + seconds)
        stateLock.lock()
        let timedOut = !finished
        stateLock.unlock()
        if timedOut {
            log("timeout \(target.host):\(target.port)")
            connection.cancel()
        }
        return result
    }

    private static func receiveHttpToFile(
        on connection: NWConnection,
        destinationPath: String,
        headerBuffer: Data,
        finish: @escaping (Int?) -> Void
    ) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: streamBufferBytes) { content, _, isComplete, error in
            if let error {
                log("receive failed \(error.localizedDescription)")
                finish(nil)
                return
            }
            var next = headerBuffer
            if let content, !content.isEmpty {
                next.append(content)
            }
            let separator = Data("\r\n\r\n".utf8)
            guard let range = next.range(of: separator) else {
                if isComplete {
                    finish(nil)
                    return
                }
                receiveHttpToFile(
                    on: connection,
                    destinationPath: destinationPath,
                    headerBuffer: next,
                    finish: finish
                )
                return
            }
            let headerBytes = next.subdata(in: 0..<range.lowerBound)
            let bodyPrefix = next.subdata(in: range.upperBound..<next.count)
            guard let parsed = HttpResponseParser.parseHeaders(headerBytes) else {
                finish(nil)
                return
            }
            let dest = URL(fileURLWithPath: destinationPath)
            do {
                try FileManager.default.createDirectory(
                    at: dest.deletingLastPathComponent(),
                    withIntermediateDirectories: true
                )
                if FileManager.default.fileExists(atPath: destinationPath) {
                    try FileManager.default.removeItem(at: dest)
                }
                FileManager.default.createFile(atPath: destinationPath, contents: nil)
            } catch {
                log("download dest failed \(error.localizedDescription)")
                finish(nil)
                return
            }
            guard let handle = try? FileHandle(forWritingTo: dest) else {
                finish(nil)
                return
            }
            streamBodyToFile(
                on: connection,
                handle: handle,
                status: parsed.status,
                contentLength: parsed.contentLength,
                isChunked: parsed.isChunked,
                pending: bodyPrefix,
                received: 0,
                isComplete: isComplete,
                finish: finish
            )
        }
    }

    private static func streamBodyToFile(
        on connection: NWConnection,
        handle: FileHandle,
        status: Int,
        contentLength: Int?,
        isChunked: Bool,
        pending: Data,
        received: Int,
        isComplete: Bool,
        finish: @escaping (Int?) -> Void
    ) {
        func closeAndFinish(_ value: Int?) {
            try? handle.close()
            finish(value)
        }
        do {
            if isChunked {
                let decoded = try writeChunked(pending, to: handle)
                if decoded.done || isComplete {
                    closeAndFinish(status)
                    return
                }
                connection.receive(minimumIncompleteLength: 1, maximumLength: streamBufferBytes) { content, _, complete, error in
                    if let error {
                        log("receive failed \(error.localizedDescription)")
                        closeAndFinish(nil)
                        return
                    }
                    streamBodyToFile(
                        on: connection,
                        handle: handle,
                        status: status,
                        contentLength: contentLength,
                        isChunked: true,
                        pending: decoded.leftover + (content ?? Data()),
                        received: received,
                        isComplete: complete,
                        finish: finish
                    )
                }
                return
            }
            if !pending.isEmpty {
                try handle.write(contentsOf: pending)
            }
        } catch {
            log("download write failed \(error.localizedDescription)")
            closeAndFinish(nil)
            return
        }
        let written = received + pending.count
        if let contentLength, written >= contentLength {
            closeAndFinish(status)
            return
        }
        if isComplete {
            closeAndFinish(status)
            return
        }
        connection.receive(minimumIncompleteLength: 1, maximumLength: streamBufferBytes) { content, _, complete, error in
            if let error {
                log("receive failed \(error.localizedDescription)")
                closeAndFinish(nil)
                return
            }
            streamBodyToFile(
                on: connection,
                handle: handle,
                status: status,
                contentLength: contentLength,
                isChunked: false,
                pending: content ?? Data(),
                received: written,
                isComplete: complete,
                finish: finish
            )
        }
    }

    /// Writes complete chunk payloads to disk; keeps only an unfinished chunk in [leftover].
    private static func writeChunked(_ data: Data, to handle: FileHandle) throws -> (leftover: Data, done: Bool) {
        var leftover = data
        let crlf = Data("\r\n".utf8)
        while true {
            guard let lineEnd = leftover.range(of: crlf) else {
                return (leftover, false)
            }
            let sizeLine = leftover.subdata(in: 0..<lineEnd.lowerBound)
            let hex = String(data: sizeLine, encoding: .isoLatin1)?
                .split(separator: ";").first?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard let size = Int(hex, radix: 16) else {
                throw NSError(domain: "FileApexLanHttp", code: 1)
            }
            leftover = leftover.subdata(in: lineEnd.upperBound..<leftover.count)
            if size == 0 {
                return (Data(), true)
            }
            guard leftover.count >= size + 2 else {
                var restored = sizeLine
                restored.append(crlf)
                restored.append(leftover)
                return (restored, false)
            }
            try handle.write(contentsOf: leftover.prefix(size))
            leftover = leftover.subdata(in: leftover.startIndex.advanced(by: size)..<leftover.endIndex)
            if leftover.starts(with: crlf) {
                leftover = leftover.subdata(in: leftover.startIndex.advanced(by: 2)..<leftover.endIndex)
            }
        }
    }

    private static func receiveHttp(
        on connection: NWConnection,
        buffer: Data = Data(),
        finish: @escaping ((Int, Data)?) -> Void
    ) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { content, _, isComplete, error in
            if let error {
                log("receive failed \(error.localizedDescription)")
                finish(nil)
                return
            }
            var next = buffer
            if let content, !content.isEmpty {
                next.append(content)
            }
            if let parsed = HttpResponseParser.parse(next) {
                finish((parsed.status, parsed.body))
                return
            }
            if isComplete {
                finish(HttpResponseParser.parse(next).map { ($0.status, $0.body) })
                return
            }
            receiveHttp(on: connection, buffer: next, finish: finish)
        }
    }

    private static func log(_ message: String) {
        NSLog("FileApex LanHttp: %@", message)
        logLock.lock()
        defer { logLock.unlock() }
        let dir = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Application Support/com.fileapex")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("lan-client.log")
        let line = "\(ISO8601DateFormatter().string(from: Date())) \(message)\n"
        if let handle = try? FileHandle(forWritingTo: file) {
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: Data(line.utf8))
            try? handle.close()
        } else {
            try? line.write(to: file, atomically: true, encoding: .utf8)
        }
    }
}

private enum HttpResponseParser {
    static func parse(_ data: Data) -> (status: Int, body: Data)? {
        let separator = Data("\r\n\r\n".utf8)
        guard let range = data.range(of: separator) else { return nil }
        let headerBytes = data.subdata(in: 0..<range.lowerBound)
        var body = data.subdata(in: range.upperBound..<data.count)
        guard let headerText = String(data: headerBytes, encoding: .isoLatin1) else { return nil }
        let lines = headerText.split(separator: "\r\n", omittingEmptySubsequences: false)
        guard let statusLine = lines.first else { return nil }
        let status = statusLine.split(separator: " ").dropFirst().first.flatMap { Int($0) } ?? 0
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            headers[key] = value
        }
        if headers["transfer-encoding"]?.lowercased().contains("chunked") == true {
            guard let decoded = decodeChunked(body) else { return nil }
            body = decoded
        } else if let length = headers["content-length"].flatMap(Int.init) {
            if body.count < length { return nil }
            body = body.prefix(length)
        }
        guard status > 0 else { return nil }
        return (status, Data(body))
    }

    static func parseHeaders(_ headerBytes: Data) -> (status: Int, contentLength: Int?, isChunked: Bool)? {
        guard let headerText = String(data: headerBytes, encoding: .isoLatin1) else { return nil }
        let lines = headerText.split(separator: "\r\n", omittingEmptySubsequences: false)
        guard let statusLine = lines.first else { return nil }
        let status = statusLine.split(separator: " ").dropFirst().first.flatMap { Int($0) } ?? 0
        guard status > 0 else { return nil }
        var contentLength: Int?
        var isChunked = false
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            if key == "content-length" {
                contentLength = Int(value)
            } else if key == "transfer-encoding", value.lowercased().contains("chunked") {
                isChunked = true
            }
        }
        return (status, contentLength, isChunked)
    }

    private static func decodeChunked(_ data: Data) -> Data? {
        var remaining = data
        var out = Data()
        while !remaining.isEmpty {
            guard let lineEnd = remaining.range(of: Data("\r\n".utf8)) else { return nil }
            let sizeLine = remaining.subdata(in: 0..<lineEnd.lowerBound)
            remaining = remaining.subdata(in: lineEnd.upperBound..<remaining.count)
            let hex = String(data: sizeLine, encoding: .isoLatin1)?
                .split(separator: ";").first?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard let size = Int(hex, radix: 16) else { return nil }
            if size == 0 { return out }
            guard remaining.count >= size + 2 else { return nil }
            out.append(remaining.prefix(size))
            remaining = remaining.dropFirst(size)
            if remaining.starts(with: Data("\r\n".utf8)) {
                remaining = remaining.dropFirst(2)
            }
        }
        return nil
    }
}

@_cdecl("fileapex_lan_http_execute")
public func fileapex_lan_http_execute(
    method: UnsafePointer<CChar>?,
    url: UnsafePointer<CChar>?,
    contentType: UnsafePointer<CChar>?,
    body: UnsafePointer<UInt8>?,
    bodyLen: Int32,
    timeoutMs: Int32,
    outStatus: UnsafeMutablePointer<Int32>?,
    outBody: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>?,
    outBodyLen: UnsafeMutablePointer<Int32>?
) -> Int32 {
    guard let method, let url else { return -1 }
    let bodyData: Data?
    if let body, bodyLen > 0 {
        bodyData = Data(bytes: body, count: Int(bodyLen))
    } else {
        bodyData = nil
    }
    guard let result = LanHttpClient.execute(
        method: String(cString: method),
        urlString: String(cString: url),
        contentType: contentType.map { String(cString: $0) },
        body: bodyData,
        timeoutMs: Int(timeoutMs)
    ) else {
        return -1
    }
    writeHttpResult(result.status, result.body, outStatus, outBody, outBodyLen)
    return 0
}

@_cdecl("fileapex_lan_http_upload_file")
public func fileapex_lan_http_upload_file(
    url: UnsafePointer<CChar>?,
    contentType: UnsafePointer<CChar>?,
    filePath: UnsafePointer<CChar>?,
    offsetBytes: Int64,
    timeoutMs: Int32,
    outStatus: UnsafeMutablePointer<Int32>?,
    outBody: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>?,
    outBodyLen: UnsafeMutablePointer<Int32>?
) -> Int32 {
    guard let url, let filePath else { return -1 }
    let offset = offsetBytes > 0 ? UInt64(offsetBytes) : 0
    guard let result = LanHttpClient.uploadFile(
        urlString: String(cString: url),
        contentType: contentType.map { String(cString: $0) },
        filePath: String(cString: filePath),
        offsetBytes: offset,
        timeoutMs: Int(timeoutMs)
    ) else {
        return -1
    }
    writeHttpResult(result.status, result.body, outStatus, outBody, outBodyLen)
    return 0
}

@_cdecl("fileapex_lan_http_download_file")
public func fileapex_lan_http_download_file(
    url: UnsafePointer<CChar>?,
    destinationPath: UnsafePointer<CChar>?,
    timeoutMs: Int32,
    outStatus: UnsafeMutablePointer<Int32>?
) -> Int32 {
    guard let url, let destinationPath else { return -1 }
    guard let status = LanHttpClient.downloadFile(
        urlString: String(cString: url),
        destinationPath: String(cString: destinationPath),
        timeoutMs: Int(timeoutMs)
    ) else {
        return -1
    }
    outStatus?.pointee = Int32(status)
    return 0
}

@_cdecl("fileapex_lan_http_free")
public func fileapex_lan_http_free(_ pointer: UnsafeMutableRawPointer?) {
    pointer?.deallocate()
}

private func writeHttpResult(
    _ status: Int,
    _ body: Data,
    _ outStatus: UnsafeMutablePointer<Int32>?,
    _ outBody: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>?,
    _ outBodyLen: UnsafeMutablePointer<Int32>?
) {
    outStatus?.pointee = Int32(status)
    if body.isEmpty {
        outBody?.pointee = nil
        outBodyLen?.pointee = 0
        return
    }
    let count = body.count
    let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: count)
    body.copyBytes(to: buffer, count: count)
    outBody?.pointee = buffer
    outBodyLen?.pointee = Int32(count)
}
