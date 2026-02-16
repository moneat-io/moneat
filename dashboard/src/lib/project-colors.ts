// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

// Generate a consistent color based on the first letter of the project name
export function getProjectColor(name: string): string {
  const firstLetter = name.charAt(0).toUpperCase()
  
  // Color mapping for each letter A-Z (using hex values for inline styles)
  const colorMap: Record<string, string> = {
    A: '#ef4444', // red-500
    B: '#f97316', // orange-500
    C: '#f59e0b', // amber-500
    D: '#eab308', // yellow-500
    E: '#84cc16', // lime-500
    F: '#22c55e', // green-500
    G: '#10b981', // emerald-500
    H: '#14b8a6', // teal-500
    I: '#06b6d4', // cyan-500
    J: '#0ea5e9', // sky-500
    K: '#3b82f6', // blue-500
    L: '#6366f1', // indigo-500
    M: '#8b5cf6', // violet-500
    N: '#a855f7', // purple-500
    O: '#d946ef', // fuchsia-500
    P: '#ec4899', // pink-500
    Q: '#f43f5e', // rose-500
    R: '#dc2626', // red-600
    S: '#ea580c', // orange-600
    T: '#d97706', // amber-600
    U: '#16a34a', // green-600
    V: '#0d9488', // teal-600
    W: '#2563eb', // blue-600
    X: '#4f46e5', // indigo-600
    Y: '#9333ea', // purple-600
    Z: '#db2777', // pink-600
  }
  
  // Default color for non-alphabetic characters
  return colorMap[firstLetter] || '#6b7280' // gray-500
}

export function getProjectInitial(name: string): string {
  return name.charAt(0).toUpperCase()
}
