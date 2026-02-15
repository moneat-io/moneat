/**
 * Strip ANSI escape codes from a string
 * Removes color codes like [34m, [0;39m, etc.
 */
export function stripAnsi(text: string | null | undefined): string {
  if (!text) return ''
  
  // ANSI escape code pattern
  // eslint-disable-next-line no-control-regex
  const ansiPattern = /\u001b\[[0-9;]*m|\x1b\[[0-9;]*m|\[0m|\[[0-9;]+m/g
  
  return text.replace(ansiPattern, '')
}
