import {createFileRoute, redirect} from '@tanstack/react-router'

export const Route = createFileRoute('/terms')({
  beforeLoad: () => {
    throw redirect({ to: '/legal/terms' })
  },
  component: () => null,
})
