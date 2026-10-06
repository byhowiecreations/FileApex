# FileApex backup node. It joins a LAN cluster by listening for another
# device's pairing broadcast. It does not create a QR code or its own code.
#
#   docker build --platform linux/amd64 -t fileapex/fileapex-daemon:latest .
#
# Received files land in /app/data/inbox on the mounted data volume. Other
# devices must be able to reach the address this container advertises.
# FILEAPEX_NAME, FILEAPEX_PORT, FILEAPEX_INBOX, FILEAPEX_ADVERTISE_IP, and
# FILEAPEX_PIN override defaults.
# A terminal shows the setup menu; without one, the container listens to join.
# --join listens immediately. --setup reopens the menu (choice 4 resets).
# --tailscale (or --ts) adds Tailscale after a local join. --reset clears the
# cluster and Tailscale setup and keeps the device id.

# Stage 1: Build the Go binary
FROM golang:1.27-alpine AS builder
WORKDIR /app

# Copy the entire native/tsnet folder (which includes go.mod and the daemon subdirectory)
COPY native/tsnet/ ./

# Change into the daemon folder where main.go lives and build
WORKDIR /app/daemon
RUN CGO_ENABLED=0 GOOS=linux go build -o /app/fileapex-daemon .

# Stage 2: Runtime Image
FROM alpine:latest
WORKDIR /app

RUN apk --no-cache add ca-certificates

# Copy the compiled daemon binary from Stage 1
COPY --from=builder /app/fileapex-daemon /app/fileapex-daemon

# Cluster identity, roster, and received backups live on this volume.
RUN mkdir -p /app/data/inbox
VOLUME [ "/app/data" ]
ENV FILEAPEX_DATA=/app/data
EXPOSE 8080/tcp
EXPOSE 8891/udp

# Configure entrypoint so flags can be passed natively
ENTRYPOINT ["/app/fileapex-daemon"]
CMD []