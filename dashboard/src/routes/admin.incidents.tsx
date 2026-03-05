import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/admin/incidents')({
  component: RouteComponent,
})

function RouteComponent() {
  return <div>Hello "/admin/incidents"!</div>
}
