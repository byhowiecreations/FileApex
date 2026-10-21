# FileApex FCM relay. Build from this folder:
#   docker build --platform linux/arm64 -t fileapex/fcmrelay:latest .     (Oracle Ampere A1)
#   docker build --platform linux/amd64 -t fileapex/fcmrelay:latest .     (Oracle AMD micro)
FROM golang:1.27-alpine AS builder
WORKDIR /src
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /fcmrelay .

FROM alpine:latest
RUN apk --no-cache add ca-certificates && adduser -D -u 10001 relay
USER relay
COPY --from=builder /fcmrelay /fcmrelay
ENV LISTEN_ADDR=0.0.0.0:8787
EXPOSE 8787
ENTRYPOINT ["/fcmrelay"]
