package transport

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/moneat/agent/internal/collectors"
	"github.com/moneat/agent/internal/config"
)

// IngestResponse is the response from the ingest endpoint
type IngestResponse struct {
	Success        bool  `json:"success"`
	IntervalSeconds int  `json:"interval_seconds"` // Server-controlled interval in seconds
	Message        string `json:"message,omitempty"`
}

// Client handles communication with the Moneat API
type Client struct {
	config     *config.Config
	httpClient *http.Client
}

// NewClient creates a new transport client
func NewClient(cfg *config.Config) *Client {
	return &Client{
		config: cfg,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
		},
	}
}

// SendMetrics sends metrics to the Moneat API
func (c *Client) SendMetrics(metrics *collectors.SystemMetrics) (*IngestResponse, error) {
	// Serialize to JSON
	jsonData, err := json.Marshal(metrics)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal metrics: %w", err)
	}
	
	// Compress with gzip
	var buf bytes.Buffer
	gzipWriter := gzip.NewWriter(&buf)
	if _, err := gzipWriter.Write(jsonData); err != nil {
		return nil, fmt.Errorf("failed to compress metrics: %w", err)
	}
	if err := gzipWriter.Close(); err != nil {
		return nil, fmt.Errorf("failed to close gzip writer: %w", err)
	}
	
	// Create request
	url := c.config.MoneatURL + c.config.IngestPath
	req, err := http.NewRequest("POST", url, &buf)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}
	
	// Set headers
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Content-Encoding", "gzip")
	req.Header.Set("Authorization", "Bearer "+c.config.AgentKey)
	req.Header.Set("User-Agent", "moneat-agent/"+collectors.AgentVersion)
	
	// Send request
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("failed to send request: %w", err)
	}
	defer resp.Body.Close()
	
	// Read response
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response: %w", err)
	}
	
	// Check status code
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("server returned %d: %s", resp.StatusCode, string(body))
	}
	
	// Parse response
	var ingestResp IngestResponse
	if err := json.Unmarshal(body, &ingestResp); err != nil {
		return nil, fmt.Errorf("failed to parse response: %w", err)
	}
	
	return &ingestResp, nil
}
