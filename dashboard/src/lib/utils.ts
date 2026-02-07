import {type ClassValue, clsx} from "clsx"
import {twMerge} from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function formatRelativeTime(dateString: string | undefined): string {
  if (!dateString) return 'unknown'
  
  // Handle ClickHouse DateTime format (YYYY-MM-DD HH:MM:SS) as UTC
  let date: Date
  if (dateString.includes('T')) {
    // ISO format with timezone
    date = new Date(dateString)
  } else {
    // ClickHouse format without timezone - treat as UTC
    date = new Date(dateString + ' UTC')
  }
  
  const now = new Date()
  const seconds = Math.floor((now.getTime() - date.getTime()) / 1000)

  if (seconds < 60) return 'just now'
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`
  if (seconds < 604800) return `${Math.floor(seconds / 86400)}d ago`
  return date.toLocaleDateString()
}
