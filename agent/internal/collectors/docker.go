package collectors

// collectDockerMetrics collects metrics from Docker containers
// TODO: Implement Docker metrics collection
// For now, returning empty list to avoid Docker SDK dependency issues
func collectDockerMetrics() ([]ContainerMetrics, error) {
	// Will be implemented in Phase 1.1 with proper Docker SDK integration
	return []ContainerMetrics{}, nil
}

