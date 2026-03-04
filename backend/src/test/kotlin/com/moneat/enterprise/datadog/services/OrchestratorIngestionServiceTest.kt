// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.services

import com.moneat.enterprise.datadog.models.DdK8sManifest
import com.moneat.enterprise.datadog.models.DdK8sResource
import com.moneat.enterprise.datadog.models.DdManifestPayload
import com.moneat.enterprise.datadog.models.DdOrchestratorPayload
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrchestratorIngestionServiceTest {

    // ============ MAP RESOURCES TESTS ============

    @Test
    fun `mapResources maps payload fields correctly`() {
        val payload = DdOrchestratorPayload(
            resources = listOf(
                DdK8sResource(
                    uid = "pod-123",
                    type = "pod",
                    namespace = "default",
                    name = "my-app-abc12",
                    status = "Running",
                    tags = mapOf("app" to "my-app"),
                    labels = mapOf("version" to "v1"),
                    annotations = mapOf("note" to "test"),
                    resourceVersion = "12345",
                    creationTimestamp = 1700000000000L,
                )
            ),
            clusterName = "prod-cluster",
            clusterId = "cluster-001",
            host = "node-1",
            tags = listOf("env:production"),
        )

        val batch = OrchestratorIngestionService.mapResources(1, payload)

        assertEquals(1, batch.organizationId)
        assertEquals("prod-cluster", batch.clusterName)
        assertEquals("cluster-001", batch.clusterId)
        assertEquals("resources", batch.batchType)
        assertEquals(1, batch.resources.size)

        val r = batch.resources[0]
        assertEquals("pod-123", r.uid)
        assertEquals("pod", r.resourceType)
        assertEquals("default", r.namespace)
        assertEquals("my-app-abc12", r.name)
        assertEquals("Running", r.status)
        assertEquals("my-app", r.tags["app"])
        assertEquals("v1", r.labels["version"])
        assertEquals("test", r.annotations["note"])
        assertEquals("12345", r.resourceVersion)
        assertEquals(1700000000000L, r.creationTimestampMs)
    }

    @Test
    fun `mapResources handles empty resources list`() {
        val payload = DdOrchestratorPayload(
            resources = emptyList(),
            clusterName = "test",
            clusterId = "c1",
        )

        val batch = OrchestratorIngestionService.mapResources(1, payload)
        assertTrue(batch.resources.isEmpty())
    }

    @Test
    fun `mapResources handles multiple resources`() {
        val payload = DdOrchestratorPayload(
            resources = listOf(
                DdK8sResource(uid = "pod-1", type = "pod", name = "app-1"),
                DdK8sResource(uid = "svc-1", type = "service", name = "svc-1"),
                DdK8sResource(uid = "deploy-1", type = "deployment", name = "deploy-1"),
            ),
            clusterName = "staging",
            clusterId = "c2",
        )

        val batch = OrchestratorIngestionService.mapResources(2, payload)
        assertEquals(3, batch.resources.size)
        assertEquals("pod", batch.resources[0].resourceType)
        assertEquals("service", batch.resources[1].resourceType)
        assertEquals("deployment", batch.resources[2].resourceType)
    }

    @Test
    fun `mapResources handles null creation timestamp`() {
        val payload = DdOrchestratorPayload(
            resources = listOf(
                DdK8sResource(
                    uid = "rs-1", type = "replicaset", name = "rs-1",
                    creationTimestamp = null,
                )
            ),
            clusterName = "test",
            clusterId = "c1",
        )

        val batch = OrchestratorIngestionService.mapResources(1, payload)
        assertEquals(null, batch.resources[0].creationTimestampMs)
    }

    // ============ MAP MANIFESTS TESTS ============

    @Test
    fun `mapManifests maps payload fields correctly`() {
        val payload = DdManifestPayload(
            manifests = listOf(
                DdK8sManifest(
                    uid = "deploy-abc",
                    type = "deployment",
                    namespace = "default",
                    name = "my-deployment",
                    content = """{"apiVersion":"apps/v1","kind":"Deployment"}""",
                    contentType = "application/json",
                )
            ),
            clusterName = "prod-cluster",
            host = "node-1",
        )

        val batch = OrchestratorIngestionService.mapManifests(1, payload)

        assertEquals(1, batch.organizationId)
        assertEquals("prod-cluster", batch.clusterName)
        assertEquals("manifests", batch.batchType)
        assertEquals(1, batch.manifests.size)

        val m = batch.manifests[0]
        assertEquals("deploy-abc", m.uid)
        assertEquals("deployment", m.resourceType)
        assertEquals("default", m.namespace)
        assertEquals("my-deployment", m.name)
        assertTrue(m.content.contains("Deployment"))
        assertEquals("application/json", m.contentType)
    }

    @Test
    fun `mapManifests handles empty manifests list`() {
        val payload = DdManifestPayload(
            manifests = emptyList(),
            clusterName = "test",
        )

        val batch = OrchestratorIngestionService.mapManifests(1, payload)
        assertTrue(batch.manifests.isEmpty())
    }

    // ============ ENCODE/DECODE ROUND-TRIP TESTS ============

    @Test
    fun `decodeBatch round-trips resources batch`() {
        val payload = DdOrchestratorPayload(
            resources = listOf(
                DdK8sResource(uid = "pod-1", type = "pod", name = "app-1"),
            ),
            clusterName = "test",
            clusterId = "c1",
        )

        val batch = OrchestratorIngestionService.mapResources(1, payload)
        val json = kotlinx.serialization.json.Json.encodeToString(batch)
        val decoded = OrchestratorIngestionService.decodeBatch(json)

        assertEquals(batch.organizationId, decoded.organizationId)
        assertEquals(batch.batchType, decoded.batchType)
        assertEquals(batch.clusterName, decoded.clusterName)
        assertEquals(batch.resources.size, decoded.resources.size)
        assertEquals(batch.resources[0].uid, decoded.resources[0].uid)
    }

    @Test
    fun `decodeBatch round-trips manifests batch`() {
        val payload = DdManifestPayload(
            manifests = listOf(
                DdK8sManifest(uid = "d-1", type = "deployment", name = "d-1", content = "{}"),
            ),
            clusterName = "test",
        )

        val batch = OrchestratorIngestionService.mapManifests(1, payload)
        val json = kotlinx.serialization.json.Json.encodeToString(batch)
        val decoded = OrchestratorIngestionService.decodeBatch(json)

        assertEquals("manifests", decoded.batchType)
        assertEquals(1, decoded.manifests.size)
        assertEquals("d-1", decoded.manifests[0].uid)
    }

    // ============ SQL ESCAPING TESTS ============

    @Test
    fun `escapeSql escapes single quotes and backslashes`() {
        assertEquals(
            "it\\'s a \\\\test",
            OrchestratorIngestionService.escapeSql("it's a \\test")
        )
    }

    @Test
    fun `mapToSqlMap produces correct format`() {
        val result = OrchestratorIngestionService.mapToSqlMap(
            mapOf("env" to "prod", "app" to "web")
        )
        assertEquals("map('env', 'prod', 'app', 'web')", result)
    }

    @Test
    fun `mapToSqlMap handles empty map`() {
        assertEquals("map()", OrchestratorIngestionService.mapToSqlMap(emptyMap()))
    }

    @Test
    fun `mapToSqlMap escapes special characters in keys and values`() {
        val result = OrchestratorIngestionService.mapToSqlMap(
            mapOf("it's" to "O'Brien")
        )
        assertEquals("map('it\\'s', 'O\\'Brien')", result)
    }
}
