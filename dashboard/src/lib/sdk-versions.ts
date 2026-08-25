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

export type SdkVersionMap = Record<string, string>

const DEFAULT_DOC_SDK_VERSIONS: SdkVersionMap = {
  'io.sentry:sentry-android': '7.0.0',
  'io.sentry:sentry-kotlin-multiplatform': '4.0.0',
  'io.sentry:sentry': '7.0.0',
  'pod:Sentry': '8.0',
  'pub:sentry_flutter': '8.0.0',
  'hex:sentry': '10.0',
  'io.opentelemetry:opentelemetry-sdk-logs': '1.34.0',
  'io.opentelemetry:opentelemetry-exporter-otlp': '1.34.0',
}

function resolveVersion(
  key: keyof typeof DEFAULT_DOC_SDK_VERSIONS,
  sdkVersions?: SdkVersionMap
): string {
  return sdkVersions?.[key] ?? DEFAULT_DOC_SDK_VERSIONS[key]
}

function replaceVersionSegment(code: string, prefix: string, version: string): string {
  let result = ''
  let cursor = 0

  while (cursor < code.length) {
    const matchStart = code.indexOf(prefix, cursor)
    if (matchStart < 0) break

    const versionStart = matchStart + prefix.length
    const versionEnd = code.indexOf('"', versionStart)
    if (versionEnd <= versionStart) {
      return result + code.slice(cursor)
    }

    result += code.slice(cursor, versionStart) + version
    cursor = versionEnd
  }

  return result + code.slice(cursor)
}

export function applySdkVersionsToSnippet(code: string, sdkVersions?: SdkVersionMap): string {
  let next = code

  const sentryAndroidVersion = resolveVersion('io.sentry:sentry-android', sdkVersions)
  next = next.replace(/io\.sentry:sentry-android:[0-9A-Za-z.+-]+/g, `io.sentry:sentry-android:${sentryAndroidVersion}`)

  const sentryKmpVersion = resolveVersion('io.sentry:sentry-kotlin-multiplatform', sdkVersions)
  next = next.replace(
    /io\.sentry:sentry-kotlin-multiplatform:[0-9A-Za-z.+-]+/g,
    `io.sentry:sentry-kotlin-multiplatform:${sentryKmpVersion}`
  )

  const sentryJvmVersion = resolveVersion('io.sentry:sentry', sdkVersions)
  next = next.replace(/io\.sentry:sentry:[0-9A-Za-z.+-]+/g, `io.sentry:sentry:${sentryJvmVersion}`)

  const sentryCocoaVersion = resolveVersion('pod:Sentry', sdkVersions)
  next = next.replace(/pod 'Sentry', '~> [^']+'/g, `pod 'Sentry', '~> ${sentryCocoaVersion}'`)

  const sentryFlutterVersion = resolveVersion('pub:sentry_flutter', sdkVersions)
  next = next.replace(/sentry_flutter:\s*\^[0-9A-Za-z.+-]+/g, `sentry_flutter: ^${sentryFlutterVersion}`)

  const sentryElixirVersion = resolveVersion('hex:sentry', sdkVersions)
  next = replaceVersionSegment(next, '{:sentry, "~> ', sentryElixirVersion)

  const otelSdkLogsVersion = resolveVersion('io.opentelemetry:opentelemetry-sdk-logs', sdkVersions)
  next = next.replace(
    /io\.opentelemetry:opentelemetry-sdk-logs:[0-9A-Za-z.+-]+/g,
    `io.opentelemetry:opentelemetry-sdk-logs:${otelSdkLogsVersion}`
  )

  const otelOtlpExporterVersion = resolveVersion('io.opentelemetry:opentelemetry-exporter-otlp', sdkVersions)
  next = next.replace(
    /io\.opentelemetry:opentelemetry-exporter-otlp:[0-9A-Za-z.+-]+/g,
    `io.opentelemetry:opentelemetry-exporter-otlp:${otelOtlpExporterVersion}`
  )

  return next
}
