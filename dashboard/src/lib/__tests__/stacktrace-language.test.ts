import {describe, expect, it} from 'vitest'
import {detectStacktraceLanguage} from '../stacktrace-language'

describe('detectStacktraceLanguage', () => {
  it('detects a Java stacktrace with a source location', () => {
    expect(detectStacktraceLanguage('  at com.example.Service.run(Service.java:42)')).toBe('javastacktrace')
  })

  it('detects tab-indented Java stacktraces', () => {
    expect(detectStacktraceLanguage('Exception\n\tat com.example.Service.run(Service.java:42)')).toBe('javastacktrace')
  })

  it('rejects malformed Java-looking lines', () => {
    expect(detectStacktraceLanguage('at com.example.Service.run(Service.java:line)')).toBe('log')
    expect(detectStacktraceLanguage('at com.example.Service.run')).toBe('log')
  })

  it('detects Python locations and falls back to logs', () => {
    expect(detectStacktraceLanguage('  File "worker.py", line 12, in run')).toBe('python')
    expect(detectStacktraceLanguage('plain application log')).toBe('log')
  })
})
