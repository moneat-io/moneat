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

import {beforeEach, describe, expect, it, vi} from 'vitest'
import type {ApiClientCore} from '../../client'
import {alertRoutesMethods} from '../alert-routes'
import {createEmptyAlertRouteForm, formToRequest} from '@/components/on-call/alert-routes/alertRouteModel'

describe('alert route and group API methods', () => {
  const request = vi.fn().mockResolvedValue({})
  const core = {
    API_BASE: '/v1',
    request,
  } as unknown as ApiClientCore
  const api = alertRoutesMethods(core)
  const route = formToRequest(createEmptyAlertRouteForm())

  beforeEach(() => request.mockClear())

  it('maps every route endpoint and mutation body', async () => {
    await api.getAlertRoutes()
    await api.getAlertRoute('route/id')
    await api.getAlertRouteRevisions('route/id')
    await api.createAlertRoute(route)
    await api.updateAlertRoute('route/id', {...route, expectedRevision: 2})
    await api.deleteAlertRoute('route/id', 2)
    await api.reorderAlertRoutes({routes: [{id: 'route/id', expectedRevision: 2}]})
    await api.previewAlertRoutes({episodeId: 'episode/id'})

    expect(request.mock.calls).toEqual(expect.arrayContaining([
      ['/v1/on-call/alert-routes'],
      ['/v1/on-call/alert-routes/route%2Fid'],
      ['/v1/on-call/alert-routes/route%2Fid/revisions'],
      ['/v1/on-call/alert-routes', expect.objectContaining({method: 'POST'})],
      ['/v1/on-call/alert-routes/route%2Fid', expect.objectContaining({method: 'PUT'})],
      ['/v1/on-call/alert-routes/route%2Fid?expectedRevision=2', {method: 'DELETE'}],
      ['/v1/on-call/alert-routes/reorder', expect.objectContaining({method: 'POST'})],
      ['/v1/on-call/alert-routes/preview', expect.objectContaining({method: 'POST'})],
    ]))
  })

  it('maps group filters and command endpoints with encoded public IDs', async () => {
    await api.getAlertGroups({limit: 20, offset: 5})
    await api.getAlertGroups()
    await api.getAlertGroup('group/id')
    await api.markAlertGroupEpisodeUnrelated('group/id', 'episode/id', 3)
    await api.removeAlertGroupEpisode('group/id', 'episode/id', 3)
    await api.attachAlertGroup('group/id', {expectedVersion: 3, incidentId: 'incident'})
    await api.createAlertGroupTriage('group/id', {expectedVersion: 3, title: 'Title'})

    expect(request.mock.calls).toEqual(expect.arrayContaining([
      ['/v1/on-call/alert-groups?limit=20&offset=5'],
      ['/v1/on-call/alert-groups'],
      ['/v1/on-call/alert-groups/group%2Fid'],
      ['/v1/on-call/alert-groups/group%2Fid/episodes/episode%2Fid/unrelated', expect.objectContaining({method: 'POST'})],
      ['/v1/on-call/alert-groups/group%2Fid/episodes/episode%2Fid?expectedVersion=3', {method: 'DELETE'}],
      ['/v1/on-call/alert-groups/group%2Fid/attach', expect.objectContaining({method: 'POST'})],
      ['/v1/on-call/alert-groups/group%2Fid/create-triage', expect.objectContaining({method: 'POST'})],
    ]))
  })
})
