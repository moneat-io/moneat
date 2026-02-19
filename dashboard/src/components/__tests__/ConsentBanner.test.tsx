import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ConsentBanner } from '../ConsentBanner'

describe('ConsentBanner', () => {
  beforeEach(() => {
    localStorage.clear()
    // @ts-ignore
    window.gtag = vi.fn()
  })

  it('shows banner when no consent stored', () => {
    render(<ConsentBanner />)
    expect(screen.getByText(/we use cookies/i)).toBeInTheDocument()
  })

  it('hides banner when consent already stored', () => {
    localStorage.setItem('moneat_cookie_consent', 'granted')
    render(<ConsentBanner />)
    expect(screen.queryByText(/we use cookies/i)).not.toBeInTheDocument()
  })

  it('clicking Accept stores granted and hides banner', () => {
    render(<ConsentBanner />)
    fireEvent.click(screen.getByRole('button', { name: /accept/i }))
    expect(localStorage.getItem('moneat_cookie_consent')).toBe('granted')
    expect(screen.queryByText(/we use cookies/i)).not.toBeInTheDocument()
  })

  it('clicking Decline stores denied and hides banner', () => {
    render(<ConsentBanner />)
    fireEvent.click(screen.getByRole('button', { name: /decline/i }))
    expect(localStorage.getItem('moneat_cookie_consent')).toBe('denied')
    expect(screen.queryByText(/we use cookies/i)).not.toBeInTheDocument()
  })

  it('calls gtag on accept', () => {
    render(<ConsentBanner />)
    fireEvent.click(screen.getByRole('button', { name: /accept/i }))
    expect(window.gtag).toHaveBeenCalledWith('consent', 'update', expect.objectContaining({
      analytics_storage: 'granted',
    }))
  })

  it('calls gtag on decline', () => {
    render(<ConsentBanner />)
    fireEvent.click(screen.getByRole('button', { name: /decline/i }))
    expect(window.gtag).toHaveBeenCalledWith('consent', 'update', expect.objectContaining({
      analytics_storage: 'denied',
    }))
  })

  it('re-applies stored consent on load', () => {
    localStorage.setItem('moneat_cookie_consent', 'granted')
    render(<ConsentBanner />)
    expect(window.gtag).toHaveBeenCalledWith('consent', 'update', expect.objectContaining({
      analytics_storage: 'granted',
    }))
  })

  it('contains privacy policy link', () => {
    render(<ConsentBanner />)
    const link = screen.getByRole('link', { name: /privacy policy/i })
    expect(link).toHaveAttribute('href', '/legal/privacy')
  })
})
