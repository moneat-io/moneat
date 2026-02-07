package config

import (
	"os"
	"strconv"
	"time"
)

// Config holds the agent configuration
type Config struct {
	// API endpoint URL (e.g., https://api.moneat.io)
	MoneatURL string

	// Agent authentication key (from dashboard)
	AgentKey string

	// How often to collect and send metrics (overridden by server)
	PollInterval time.Duration

	// API endpoint path
	IngestPath string
}

// LoadFromEnv loads configuration from environment variables
func LoadFromEnv() *Config {
	pollInterval := 60 * time.Second
	if val := os.Getenv("POLL_INTERVAL"); val != "" {
		if seconds, err := strconv.Atoi(val); err == nil {
			pollInterval = time.Duration(seconds) * time.Second
		}
	}

	return &Config{
		MoneatURL:    getEnv("MONEAT_URL", "https://api.moneat.io"),
		AgentKey:     getEnv("MONEAT_KEY", ""),
		PollInterval: pollInterval,
		IngestPath:   "/v1/monitor/ingest",
	}
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}
