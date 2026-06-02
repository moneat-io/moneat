/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ["class"],
  content: ['./src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        border: 'hsl(var(--border))',
        background: 'hsl(var(--background))',
        foreground: 'hsl(var(--foreground))',
        card: {
          DEFAULT: 'hsl(var(--card))',
          foreground: 'hsl(var(--card-foreground))'
        },
        popover: {
          DEFAULT: 'hsl(var(--popover))',
          foreground: 'hsl(var(--popover-foreground))'
        },
        primary: {
          DEFAULT: 'hsl(var(--primary))',
          foreground: 'hsl(var(--primary-foreground))'
        },
        secondary: {
          DEFAULT: 'hsl(var(--secondary))',
          foreground: 'hsl(var(--secondary-foreground))'
        },
        muted: {
          DEFAULT: 'hsl(var(--muted))',
          foreground: 'hsl(var(--muted-foreground))'
        },
        accent: {
          DEFAULT: 'hsl(var(--accent))',
          foreground: 'hsl(var(--accent-foreground))'
        },
        destructive: {
          DEFAULT: 'hsl(var(--destructive))',
          foreground: 'hsl(var(--destructive-foreground))'
        },
        input: 'hsl(var(--input))',
        ring: 'hsl(var(--ring))',
        // Semantic status — one status language (fg / bg / border / solid) shared app-wide.
        success: {
          DEFAULT: 'hsl(var(--success-fg))',
          fg: 'hsl(var(--success-fg))',
          bg: 'hsl(var(--success-bg))',
          border: 'hsl(var(--success-border))',
          solid: 'hsl(var(--success-solid))'
        },
        warning: {
          DEFAULT: 'hsl(var(--warning-fg))',
          fg: 'hsl(var(--warning-fg))',
          bg: 'hsl(var(--warning-bg))',
          border: 'hsl(var(--warning-border))',
          solid: 'hsl(var(--warning-solid))'
        },
        danger: {
          DEFAULT: 'hsl(var(--danger-fg))',
          fg: 'hsl(var(--danger-fg))',
          bg: 'hsl(var(--danger-bg))',
          border: 'hsl(var(--danger-border))',
          solid: 'hsl(var(--danger-solid))'
        },
        info: {
          DEFAULT: 'hsl(var(--info-fg))',
          fg: 'hsl(var(--info-fg))',
          bg: 'hsl(var(--info-bg))',
          border: 'hsl(var(--info-border))',
          solid: 'hsl(var(--info-solid))'
        },
        chart: {
          '1': 'hsl(var(--chart-1))',
          '2': 'hsl(var(--chart-2))',
          '3': 'hsl(var(--chart-3))',
          '4': 'hsl(var(--chart-4))',
          '5': 'hsl(var(--chart-5))',
          '6': 'hsl(var(--chart-6))',
          '7': 'hsl(var(--chart-7))',
          '8': 'hsl(var(--chart-8))',
          '9': 'hsl(var(--chart-9))',
          '10': 'hsl(var(--chart-10))'
        }
      },
      fontFamily: {
        sans: [
          'Inter',
          'ui-sans-serif',
          'system-ui',
          '-apple-system',
          'BlinkMacSystemFont',
          '"Segoe UI"',
          'Roboto',
          'Helvetica',
          'Arial',
          'sans-serif'
        ]
      },
      borderRadius: {
        lg: 'var(--radius)',
        md: 'calc(var(--radius) - 2px)',
        sm: 'calc(var(--radius) - 4px)'
      }
    }
  },
  plugins: [require("tailwindcss-animate"), require("@tailwindcss/typography")],
}
