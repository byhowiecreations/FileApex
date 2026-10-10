package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"fileapex.dev/tsnetbridge"
	"golang.org/x/term"
)

func main() {
	log.SetFlags(log.LstdFlags)
	resetFlag := flag.Bool("reset", false, "Forget the joined cluster and the Tailscale setup. The device id is kept.")
	setupFlag := flag.Bool("setup", false, "Show the setup menu")
	joinFlag := flag.Bool("join", false, "Listen for a pairing broadcast and join the local cluster")
	tsFlag := flag.Bool("ts", false, "Add Tailscale to a node that has already joined the local cluster")
	tailscaleFlag := flag.Bool("tailscale", false, "Add Tailscale to a node that has already joined the local cluster")
	flag.Parse()
	wantTailscale := *tsFlag || *tailscaleFlag

	dataDir := strings.TrimSpace(os.Getenv("FILEAPEX_DATA"))
	if dataDir == "" {
		dataDir = "/app/data"
	}
	if *resetFlag {
		log.Printf("Clearing cluster configuration. The device id is kept so a later pairing is the same node.")
		clearSavedCluster(dataDir)
	}

	fmt.Println("=== FileApex Daemon (cluster joiner) ===")
	node, err := newNode(dataDir)
	if err != nil {
		log.Fatalf("Startup failed: %v", err)
	}
	log.Printf("Saved state: identity=%t cluster=%t config=%t joined=%t removed=%t",
		fileExists(node.identityPath), fileExists(node.clusterPath), fileExists(node.configPath), node.isJoined(), node.wasRemoved())
	if err := applyEnv(node); err != nil {
		log.Fatalf("Startup failed: %v", err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	defer node.shutdown(2 * time.Second)

	cfg, cfgErr := loadConfig(node.configPath)
	if cfgErr != nil {
		log.Printf("Ignoring unreadable config: %v", cfgErr)
		cfg = nil
	}
	if mode, key := configuredTailscale(cfg); mode != "" {
		node.noteTailscale(mode, key)
	}

	switch {
	case node.wasRemoved() && !*setupFlag:
		if *joinFlag || wantTailscale {
			log.Fatalf("Removed from the cluster at version %d. Start with --reset, or --setup and choose 4, before pairing again.", node.removalVersion())
		}
		if err := runRemoved(ctx, node); err != nil && !errors.Is(err, context.Canceled) {
			log.Fatalf("Daemon stopped: %v", err)
		}
		return
	case wantTailscale:
		if !node.isJoined() {
			log.Fatal("Join the local cluster first (--join). Tailscale does not learn the cluster by itself.")
		}
		if !stdinIsTerminal() {
			log.Fatal("--tailscale needs a terminal to choose a browser link or a pre-auth key.")
		}
		if err := promptTailscale(node); err != nil {
			log.Fatalf("Tailscale setup failed: %v", err)
		}
		if err := runLocal(ctx, node, true); err != nil && !errors.Is(err, context.Canceled) {
			log.Fatalf("Daemon stopped: %v", err)
		}
		return
	case *setupFlag || (stdinIsTerminal() && !runningOnUnraid() && !*joinFlag && !explicitJoinMode() && !node.isJoined()):
		if !stdinIsTerminal() {
			log.Fatal("--setup needs a terminal.")
		}
		err := runMenu(ctx, node)
		if errors.Is(err, io.EOF) {
			log.Printf("No keyboard on this start. Listening for a pairing broadcast.")
			err = runLocal(ctx, node, node.isJoined())
		}
		if err != nil && !errors.Is(err, context.Canceled) {
			log.Fatalf("Daemon stopped: %v", err)
		}
		return
	default:
		if node.isJoined() {
			log.Printf("Already in a cluster. Resuming as %s.", node.deviceName())
		} else {
			log.Printf("No cluster saved. Listening for a pairing broadcast.")
		}
		if err := runLocal(ctx, node, node.isJoined()); err != nil && !errors.Is(err, context.Canceled) {
			log.Fatalf("Daemon stopped: %v", err)
		}
	}
}

func runMenu(ctx context.Context, node *Node) error {
	reader := bufio.NewReader(os.Stdin)
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		fmt.Println()
		fmt.Println("Select your connection mode:")
		fmt.Println("1) Join local cluster (listen for another device's pairing code)")
		fmt.Println("2) Connect to Tailscale via browser sign-in")
		fmt.Println("3) Enter Tailscale pre-auth key")
		fmt.Println("4) Reset config and start over")
		fmt.Print("Enter choice [1-4]: ")
		input, err := reader.ReadString('\n')
		if err != nil {
			return err
		}
		switch strings.TrimSpace(input) {
		case "1":
			if node.wasRemoved() {
				fmt.Println("This node was removed from the cluster. Choose 4 before pairing again.")
				continue
			}
			if node.isJoined() {
				fmt.Printf("Already paired as %s. Resuming.\n", node.deviceName())
				return runLocal(ctx, node, true)
			}
			fmt.Println("This node does not create a QR code or its own pairing code.")
			fmt.Println("On another device, open FileApex and leave the pairing screen up.")
			return runLocal(ctx, node, false)
		case "2", "3":
			if !node.isJoined() {
				fmt.Println("Pair on the local network first (choice 1). Tailscale is added after that and uses the roster from that join.")
				continue
			}
			if strings.TrimSpace(input) == "2" {
				node.saveTailscale("browser", "")
			} else {
				fmt.Print("Please paste your Tailscale pre-auth key: ")
				keyInput, err := reader.ReadString('\n')
				if err != nil {
					return err
				}
				authKey := strings.TrimSpace(keyInput)
				if authKey == "" {
					fmt.Println("No key entered.")
					continue
				}
				node.saveTailscale("auth_key", authKey)
			}
			return runLocal(ctx, node, true)
		case "4":
			fmt.Println("Clearing cluster configuration. The device id is kept.")
			node.resetCluster()
		default:
			fmt.Println("Invalid choice.")
		}
	}
}

func promptTailscale(node *Node) error {
	fmt.Println()
	fmt.Println("Tailscale is added on top of the local cluster. The roster stays the one learned at pairing.")
	fmt.Println("1) Browser sign-in URL")
	fmt.Println("2) Pre-auth key")
	fmt.Print("Enter choice [1-2]: ")
	input, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil {
		return err
	}
	switch strings.TrimSpace(input) {
	case "1":
		node.saveTailscale("browser", "")
		return nil
	case "2":
		fmt.Print("Please paste your Tailscale pre-auth key: ")
		keyInput, err := bufio.NewReader(os.Stdin).ReadString('\n')
		if err != nil {
			return err
		}
		authKey := strings.TrimSpace(keyInput)
		if authKey == "" {
			return fmt.Errorf("no pre-auth key entered")
		}
		node.saveTailscale("auth_key", authKey)
		return nil
	default:
		return fmt.Errorf("invalid choice %q", strings.TrimSpace(input))
	}
}

func applyEnv(node *Node) error {
	if name := strings.TrimSpace(os.Getenv("FILEAPEX_NAME")); name != "" {
		node.setName(name)
	}
	if raw := strings.TrimSpace(os.Getenv("FILEAPEX_PORT")); raw != "" {
		port, err := strconv.Atoi(raw)
		if err != nil || port < 0 || port > 65535 {
			return fmt.Errorf("FILEAPEX_PORT must be 0-65535")
		}
		node.port = port
	}
	if ip := strings.TrimSpace(os.Getenv("FILEAPEX_ADVERTISE_IP")); ip != "" {
		if net.ParseIP(ip) == nil {
			return fmt.Errorf("FILEAPEX_ADVERTISE_IP is not an IP address")
		}
		node.forceAdvertise = ip
	}
	if inbox := strings.TrimSpace(os.Getenv("FILEAPEX_INBOX")); inbox != "" {
		if err := node.setInbox(inbox); err != nil {
			return err
		}
	}
	return nil
}

func explicitJoinMode() bool {
	switch strings.ToLower(strings.TrimSpace(os.Getenv("FILEAPEX_MODE"))) {
	case "1", "local", "join":
		return true
	default:
		return false
	}
}

func stdinIsTerminal() bool {
	return term.IsTerminal(int(os.Stdin.Fd()))
}

// Unraid's kernel release ends in "-Unraid". Containers share the host kernel,
// so a normal Unraid start can be recognized without a terminal.
func runningOnUnraid() bool {
	return unraidKernel(kernelRelease())
}

func unraidKernel(release string) bool {
	return strings.Contains(strings.ToLower(release), "unraid")
}

func kernelRelease() string {
	body, err := os.ReadFile("/proc/version")
	if err != nil {
		return ""
	}
	return string(body)
}

func runRemoved(ctx context.Context, node *Node) error {
	log.Printf("Removed from the cluster at version %d. This node will not pair or announce again until --reset, or --setup and choice 4.", node.removalVersion())
	if err := node.serve(); err != nil {
		return err
	}
	log.Printf("Backup folder: %s", node.inboxDir())
	<-ctx.Done()
	return ctx.Err()
}

func runLocal(ctx context.Context, node *Node, resumed bool) error {
	if node.wasRemoved() {
		return runRemoved(ctx, node)
	}
	if err := node.serve(); err != nil {
		return err
	}
	go node.presenceLoop(ctx)
	go node.tlsAnnounceLoop(ctx)
	if ip := node.advertiseIP(); ip == "" {
		log.Printf("No LAN address found. Other devices will not be able to send the roster or files back.")
	} else {
		log.Printf("LAN address %s:%d", ip, node.listenPort())
	}
	log.Printf("Backup folder: %s", node.inboxDir())
	log.Printf("Received files are stored in that folder on the data volume mounted into the container.")
	bringUpTailscale(ctx, node)
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if !node.isJoined() {
			if node.wasRemoved() {
				log.Printf("Removed from the cluster at version %d. This node will not pair or announce again until --reset, or --setup and choice 4.", node.removalVersion())
				<-ctx.Done()
				return ctx.Err()
			}
			if err := node.listenAndJoin(ctx); err != nil {
				return err
			}
			resumed = false
			bringUpTailscale(ctx, node)
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if !node.isJoined() {
			continue
		}
		if resumed {
			node.disambiguateAutoNameFromRoster()
			node.logRoster()
			go node.announceSelf(ctx, "SELF_METADATA")
			resumed = false
		}
		node.waitWhileJoined(ctx)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if node.wasRemoved() {
			log.Printf("Removed from the cluster at version %d. This node will not pair or announce again until --reset, or --setup and choice 4.", node.removalVersion())
			<-ctx.Done()
			return ctx.Err()
		}
		log.Printf("Left the cluster. Listening for a new pairing broadcast.")
	}
}

func (n *Node) listenAndJoin(ctx context.Context) error {
	log.Printf("Listening for a FileApex pairing broadcast on %s:%d.", multicastGroup, beaconPort)
	log.Printf("This node will not create a QR code or its own pairing code.")
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		beacon, err := waitForBeacon(ctx, n.deviceID(), n.allowLoopback)
		if err != nil {
			return err
		}
		log.Printf("Heard pairing code %s from %s at %s:%d", beacon.PairingCode, beacon.DeviceName, beacon.IPAddress, beacon.Port)
		pin := strings.TrimSpace(os.Getenv("FILEAPEX_PIN"))
		if beacon.PinRequired && pin == "" {
			if !stdinIsTerminal() {
				log.Printf("%s requires a PIN. Set FILEAPEX_PIN and it will be used on the next broadcast.", beacon.DeviceName)
				continue
			}
			fmt.Print("That device requires a PIN. Enter PIN: ")
			line, err := bufio.NewReader(os.Stdin).ReadString('\n')
			if err != nil {
				return err
			}
			pin = strings.TrimSpace(line)
			if pin == "" {
				log.Printf("No PIN entered. Still listening.")
				continue
			}
		}
		if err := n.handshake(ctx, beacon, pin); err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			log.Printf("Pairing did not finish: %v", err)
			log.Printf("Still listening for a pairing broadcast.")
			continue
		}
		return nil
	}
}

func (n *Node) presenceLoop(ctx context.Context) {
	ticker := time.NewTicker(10 * time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if n.isJoined() {
				n.announceSelf(ctx, "SELF_METADATA")
			}
		}
	}
}

func configuredTailscale(cfg *fileConfig) (mode, key string) {
	if cfg == nil {
		return "", ""
	}
	mode = strings.TrimSpace(cfg.Tailscale)
	if mode == "" {
		switch cfg.Mode {
		case "browser", "auth_key":
			mode = cfg.Mode
		}
	}
	return mode, strings.TrimSpace(cfg.AuthKey)
}

func clearSavedCluster(dataDir string) {
	_ = os.Remove(filepath.Join(dataDir, "config.json"))
	_ = os.Remove(filepath.Join(dataDir, "cluster.json"))
}

func bringUpTailscale(ctx context.Context, node *Node) {
	mode, key, port, ok := node.claimTailscaleStart()
	if !ok {
		return
	}
	host := tailscaleHost(node.deviceName())
	log.Printf("Starting Tailscale (%s) as %s. Tailnet port %d forwards to this cluster node.", mode, host, port)
	switch mode {
	case "browser":
		go func() {
			ip, err := tsnetbridge.Start("", node.dataDir, host, port)
			if err != nil {
				log.Printf("Tailscale did not start: %v", err)
				return
			}
			if ip != "" {
				log.Printf("Tailscale address %s", ip)
			}
		}()
		deadline := time.Now().Add(30 * time.Second)
		for time.Now().Before(deadline) {
			if ctx.Err() != nil {
				return
			}
			if authURL := tsnetbridge.AuthURL(); authURL != "" {
				fmt.Printf("\n>>> OPEN THIS LINK IN YOUR BROWSER TO LOG IN: <<<\n%s\n\n", authURL)
				return
			}
			if ip := tsnetbridge.SelfIPv4(); ip != "" {
				log.Printf("Tailscale address %s", ip)
				return
			}
			time.Sleep(time.Second)
		}
		log.Printf("Waiting for the Tailscale sign-in link.")
	default:
		ip, err := tsnetbridge.Start(key, node.dataDir, host, port)
		if err != nil {
			log.Printf("Tailscale did not start: %v", err)
			return
		}
		if ip == "" {
			ip = tsnetbridge.SelfIPv4()
		}
		if ip != "" {
			log.Printf("Tailscale address %s", ip)
		}
	}
}

func tailscaleHost(name string) string {
	name = strings.ToLower(strings.TrimSpace(name))
	var b strings.Builder
	for _, r := range name {
		switch {
		case r >= 'a' && r <= 'z', r >= '0' && r <= '9', r == '-':
			b.WriteRune(r)
		case r == ' ' || r == '_':
			b.WriteByte('-')
		}
	}
	out := strings.Trim(b.String(), "-")
	if out == "" {
		return "docker"
	}
	return out
}

func loadConfig(path string) (*fileConfig, error) {
	body, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var cfg fileConfig
	if err := json.Unmarshal(body, &cfg); err != nil {
		return nil, err
	}
	return &cfg, nil
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}
