import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Shield} from 'lucide-react'

export function SsoTab() {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <Shield className="h-4 w-4" />
          Single Sign-On (SSO)
        </CardTitle>
      </CardHeader>
      <CardContent>
        <p className="text-sm text-muted-foreground">
          SSO configuration is available in Moneat Enterprise. Visit{' '}
          <a href="https://moneat.io/enterprise" className="underline hover:text-foreground" target="_blank" rel="noopener noreferrer">
            moneat.io/enterprise
          </a>{' '}
          to learn more.
        </p>
      </CardContent>
    </Card>
  )
}
