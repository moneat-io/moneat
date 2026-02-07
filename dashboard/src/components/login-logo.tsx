import {Logo} from '@/components/logo'
import {cn} from '@/lib/utils'

interface LoginLogoProps {
  className?: string
}

export function LoginLogo({ className }: LoginLogoProps) {
  return <Logo className={cn('h-8', className)} />
}
