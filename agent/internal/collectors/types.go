package collectors

import (
	"time"
)

// SystemMetrics represents all metrics collected from a system
type SystemMetrics struct {
	Timestamp time.Time `json:"timestamp"`
	
	// CPU metrics
	CPUPercent   float64   `json:"cpu_percent"`
	CPUPerCore   []float64 `json:"cpu_per_core,omitempty"`
	
	// Memory metrics
	MemTotal     uint64 `json:"mem_total"`
	MemUsed      uint64 `json:"mem_used"`
	MemAvailable uint64 `json:"mem_available"`
	SwapTotal    uint64 `json:"swap_total"`
	SwapUsed     uint64 `json:"swap_used"`
	
	// Disk metrics
	DiskTotal      uint64 `json:"disk_total"`
	DiskUsed       uint64 `json:"disk_used"`
	DiskReadBytes  uint64 `json:"disk_read_bytes"`
	DiskWriteBytes uint64 `json:"disk_write_bytes"`
	
	// Network metrics
	NetRecvBytes uint64 `json:"net_recv_bytes"`
	NetSentBytes uint64 `json:"net_sent_bytes"`
	
	// Load average
	Load1  float64 `json:"load_1"`
	Load5  float64 `json:"load_5"`
	Load15 float64 `json:"load_15"`
	
	// Temperature (max across all sensors)
	TempMax float64 `json:"temp_max,omitempty"`
	
	// GPU metrics (if available)
	GPUPercent    float64 `json:"gpu_percent,omitempty"`
	GPUMemPercent float64 `json:"gpu_mem_percent,omitempty"`
	GPUPower      float64 `json:"gpu_power,omitempty"`
	
	// Battery (if available)
	BatteryPercent float64 `json:"battery_percent,omitempty"`
	
	// Container metrics
	Containers []ContainerMetrics `json:"containers,omitempty"`
	
	// System info
	Hostname string `json:"hostname"`
	OS       string `json:"os"`
	Arch     string `json:"arch"`
	Version  string `json:"agent_version"`
}

// ContainerMetrics represents metrics for a single container
type ContainerMetrics struct {
	Name         string  `json:"name"`
	ID           string  `json:"id"`
	Image        string  `json:"image"`
	Status       string  `json:"status"`
	CPUPercent   float64 `json:"cpu_percent"`
	MemUsed      uint64  `json:"mem_used"`
	MemLimit     uint64  `json:"mem_limit"`
	NetRecvBytes uint64  `json:"net_recv_bytes"`
	NetSentBytes uint64  `json:"net_sent_bytes"`
}

// Collector is the interface for metric collectors
type Collector interface {
	Collect() (*SystemMetrics, error)
}
