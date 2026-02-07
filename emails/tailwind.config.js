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
