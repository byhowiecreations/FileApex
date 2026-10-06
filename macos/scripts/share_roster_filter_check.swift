import Foundation

@main
struct ShareRosterFilterCheck {
    static func main() {
        let ready: Set<String> = ["live-x9d", "fold"]
        var failed = 0

        func check(_ name: String, _ value: Bool, _ expected: Bool) {
            if value == expected { return }
            FileHandle.standardError.write(Data("FAIL \(name): got \(value)\n".utf8))
            failed += 1
        }

        check("ready", ShareRoster.isListed(isRemoved: false, deviceId: "live-x9d", readyDeviceIds: ready), true)
        check("offline", ShareRoster.isListed(isRemoved: false, deviceId: "moto", readyDeviceIds: ready), false)
        check("removed", ShareRoster.isListed(isRemoved: true, deviceId: "live-x9d", readyDeviceIds: ready), false)
        check("unknown", ShareRoster.isListed(isRemoved: false, deviceId: "ghost", readyDeviceIds: []), false)

        if failed != 0 {
            fputs("\(failed) failed\n", stderr)
            exit(1)
        }
    }
}
