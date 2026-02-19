import { describe, it, expect } from 'vitest'
import { applySdkVersionsToSnippet } from '../sdk-versions'

describe('applySdkVersionsToSnippet', () => {
  it('replaces Android SDK version with default', () => {
    const code = 'implementation("io.sentry:sentry-android:6.0.0")'
    const result = applySdkVersionsToSnippet(code)
    expect(result).toBe('implementation("io.sentry:sentry-android:7.0.0")')
  })

  it('replaces Android SDK version with custom', () => {
    const code = 'implementation("io.sentry:sentry-android:6.0.0")'
    const result = applySdkVersionsToSnippet(code, { 'io.sentry:sentry-android': '8.0.0' })
    expect(result).toBe('implementation("io.sentry:sentry-android:8.0.0")')
  })

  it('replaces KMP SDK version', () => {
    const code = 'implementation("io.sentry:sentry-kotlin-multiplatform:3.0.0")'
    const result = applySdkVersionsToSnippet(code)
    expect(result).toBe('implementation("io.sentry:sentry-kotlin-multiplatform:4.0.0")')
  })

  it('replaces JVM Sentry version', () => {
    const code = 'implementation("io.sentry:sentry:6.0.0")'
    const result = applySdkVersionsToSnippet(code)
    expect(result).toBe('implementation("io.sentry:sentry:7.0.0")')
  })

  it('replaces CocoaPods version', () => {
    const code = "pod 'Sentry', '~> 7.0'"
    const result = applySdkVersionsToSnippet(code)
    expect(result).toBe("pod 'Sentry', '~> 8.0'")
  })

  it('replaces Flutter version', () => {
    const code = 'sentry_flutter: ^7.0.0'
    const result = applySdkVersionsToSnippet(code)
    expect(result).toBe('sentry_flutter: ^8.0.0')
  })

  it('replaces Elixir version', () => {
    const code = '{:sentry, "~> 9.0"}'
    const result = applySdkVersionsToSnippet(code)
    expect(result).toBe('{:sentry, "~> 10.0"}')
  })

  it('replaces OpenTelemetry SDK logs version', () => {
    const code = 'implementation("io.opentelemetry:opentelemetry-sdk-logs:1.30.0")'
    const result = applySdkVersionsToSnippet(code)
    expect(result).toBe('implementation("io.opentelemetry:opentelemetry-sdk-logs:1.34.0")')
  })

  it('replaces OpenTelemetry OTLP exporter version', () => {
    const code = 'implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.30.0")'
    const result = applySdkVersionsToSnippet(code)
    expect(result).toBe('implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.34.0")')
  })

  it('replaces multiple versions in same snippet', () => {
    const code = `implementation("io.sentry:sentry-android:6.0.0")
implementation("io.sentry:sentry:6.0.0")`
    const result = applySdkVersionsToSnippet(code)
    expect(result).toContain('io.sentry:sentry-android:7.0.0')
    expect(result).toContain('io.sentry:sentry:7.0.0')
  })

  it('returns unchanged text when no versions present', () => {
    const code = 'const x = 42'
    const result = applySdkVersionsToSnippet(code)
    expect(result).toBe('const x = 42')
  })

  it('uses custom versions map when provided', () => {
    const code = 'implementation("io.sentry:sentry-android:5.0.0")'
    const custom = { 'io.sentry:sentry-android': '9.1.0' }
    const result = applySdkVersionsToSnippet(code, custom)
    expect(result).toBe('implementation("io.sentry:sentry-android:9.1.0")')
  })
})
