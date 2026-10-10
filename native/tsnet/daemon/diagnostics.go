package main

import (
	"net/http"
	"os"
	"runtime"
	"strconv"
	"strings"
	"time"
)

// Wire shape of the app's PeerDeviceDiagnostics. Unknown values are omitted so the app shows nothing
// rather than a wrong number.
type diagIdentity struct {
	Make           string `json:"make"`
	Model          string `json:"model"`
	KernelVersion  string `json:"kernelVersion"`
	OsBuildVersion string `json:"osBuildVersion"`
}

type diagProcessor struct {
	Architecture     string `json:"architecture"`
	Hardware         string `json:"hardware"`
	ActiveCoreCount  int    `json:"activeCoreCount,omitempty"`
	TotalCoreCount   int    `json:"totalCoreCount,omitempty"`
	FrequencyScaling string `json:"frequencyScaling,omitempty"`
}

type diagBattery struct {
	ChargingState string `json:"chargingState"`
	LevelPercent  *int   `json:"levelPercent,omitempty"`
}

type diagStorage struct {
	UsedBytes  *int64 `json:"usedBytes,omitempty"`
	TotalBytes *int64 `json:"totalBytes,omitempty"`
}

type diagNetwork struct {
	InterfaceType string `json:"interfaceType"`
}

type diagUptime struct {
	UptimeMs    *int64 `json:"uptimeMs,omitempty"`
	BootEpochMs *int64 `json:"bootEpochMs,omitempty"`
}

type diagThermal struct {
	State string `json:"state"`
}

type diagMemory struct {
	TotalBytes     *int64 `json:"totalBytes,omitempty"`
	AvailableBytes *int64 `json:"availableBytes,omitempty"`
	UsedBytes      *int64 `json:"usedBytes,omitempty"`
}

type diagSnapshot struct {
	CollectedAtEpochMs int64         `json:"collectedAtEpochMs"`
	Platform           string        `json:"platform"`
	Device             diagIdentity  `json:"device"`
	Processor          diagProcessor `json:"processor"`
	Battery            diagBattery   `json:"battery"`
	Storage            diagStorage   `json:"storage"`
	Network            diagNetwork   `json:"network"`
	Uptime             diagUptime    `json:"uptime"`
	Thermal            diagThermal   `json:"thermal"`
	Memory             diagMemory    `json:"memory"`
}

func (n *Node) handleDiagnostics(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !n.pass(w, r, "peer") {
		return
	}
	writeJSON(w, http.StatusOK, collectDiagnostics(n.inboxDir()))
}

func collectDiagnostics(storagePath string) diagSnapshot {
	now := time.Now()
	arch := runtime.GOARCH
	model := arch
	if _, err := os.Stat("/.dockerenv"); err == nil {
		model = "Docker container (" + arch + ")"
	}
	distro := "Linux"
	if name := parseOSReleasePrettyName(readFileString("/etc/os-release")); name != "" {
		distro = name
	}
	kernel := strings.TrimSpace(readFileString("/proc/sys/kernel/osrelease"))
	cores := runtime.NumCPU()
	hardware := parseCPUModel(readFileString("/proc/cpuinfo"))
	if hardware == "" {
		hardware = arch
	}

	snap := diagSnapshot{
		CollectedAtEpochMs: now.UnixMilli(),
		Platform:           "Linux",
		Device: diagIdentity{
			Make:           distro,
			Model:          model,
			KernelVersion:  kernel,
			OsBuildVersion: strings.TrimSpace("Linux " + kernel),
		},
		Processor: diagProcessor{
			Architecture:     arch,
			Hardware:         hardware,
			ActiveCoreCount:  cores,
			TotalCoreCount:   cores,
			FrequencyScaling: cpuLoadLabel(readFileString("/proc/loadavg"), cores),
		},
		Battery: readPowerSupply("/sys/class/power_supply"),
		Network: diagNetwork{InterfaceType: "Ethernet"},
		Thermal: diagThermal{State: "Not available"},
	}
	if total, free, ok := diskUsage(storagePath); ok {
		used := total - free
		snap.Storage = diagStorage{UsedBytes: &used, TotalBytes: &total}
	}
	if ms, ok := parseUptimeMs(readFileString("/proc/uptime")); ok {
		boot := now.UnixMilli() - ms
		snap.Uptime = diagUptime{UptimeMs: &ms, BootEpochMs: &boot}
	}
	if total, available, ok := readMemory(); ok {
		used := total - available
		snap.Memory = diagMemory{TotalBytes: &total, AvailableBytes: &available, UsedBytes: &used}
	}
	return snap
}

func readFileString(path string) string {
	body, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return string(body)
}

func parseOSReleasePrettyName(text string) string {
	for _, line := range strings.Split(text, "\n") {
		if strings.HasPrefix(line, "PRETTY_NAME=") {
			return strings.Trim(strings.TrimSpace(strings.TrimPrefix(line, "PRETTY_NAME=")), `"`)
		}
	}
	return ""
}

func parseCPUModel(cpuinfo string) string {
	for _, line := range strings.Split(cpuinfo, "\n") {
		key, value, found := strings.Cut(line, ":")
		if !found {
			continue
		}
		key = strings.TrimSpace(key)
		value = strings.TrimSpace(value)
		if value != "" && (strings.EqualFold(key, "model name") || strings.EqualFold(key, "Hardware") || strings.EqualFold(key, "Model")) {
			return value
		}
	}
	return ""
}

func parseUptimeMs(text string) (int64, bool) {
	field, _, _ := strings.Cut(strings.TrimSpace(text), " ")
	seconds, err := strconv.ParseFloat(field, 64)
	if err != nil || seconds <= 0 {
		return 0, false
	}
	return int64(seconds * 1000), true
}

func cpuLoadLabel(loadavg string, cores int) string {
	field, _, _ := strings.Cut(strings.TrimSpace(loadavg), " ")
	load, err := strconv.ParseFloat(field, 64)
	if err != nil || cores <= 0 {
		return ""
	}
	return "CPU load " + strconv.Itoa(int(load/float64(cores)*100+0.5)) + "%"
}

func parseMeminfoKB(text, key string) (int64, bool) {
	for _, line := range strings.Split(text, "\n") {
		if !strings.HasPrefix(line, key+":") {
			continue
		}
		fields := strings.Fields(strings.TrimPrefix(line, key+":"))
		if len(fields) == 0 {
			return 0, false
		}
		kb, err := strconv.ParseInt(fields[0], 10, 64)
		return kb * 1024, err == nil
	}
	return 0, false
}

// readMemory reports the container's cgroup limit when one is set, otherwise the host's memory.
func readMemory() (total, available int64, ok bool) {
	meminfo := readFileString("/proc/meminfo")
	total, totalOK := parseMeminfoKB(meminfo, "MemTotal")
	available, availableOK := parseMeminfoKB(meminfo, "MemAvailable")
	if !totalOK || !availableOK {
		return 0, 0, false
	}
	limit, err := strconv.ParseInt(strings.TrimSpace(readFileString("/sys/fs/cgroup/memory.max")), 10, 64)
	if err == nil && limit > 0 && limit < total {
		current, err := strconv.ParseInt(strings.TrimSpace(readFileString("/sys/fs/cgroup/memory.current")), 10, 64)
		if err == nil && current >= 0 && current <= limit {
			return limit, limit - current, true
		}
	}
	return total, available, true
}

// readPowerSupply reports the host's power state from sysfs, which containers share with the host.
// A machine with no battery (a NAS, a server, a VM) is on mains, so it reports AC with no level.
func readPowerSupply(dir string) diagBattery {
	out := diagBattery{ChargingState: "AC"}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return out
	}
	for _, entry := range entries {
		base := dir + "/" + entry.Name()
		if strings.TrimSpace(readFileString(base+"/type")) != "Battery" {
			continue
		}
		if capacity, err := strconv.Atoi(strings.TrimSpace(readFileString(base + "/capacity"))); err == nil {
			out.LevelPercent = &capacity
		}
		switch strings.ToLower(strings.TrimSpace(readFileString(base + "/status"))) {
		case "discharging":
			out.ChargingState = "Discharging"
		case "full":
			out.ChargingState = "Full"
		default:
			// Charging, or plugged in and not charging.
			out.ChargingState = "AC"
		}
		return out
	}
	return out
}
