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
