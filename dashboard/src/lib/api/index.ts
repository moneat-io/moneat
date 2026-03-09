// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import { createApiClientCore } from './client'
import { authMethods } from './modules/auth'
import { projectsMethods } from './modules/projects'
import { issuesMethods } from './modules/issues'
import { userMethods } from './modules/user'
import { orgMembersMethods } from './modules/org-members'
import { logsMethods } from './modules/logs'
import { debuggerMethods } from './modules/debugger'
import { apmMethods } from './modules/apm'
import { profilesMethods } from './modules/profiles'
import { monitoringMethods } from './modules/monitoring'
import { performanceMethods } from './modules/performance'
import { releasesMethods } from './modules/releases'
import { replaysMethods } from './modules/replays'
import { feedbackMethods } from './modules/feedback'
import { authTokensMethods } from './modules/auth-tokens'
import { billingMethods } from './modules/billing'
import { adminMethods } from './modules/admin'
import { notificationsMethods } from './modules/notifications'
import { uptimeMethods } from './modules/uptime'
import { integrationsMethods } from './modules/integrations'
import { statusPagesMethods } from './modules/status-pages'
import { onCallMethods } from './modules/on-call'
import { aiMethods } from './modules/ai'
import { llmMethods } from './modules/llm'
import { analyticsMethods } from './modules/analytics'
import { dashboardsMethods } from './modules/dashboards'
import { syntheticsMethods } from './modules/synthetics'
import { logIndexesMethods } from './modules/log-indexes'

const core = createApiClientCore()

export const api = {
  get: core.get,
  ...authMethods(core),
  ...projectsMethods(core),
  ...issuesMethods(core),
  ...userMethods(core),
  ...orgMembersMethods(core),
  ...logsMethods(core),
  ...debuggerMethods(core),
  ...apmMethods(core),
  ...profilesMethods(core),
  ...monitoringMethods(core),
  ...performanceMethods(core),
  ...releasesMethods(core),
  ...replaysMethods(core),
  ...feedbackMethods(core),
  ...authTokensMethods(core),
  ...billingMethods(core),
  ...adminMethods(core),
  ...notificationsMethods(core),
  ...uptimeMethods(core),
  ...integrationsMethods(core),
  ...statusPagesMethods(core),
  ...onCallMethods(core),
  ...aiMethods(core),
  ...llmMethods(core),
  ...analyticsMethods(core),
  ...dashboardsMethods(core),
  ...syntheticsMethods(core),
  ...logIndexesMethods(core),
}

export { formatErrorForLogging } from './utils'
export { resetAuthRedirectForTesting } from './client'

export type * from './types'
