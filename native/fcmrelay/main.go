// fcmrelay holds the Firebase service-account key so FileApex clients do not have to.
// A client sends POST /v1/wake with its Firebase ID token; the relay checks the token, rate limits
// the user, accepts only FileApex wake types and forwards the message through FCM HTTP v1.
package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"strconv"
	"time"
)

func main() {
	accountFile := mustEnv("FCM_SERVICE_ACCOUNT_FILE")
	raw, err := os.ReadFile(accountFile)
	if err != nil {
		log.Fatalf("read service account: %v", err)
	}
	var account ServiceAccount
	if err := json.Unmarshal(raw, &account); err != nil || account.ProjectID == "" || account.PrivateKey == "" {
		log.Fatalf("service account file is not a Firebase admin key")
	}
	if account.TokenURI == "" {
		account.TokenURI = "https://oauth2.googleapis.com/token"
	}
	client := &http.Client{Timeout: 15 * time.Second}
	now := time.Now
	proof, err := newProofKey(now)
	if err != nil {
		log.Fatalf("proof key: %v", err)
	}
	admin := newAdmin(account, client, now)
	server := &Server{
		Verifier: &Verifier{
			ProjectID: account.ProjectID,
			Keys:      &googleKeys{URL: firebaseCertsURL, HTTP: client, Now: now},
			Now:       now,
		},
		Sender: &Sender{
			Account: account,
			HTTP:    client,
			SendURL: "https://fcm.googleapis.com/v1/projects/" + account.ProjectID + "/messages:send",
			Now:     now,
		},
		PerUser: newLimiter(envInt("RATE_PER_USER_PER_MIN", 60), envInt("RATE_PER_USER_BURST", 20), now),
		Global:  newLimiter(envInt("RATE_GLOBAL_PER_MIN", 3000), envInt("RATE_GLOBAL_BURST", 200), now),
		Devices: &DeviceAdmin{Store: admin, Identity: admin, Tokens: &CustomTokens{Account: account, Now: now}, Proof: proof, Now: now},
	}
	address := os.Getenv("LISTEN_ADDR")
	if address == "" {
		address = "127.0.0.1:8787"
	}
	log.Printf("fcmrelay for project %s listening on %s", account.ProjectID, address)
	httpServer := &http.Server{
		Addr:              address,
		Handler:           server.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	log.Fatal(httpServer.ListenAndServe())
}

func mustEnv(name string) string {
	value := os.Getenv(name)
	if value == "" {
		log.Fatalf("%s is required", name)
	}
	return value
}

func envInt(name string, fallback int) int {
	if value, err := strconv.Atoi(os.Getenv(name)); err == nil && value > 0 {
		return value
	}
	return fallback
}
