import { createFileRoute, redirect } from '@tanstack/react-router'
import { api } from '@/lib/api'

export const Route = createFileRoute('/settings')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
  },
  component: SettingsPage,
})

function SettingsPage() {
  return (
    <div className="p-6">
      <h1 className="text-2xl font-bold mb-4">Settings</h1>
      <p className="text-muted-foreground">Settings page coming soon...</p>
    </div>
  )
}
