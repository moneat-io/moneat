export function isNotFoundError(error: unknown): boolean {
  if (typeof error !== 'object' || error === null || !('status' in error)) return false
  return (error as {status?: unknown}).status === 404
}
