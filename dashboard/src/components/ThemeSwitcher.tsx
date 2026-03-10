import { Moon, Sun, Palette, CloudMoon, Leaf, Sunset, Gamepad2, Check, Newspaper, Monitor } from 'lucide-react'
import { Button } from './ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from './ui/dropdown-menu'
import { useEffect, useState } from 'react'

type Theme = 'light' | 'dark' | 'midnight' | 'forest' | 'sunset' | 'gamer' | 'retro' | 'retro-dark'

const VT323_FONT_ID = 'vt323-font'

function loadGamerFont() {
  if (document.getElementById(VT323_FONT_ID)) return
  const link = document.createElement('link')
  link.id = VT323_FONT_ID
  link.rel = 'stylesheet'
  link.href = 'https://fonts.googleapis.com/css2?family=VT323&display=swap'
  document.head.appendChild(link)
}

export function ThemeSwitcher() {
  const [theme, setTheme] = useState<Theme>(() => {
    const saved = localStorage.getItem('theme') as Theme | null
    return saved || 'dark'
  })

  const applyTheme = (newTheme: Theme) => {
    const root = document.documentElement
    
    // Remove all theme classes
    root.classList.remove('light', 'dark', 'theme-midnight', 'theme-forest', 'theme-sunset', 'theme-gamer', 'theme-retro', 'theme-retro-dark')

    if (newTheme === 'light') {
      // No class for light mode (default)
    } else if (newTheme === 'dark') {
      root.classList.add('dark')
    } else if (newTheme === 'retro') {
      root.classList.add('theme-retro')
    } else if (newTheme === 'retro-dark') {
      root.classList.add('dark', 'theme-retro-dark')
    } else {
      root.classList.add('dark', `theme-${newTheme}`)
      if (newTheme === 'gamer') loadGamerFont()
    }
  }

  useEffect(() => {
    applyTheme(theme)
  }, [theme])

  const handleThemeChange = (newTheme: Theme) => {
    setTheme(newTheme)
    applyTheme(newTheme)
    localStorage.setItem('theme', newTheme)
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label="Select theme">
          <Palette className="h-5 w-5" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem onClick={() => handleThemeChange('light')}>
          <Sun className="mr-2 h-4 w-4" />
          <span>Light</span>
          {theme === 'light' && <Check className="ml-auto h-4 w-4" />}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => handleThemeChange('dark')}>
          <Moon className="mr-2 h-4 w-4" />
          <span>Dark</span>
          {theme === 'dark' && <Check className="ml-auto h-4 w-4" />}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => handleThemeChange('midnight')}>
          <CloudMoon className="mr-2 h-4 w-4" />
          <span>Midnight</span>
          {theme === 'midnight' && <Check className="ml-auto h-4 w-4" />}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => handleThemeChange('forest')}>
          <Leaf className="mr-2 h-4 w-4" />
          <span>Forest</span>
          {theme === 'forest' && <Check className="ml-auto h-4 w-4" />}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => handleThemeChange('sunset')}>
          <Sunset className="mr-2 h-4 w-4" />
          <span>Sunset</span>
          {theme === 'sunset' && <Check className="ml-auto h-4 w-4" />}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => handleThemeChange('gamer')}>
          <Gamepad2 className="mr-2 h-4 w-4" />
          <span>Gamer</span>
          {theme === 'gamer' && <Check className="ml-auto h-4 w-4" />}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => handleThemeChange('retro')}>
          <Newspaper className="mr-2 h-4 w-4" />
          <span>Retro</span>
          {theme === 'retro' && <Check className="ml-auto h-4 w-4" />}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => handleThemeChange('retro-dark')}>
          <Monitor className="mr-2 h-4 w-4" />
          <span>Retro Dark</span>
          {theme === 'retro-dark' && <Check className="ml-auto h-4 w-4" />}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
