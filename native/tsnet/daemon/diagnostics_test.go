package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestParseOSReleasePrettyName(t *testing.T) {
	text := "NAME=\"Alpine Linux\"\nPRETTY_NAME=\"Alpine Linux v3.20\"\nID=alpine\n"
	if got := parseOSReleasePrettyName(text); got != "Alpine Linux v3.20" {
		t.Fatalf("got %q", got)
	}
	if got := parseOSReleasePrettyName("ID=alpine\n"); got != "" {
		t.Fatalf("got %q", got)
	}
}

func TestParseCPUModel(t *testing.T) {
	if got := parseCPUModel("processor\t: 0\nmodel name\t: Intel(R) N100\n"); got != "Intel(R) N100" {
		t.Fatalf("got %q", got)
	}
	if got := parseCPUModel("processor : 0\nModel : Raspberry Pi 5\n"); got != "Raspberry Pi 5" {
		t.Fatalf("got %q", got)
	}
	if got := parseCPUModel("processor : 0\nBogoMIPS : 108.00\n"); got != "" {
		t.Fatalf("got %q", got)
	}
}

func TestParseUptimeAndMeminfoAndLoad(t *testing.T) {
	if ms, ok := parseUptimeMs("12345.67 99999.00\n"); !ok || ms != 12345670 {
		t.Fatalf("got %d %v", ms, ok)
	}
	if _, ok := parseUptimeMs("nope"); ok {
		t.Fatal("expected failure")
	}
	if kb, ok := parseMeminfoKB("MemTotal:       2048 kB\nMemAvailable:   1024 kB\n", "MemAvailable"); !ok || kb != 1024*1024 {
		t.Fatalf("got %d %v", kb, ok)
	}
	if got := cpuLoadLabel("0.50 0.40 0.30 1/200 99\n", 2); got != "CPU load 25%" {
		t.Fatalf("got %q", got)
	}
}

func TestCollectDiagnosticsFillsIdentity(t *testing.T) {
	snap := collectDiagnostics(t.TempDir())
	if snap.Platform == "" || snap.Processor.Architecture == "" || snap.CollectedAtEpochMs == 0 {
		t.Fatalf("incomplete snapshot: %+v", snap)
	}
}

func TestReadPowerSupplyWithoutBatteryIsAC(t *testing.T) {
	got := readPowerSupply(t.TempDir())
	if got.ChargingState != "AC" || got.LevelPercent != nil {
		t.Fatalf("got %+v", got)
	}
	if missing := readPowerSupply(filepath.Join(t.TempDir(), "absent")); missing.ChargingState != "AC" {
		t.Fatalf("got %+v", missing)
	}
}

func TestReadPowerSupplyReadsBattery(t *testing.T) {
	dir := t.TempDir()
	bat := filepath.Join(dir, "BAT0")
	if err := os.MkdirAll(bat, 0o755); err != nil {
		t.Fatal(err)
	}
	for name, body := range map[string]string{"type": "Battery\n", "capacity": "64\n", "status": "Discharging\n"} {
		if err := os.WriteFile(filepath.Join(bat, name), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	got := readPowerSupply(dir)
	if got.ChargingState != "Discharging" || got.LevelPercent == nil || *got.LevelPercent != 64 {
		t.Fatalf("got %+v", got)
	}
}
