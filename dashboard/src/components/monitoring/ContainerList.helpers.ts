export type StateFilter = 'all' | 'running' | 'stopped'

export function stateFilterCount(filter: StateFilter, total: number, running: number, stopped: number): number {
  switch (filter) {
    case 'running':
      return running
    case 'stopped':
      return stopped
    default:
      return total
  }
}

export function containerIconClassName(state: string): string {
  if (state === 'running') return 'bg-success-bg text-success-fg'
  if (state === 'exited' || state === 'dead') return 'bg-danger-bg text-danger-fg'
  return 'bg-warning-bg text-warning-fg'
}
