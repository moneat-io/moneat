/**
 * Sidebar navigation configuration
 * 
 * Defines which sidebar items can be hidden/shown by users
 * and provides labels for the settings UI.
 */

import { LucideIcon } from 'lucide-react';

export interface SidebarItem {
  key: string;
  label: string;
  icon?: LucideIcon;
}

import {
  Activity,
  AlertCircle,
  BarChart3,
  Bell,
  Brain,
  Flag,
  Globe,
  LayoutDashboard,
  LineChart,
  MessageSquare,
  Package,
  Play,
  ScrollText,
  Server,
  Timer,
} from 'lucide-react';

// Configurable sidebar items (can be hidden by user)
// Note: 'dashboard', 'admin' and 'settings' are always visible and not included here
export const CONFIGURABLE_SIDEBAR_ITEMS: SidebarItem[] = [
  { key: 'performance', label: 'Performance', icon: Timer },
  { key: 'issues', label: 'Issues', icon: AlertCircle },
  { key: 'logs', label: 'Logs', icon: ScrollText },
  { key: 'replays', label: 'Replays', icon: Play },
  { key: 'feedback', label: 'Feedback', icon: MessageSquare },
  { key: 'releases', label: 'Releases', icon: Package },
  { key: 'ai', label: 'AI Monitoring', icon: Brain },
  { key: 'usage-insights', label: 'Usage Insights', icon: LineChart },
  { key: 'uptime', label: 'Uptime', icon: Activity },
  { key: 'status-pages', label: 'Status Pages', icon: Globe },
  { key: 'monitoring', label: 'Monitoring', icon: Server },
  { key: 'analytics', label: 'Analytics', icon: BarChart3 },
  { key: 'on-call', label: 'On-Call', icon: Bell },
  { key: 'dashboards', label: 'Dashboards', icon: LayoutDashboard },
  { key: 'feature-flags', label: 'Feature Flags', icon: Flag },
];

// Always visible items (not configurable)
export const ALWAYS_VISIBLE_ITEMS = ['overview', 'admin', 'settings'];

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
