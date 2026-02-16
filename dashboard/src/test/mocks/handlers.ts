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

import { http, HttpResponse } from 'msw'

const API_BASE = 'http://localhost:8080'

export const handlers = [
  // Auth endpoints
  http.post(`${API_BASE}/auth/login`, () => {
    return HttpResponse.json({
      token: 'mock-jwt-token',
      user: {
        id: '1',
        email: 'test@example.com',
        name: 'Test User',
      },
    })
  }),

  http.post(`${API_BASE}/auth/signup`, () => {
    return HttpResponse.json({
      token: 'mock-jwt-token',
      user: {
        id: '1',
        email: 'test@example.com',
        name: 'Test User',
      },
    })
  }),

  // Projects endpoints
  http.get(`${API_BASE}/v1/projects`, () => {
    return HttpResponse.json([
      {
        id: '1',
        name: 'Test Project',
        slug: 'test-project',
        platform: 'javascript',
      },
    ])
  }),

  // Issues endpoints
  http.get(`${API_BASE}/v1/projects/:projectId/issues`, () => {
    return HttpResponse.json({
      issues: [],
      totalCount: 0,
    })
  }),
]
