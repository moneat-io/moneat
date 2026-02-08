import {createFileRoute, redirect} from '@tanstack/react-router'

export const Route = createFileRoute('/privacy')({
  beforeLoad: () => {
    throw redirect({ to: '/legal/privacy' })
  },
  component: () => null,
})
