/**
 * Sidebar navigation configuration
 * 
 * Defines which sidebar items can be hidden/shown by users
 * and provides labels for the settings UI.
 */

export interface SidebarItem {
  key: string;
  label: string;
  icon?: string;
}

// Configurable sidebar items (can be hidden by user)
// Note: 'admin' and 'settings' are always visible and not included here
export const CONFIGURABLE_SIDEBAR_ITEMS: SidebarItem[] = [
  { key: 'dashboard', label: 'Dashboard' },
  { key: 'performance', label: 'Performance' },
  { key: 'issues', label: 'Issues' },
  { key: 'logs', label: 'Logs' },
  { key: 'replays', label: 'Replays' },
  { key: 'feedback', label: 'Feedback' },
  { key: 'releases', label: 'Releases' },
  { key: 'ai', label: 'AI Assistant' },
  { key: 'uptime', label: 'Uptime' },
  { key: 'status-pages', label: 'Status Pages' },
  { key: 'monitoring', label: 'Monitoring' },
  { key: 'on-call', label: 'On-Call' },
];

// Always visible items (not configurable)
export const ALWAYS_VISIBLE_ITEMS = ['admin', 'settings'];

/**
 * Check if a sidebar item should be visible based on user preferences
 */
export function isSidebarItemVisible(itemKey: string, hiddenItems: string[]): boolean {
  // Always show non-configurable items
  if (ALWAYS_VISIBLE_ITEMS.includes(itemKey)) {
    return true;
  }
  
  // Show configurable items only if not hidden
  return !hiddenItems.includes(itemKey);
}

/**
 * Get all sidebar item keys (configurable only)
 */
export function getAllSidebarItemKeys(): string[] {
  return CONFIGURABLE_SIDEBAR_ITEMS.map(item => item.key);
}
