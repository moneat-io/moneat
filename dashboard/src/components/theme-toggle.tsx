import { Moon, Sun } from 'lucide-react'
import { Button } from './ui/button'
import { useEffect, useState } from 'react'

export function ThemeToggle() {
  const [theme, setTheme] = useState<'dark' | 'light'>('dark')

  useEffect(() => {
    const root = window.document.documentElement
    const savedTheme = localStorage.getItem('theme') as 'dark' | 'light' | null
    
    if (savedTheme) {
      setTheme(savedTheme)
      root.classList.toggle('dark', savedTheme === 'dark')
    } else {
      // Default to dark theme
      setTheme('dark')
      root.classList.add('dark')
      localStorage.setItem('theme', 'dark')
    }
  }, [])

  const toggleTheme = () => {
    const newTheme = theme === 'dark' ? 'light' : 'dark'
    const root = window.document.documentElement
    
    root.classList.toggle('dark', newTheme === 'dark')
    setTheme(newTheme)
    localStorage.setItem('theme', newTheme)
  }

  return (
    <Button
      variant="ghost"
      size="icon"
      onClick={toggleTheme}
      aria-label="Toggle theme"
    >
      {theme === 'dark' ? (
        <Sun className="h-5 w-5" />
      ) : (
        <Moon className="h-5 w-5" />
      )}
    </Button>
  )
}
