// Moneat - Mobile-First Error Monitoring Platform
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

/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './src/**/*.html',
  ],
  theme: {
    extend: {
      colors: {
        // Matches dashboard shadcn/ui neutral palette
        'background': '#ffffff',
        'foreground': '#0a0a0a',
        'muted': '#f5f5f5',
        'muted-foreground': '#737373',
        'border': '#e5e5e5',
        // Accent colors matching dashboard stats-card.tsx
        'moneat': {
          'error': '#ef4444',
          'warning': '#f59e0b',
          'info': '#3b82f6',
          'success': '#10b981',
          'primary': '#171717',
          'secondary': '#737373',
          'accent-blue': '#3b82f6',
          'accent-amber': '#f59e0b',
          'accent-emerald': '#10b981',
          'accent-violet': '#8b5cf6',
        },
      },
      borderRadius: {
        'lg': '8px',
        'xl': '12px',
      },
    },
  },
}
