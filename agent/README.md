# Moneat Agent

Lightweight monitoring agent that collects system and container metrics and sends them to the Moneat platform.

## Quick Start

```bash
docker run -d --name moneat-agent \
  --restart unless-stopped \
  -v /var/run/docker.sock:/var/run/docker.sock:ro \
  -e MONEAT_KEY="<your-agent-key>" \
  -e MONEAT_URL="https://api.moneat.dev" \
  ghcr.io/moneat/agent:latest
```

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `MONEAT_KEY` | Yes | - | Agent authentication key (from dashboard) |
| `MONEAT_URL` | No | `https://api.moneat.dev` | Moneat API endpoint |
| `POLL_INTERVAL` | No | `60` | Initial poll interval in seconds (server-controlled) |

## Collected Metrics

- **CPU**: Per-core and aggregate usage percentage
- **Memory**: Total, used, available, swap usage
- **Disk**: Usage and I/O throughput
- **Network**: Bytes sent/received across all interfaces
- **Load**: 1, 5, and 15-minute load averages
- **Temperature**: Maximum temperature across all sensors
- **GPU**: Nvidia, AMD, and Intel GPU utilization (if available)
- **Battery**: Charge percentage (if available)
- **Docker**: Per-container CPU, memory, and network usage

## Docker Socket

The agent needs read-only access to `/var/run/docker.sock` to collect container metrics. If you don't want to monitor containers, you can omit the volume mount.

## Architecture

The agent uses a **push-based** model:

1. Collects metrics from the system (reading `/proc`, calling APIs, etc.)
2. Serializes to JSON and compresses with gzip
3. POSTs to the Moneat API with agent key authentication
4. Server responds with the next poll interval (tier-based)

This design works behind NAT/firewalls without requiring any port forwarding.

## Building from Source

```bash
# Build binary
go build -o moneat-agent ./cmd/moneat-agent

# Build Docker image
docker build -t moneat-agent .

# Multi-arch build
docker buildx build --platform linux/amd64,linux/arm64 -t moneat-agent .
```

## Supported Platforms

- Linux (amd64, arm64)
- macOS (limited metrics - network, CPU, memory)
- Windows WSL (same as Linux)

## License

MIT
