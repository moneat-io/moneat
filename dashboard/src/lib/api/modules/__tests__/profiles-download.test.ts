import { describe, it, expect, beforeEach, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '@/lib/api'

const API_BASE = 'http://localhost:8080'

describe('Profiles API - downloadProfile coverage', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  it('downloads a profile and triggers file download', async () => {
    const blobContent = new Blob(['profile data'], { type: 'application/octet-stream' })
    const mockClick = vi.fn()
    const mockRemove = vi.fn()

    vi.spyOn(document, 'createElement').mockReturnValue({
      href: '',
      download: '',
      click: mockClick,
      remove: mockRemove,
    } as unknown as HTMLAnchorElement)

    vi.spyOn(document.body, 'appendChild').mockImplementation((node) => node)
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock-url')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})

    server.use(
      http.get(`${API_BASE}/v1/profiles/prof-dl/download`, () => {
        return new HttpResponse(blobContent, {
          headers: {
            'content-disposition': 'attachment; filename="my-profile.jfr"',
          },
        })
      })
    )

    await api.downloadProfile('prof-dl')

    expect(document.createElement).toHaveBeenCalledWith('a')
    expect(mockClick).toHaveBeenCalled()
    expect(mockRemove).toHaveBeenCalled()

    vi.restoreAllMocks()
  })

  it('uses JFR extension when profileType contains jfr', async () => {
    const blobContent = new Blob(['data'])
    const mockClick = vi.fn()
    const mockRemove = vi.fn()

    vi.spyOn(document, 'createElement').mockReturnValue({
      href: '',
      download: '',
      click: mockClick,
      remove: mockRemove,
    } as unknown as HTMLAnchorElement)

    vi.spyOn(document.body, 'appendChild').mockImplementation((node) => node)
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})

    server.use(
      http.get(`${API_BASE}/v1/profiles/prof-jfr/download`, () => {
        return new HttpResponse(blobContent)
      })
    )

    await api.downloadProfile('prof-jfr', undefined, 'JFR')

    const link = document.createElement('a') as unknown as { download: string }
    expect(link.download).toContain('jfr')

    vi.restoreAllMocks()
  })

  it('throws on non-ok response', async () => {
    server.use(
      http.get(`${API_BASE}/v1/profiles/prof-fail/download`, () => {
        return new HttpResponse(null, { status: 500 })
      })
    )

    await expect(api.downloadProfile('prof-fail')).rejects.toThrow('Profile download failed')
  })
})
