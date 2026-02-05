/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './src/**/*.html',
  ],
  theme: {
    extend: {
      colors: {
        'moneat': {
          'error': '#ef4444',
          'warning': '#f59e0b',
          'info': '#3b82f6',
          'success': '#10b981',
          'primary': '#0f172a',
          'secondary': '#64748b',
        },
      },
    },
  },
}
