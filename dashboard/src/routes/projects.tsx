import { createFileRoute, redirect } from '@tanstack/react-router'
import { api } from '@/lib/api'

export const Route = createFileRoute('/projects')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
  },
  component: ProjectsPage,
})

function ProjectsPage() {
  return (
    <div className="p-6">
      <h1 className="text-2xl font-bold mb-4">Projects</h1>
      <p className="text-muted-foreground">Projects page coming soon...</p>
    </div>
  )
}
