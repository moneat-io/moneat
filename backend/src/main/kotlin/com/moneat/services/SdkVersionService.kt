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

package com.moneat.services

import com.moneat.config.EnvConfig
import com.moneat.models.SdkVersionsResponse
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val sdkVersionLogger = KotlinLogging.logger {}
private val sdkVersionJson = Json { ignoreUnknownKeys = true }
private val stableSemverRegex = Regex("""\d+\.\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.-]+)?""")

internal fun normalizeVersionTag(tag: String): String? {
    return stableSemverRegex.find(tag)?.value
}

private data class VersionTarget(
    val packageKey: String,
    val repository: String,
    val fallbackVersion: String? = null,
)

@Serializable
private data class GitHubLatestReleaseResponse(
    val tag_name: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
)

@Serializable
private data class GitHubTagResponse(
    val name: String,
)

object SdkVersionService {
    private const val CACHE_KEY = "cache:sdk_versions:github:v1"
    private const val DEFAULT_CACHE_TTL_SECONDS = 21_600 // 6 hours
    private const val MIN_CACHE_TTL_SECONDS = 300
    private const val MAX_CACHE_TTL_SECONDS = 86_400
    private const val GITHUB_API_BASE = "https://api.github.com"

    private val versionTargets = listOf(
        VersionTarget("@sentry/browser", "getsentry/sentry-javascript"),
        VersionTarget("@sentry/react", "getsentry/sentry-javascript"),
        VersionTarget("@sentry/vue", "getsentry/sentry-javascript"),
        VersionTarget("@sentry/node", "getsentry/sentry-javascript"),
        VersionTarget("@sentry/angular", "getsentry/sentry-javascript"),
        VersionTarget("@sentry/svelte", "getsentry/sentry-javascript"),
        VersionTarget("@sentry/nextjs", "getsentry/sentry-javascript"),
        VersionTarget("@sentry/nuxt", "getsentry/sentry-javascript"),
        VersionTarget("@sentry/remix", "getsentry/sentry-javascript"),
        VersionTarget("@sentry/astro", "getsentry/sentry-javascript"),
        VersionTarget("@sentry/solidstart", "getsentry/sentry-javascript"),
        VersionTarget("@sentry/electron", "getsentry/sentry-electron"),
        VersionTarget("@sentry/react-native", "getsentry/sentry-react-native"),
        VersionTarget("io.sentry:sentry-android", "getsentry/sentry-java", fallbackVersion = "7.0.0"),
        VersionTarget("io.sentry:sentry", "getsentry/sentry-java", fallbackVersion = "7.0.0"),
        VersionTarget("io.sentry:sentry-spring-boot-starter", "getsentry/sentry-java"),
        VersionTarget("io.sentry:sentry-kotlin-multiplatform", "getsentry/sentry-kotlin-multiplatform", fallbackVersion = "4.0.0"),
        VersionTarget("pod:Sentry", "getsentry/sentry-cocoa", fallbackVersion = "8.0"),
        VersionTarget("pub:sentry_flutter", "getsentry/sentry-dart", fallbackVersion = "8.0.0"),
        VersionTarget("sentry-sdk", "getsentry/sentry-python"),
        VersionTarget("sentry-ruby", "getsentry/sentry-ruby"),
        VersionTarget("sentry-rails", "getsentry/sentry-ruby"),
        VersionTarget("sentry/sentry", "getsentry/sentry-php"),
        VersionTarget("sentry/sentry-laravel", "getsentry/sentry-laravel"),
        VersionTarget("github.com/getsentry/sentry-go", "getsentry/sentry-go"),
        VersionTarget("nuget:Sentry", "getsentry/sentry-dotnet"),
        VersionTarget("cargo:sentry", "getsentry/sentry-rust"),
        VersionTarget("hex:sentry", "getsentry/sentry-elixir", fallbackVersion = "10.0"),
        VersionTarget("sentry-unity", "getsentry/sentry-unity"),
        VersionTarget("sentry-unreal", "getsentry/sentry-unreal"),
        VersionTarget("sentry-godot", "getsentry/sentry-godot"),
        VersionTarget("sentry-native", "getsentry/sentry-native"),
        VersionTarget("io.opentelemetry:opentelemetry-sdk-logs", "open-telemetry/opentelemetry-java", fallbackVersion = "1.34.0"),
        VersionTarget("io.opentelemetry:opentelemetry-exporter-otlp", "open-telemetry/opentelemetry-java", fallbackVersion = "1.34.0"),
    )

    private val repositories = versionTargets.map { it.repository }.distinct()

    private val httpClient by lazy {
        HttpClient(CIO) {
            engine {
                endpoint {
                    connectTimeout = 10_000
                    socketTimeout = 15_000
                }
            }
        }
    }

    suspend fun getSdkVersions(): SdkVersionsResponse {
        val ttlSeconds = cacheTtlSeconds()
        return CacheService.cached(CACHE_KEY, ttlSeconds.toLong()) {
            fetchSdkVersions(ttlSeconds)
        }
    }

    private suspend fun fetchSdkVersions(cacheTtlSeconds: Int): SdkVersionsResponse {
        val repositoryVersions = fetchRepositoryVersions()

        val versions = buildMap {
            for (target in versionTargets) {
                val version = repositoryVersions[target.repository] ?: target.fallbackVersion
                if (!version.isNullOrBlank()) {
                    put(target.packageKey, version)
                }
            }
        }.toSortedMap()

        return SdkVersionsResponse(
            fetchedAt = Clock.System.now().toString(),
            cacheTtlSeconds = cacheTtlSeconds,
            versions = versions,
        )
    }

    private suspend fun fetchRepositoryVersions(): Map<String, String> = coroutineScope {
        val deferred = repositories.associateWith { repository ->
            async {
                fetchLatestRepositoryVersion(repository)
            }
        }

        deferred.entries.mapNotNull { (repository, value) ->
            value.await()?.let { repository to it }
        }.toMap()
    }

    private suspend fun fetchLatestRepositoryVersion(repository: String): String? {
        fetchFromLatestRelease(repository)?.let { return it }
        return fetchFromTags(repository)
    }

    private suspend fun fetchFromLatestRelease(repository: String): String? {
        val response = try {
            httpClient.get("$GITHUB_API_BASE/repos/$repository/releases/latest") {
                applyGitHubHeaders()
            }
        } catch (e: Exception) {
            sdkVersionLogger.warn(e) { "Failed to fetch latest release for $repository" }
            return null
        }

        if (response.status == HttpStatusCode.NotFound) {
            return null
        }

        if (response.status != HttpStatusCode.OK) {
            logUnexpectedStatus("latest release", repository, response)
            return null
        }

        val payload = response.bodyAsText()
        val release = try {
            sdkVersionJson.decodeFromString(GitHubLatestReleaseResponse.serializer(), payload)
        } catch (e: Exception) {
            sdkVersionLogger.warn(e) { "Failed to decode latest release payload for $repository" }
            return null
        }

        if (release.draft || release.prerelease) {
            return null
        }

        return normalizeVersionTag(release.tag_name.orEmpty())
    }

    private suspend fun fetchFromTags(repository: String): String? {
        val response = try {
            httpClient.get("$GITHUB_API_BASE/repos/$repository/tags") {
                applyGitHubHeaders()
                parameter("per_page", 20)
            }
        } catch (e: Exception) {
            sdkVersionLogger.warn(e) { "Failed to fetch tags for $repository" }
            return null
        }

        if (response.status != HttpStatusCode.OK) {
            logUnexpectedStatus("tags", repository, response)
            return null
        }

        val payload = response.bodyAsText()
        val tags = try {
            sdkVersionJson.decodeFromString(ListSerializer(GitHubTagResponse.serializer()), payload)
        } catch (e: Exception) {
            sdkVersionLogger.warn(e) { "Failed to decode tags payload for $repository" }
            return null
        }

        var prereleaseCandidate: String? = null
        for (tag in tags) {
            val normalized = normalizeVersionTag(tag.name) ?: continue
            if (!normalized.contains('-')) {
                return normalized
            }
            if (prereleaseCandidate == null) {
                prereleaseCandidate = normalized
            }
        }

        return prereleaseCandidate
    }

    private fun HttpRequestBuilder.applyGitHubHeaders() {
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header(HttpHeaders.UserAgent, "moneat-sdk-version-service")
    }

    private fun cacheTtlSeconds(): Int {
        return EnvConfig.get("GITHUB_VERSIONS_CACHE_TTL_SECONDS")
            ?.toIntOrNull()
            ?.coerceIn(MIN_CACHE_TTL_SECONDS, MAX_CACHE_TTL_SECONDS)
            ?: DEFAULT_CACHE_TTL_SECONDS
    }

    private fun logUnexpectedStatus(endpoint: String, repository: String, response: HttpResponse) {
        val remaining = response.headers["X-RateLimit-Remaining"]
        val reset = response.headers["X-RateLimit-Reset"]

        sdkVersionLogger.warn {
            "GitHub $endpoint request failed for $repository: ${response.status.value} " +
                "(remaining=${remaining ?: "unknown"}, reset=${reset ?: "unknown"})"
        }
    }
}
