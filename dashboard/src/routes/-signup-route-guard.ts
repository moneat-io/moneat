import {redirect} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {isDemo} from '@/lib/demo'
import {APP_OVERVIEW_SEARCH} from '@/lib/overview-route'

export async function ensureSignupRouteCanLoad() {
  if (!api.isAuthenticated()) {
    return
  }

  if (isDemo()) {
    await api.logout()
    return
  }

  throw redirect({ to: '/', search: APP_OVERVIEW_SEARCH })
}
