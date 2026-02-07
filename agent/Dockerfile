# Build stage
FROM golang:1.23-alpine AS builder

# Install build dependencies
RUN apk add --no-cache git

WORKDIR /build

# Copy go mod files
COPY go.mod ./
RUN go mod download

# Copy source code
COPY . .

# Build the binary
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -ldflags '-w -s' \
    -o moneat-agent ./cmd/moneat-agent

# Runtime stage
FROM alpine:3.19

# Install runtime dependencies for metric collection
RUN apk add --no-cache \
    ca-certificates \
    smartmontools \
    lm-sensors

# Copy binary from builder
COPY --from=builder /build/moneat-agent /usr/local/bin/moneat-agent

# Run as non-root user
RUN addgroup -S moneat && adduser -S moneat -G moneat
USER moneat

ENTRYPOINT ["/usr/local/bin/moneat-agent"]
