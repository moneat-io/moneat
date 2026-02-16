import {type ClassValue, clsx} from "clsx"
import {twMerge} from "tailwind-merge"
import { getNowDate } from './demo'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function formatRelativeTime(dateValue: string | number | undefined): string {
  if (!dateValue) return 'unknown'
  
  // Handle ClickHouse DateTime format (YYYY-MM-DD HH:MM:SS) as UTC
  let date: Date
  if (typeof dateValue === 'number') {
    date = new Date(dateValue)
  } else if (dateValue.includes('T')) {
    date = new Date(dateValue)
  } else {
    date = new Date(dateValue + ' UTC')
  }
  
  const now = getNowDate()
  const seconds = Math.floor((now.getTime() - date.getTime()) / 1000)

  if (seconds < 60) return 'just now'
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`
  if (seconds < 604800) return `${Math.floor(seconds / 86400)}d ago`
  return date.toLocaleDateString()
}
