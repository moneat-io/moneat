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

package com.moneat.workflows.engine.temporal

import com.google.protobuf.ByteString
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.moneat.config.EnvConfig
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.temporal.api.enums.v1.IndexedValueType
import io.temporal.api.operatorservice.v1.AddSearchAttributesRequest
import io.temporal.api.operatorservice.v1.ListSearchAttributesRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.converter.CodecDataConverter
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.payload.codec.PayloadCodec
import io.temporal.serviceclient.OperatorServiceStubs
import io.temporal.serviceclient.OperatorServiceStubsOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val DEFAULT_TEMPORAL_TARGET = "temporal:7233"
private const val DEFAULT_TEMPORAL_NAMESPACE = "default"
private const val TEMPORAL_PAYLOAD_KEY = "WORKFLOWS_TEMPORAL_PAYLOAD_KEY"
private const val ENCODING_METADATA_KEY = "encoding"
private const val ENCRYPTED_ENCODING = "binary/moneat-workflow-encrypted"
private const val KEY_ID_METADATA_KEY = "encryption-key-id"
private const val DEFAULT_KEY_ID = "default"
private const val AES_ALGORITHM = "AES"
private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128
private const val HASH_BYTES = 32
private val WORKFLOW_SEARCH_ATTRIBUTES =
    mapOf(
        ORGANIZATION_SEARCH_ATTRIBUTE to IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD,
        WORKFLOW_SEARCH_ATTRIBUTE to IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD
    )

class TemporalClientProvider(
    private val target: String = EnvConfig.get("TEMPORAL_TARGET", DEFAULT_TEMPORAL_TARGET),
    private val namespace: String = EnvConfig.get("TEMPORAL_NAMESPACE", DEFAULT_TEMPORAL_NAMESPACE),
    private val payloadKey: String? = EnvConfig.get(TEMPORAL_PAYLOAD_KEY)
) : AutoCloseable {
    val dataConverter: DataConverter by lazy { buildDataConverter(payloadKey) }
    private var searchAttributesReady = false

    private val serviceDelegate = lazy {
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions
                .newBuilder()
                .setTarget(target)
                .build()
        )
    }
    val service: WorkflowServiceStubs
        get() = serviceDelegate.value

    private val clientDelegate = lazy {
        WorkflowClient.newInstance(
            service,
            WorkflowClientOptions
                .newBuilder()
                .setNamespace(namespace)
                .setDataConverter(dataConverter)
                .build()
        )
    }
    val client: WorkflowClient
        get() = clientDelegate.value

    private val operatorDelegate = lazy {
        OperatorServiceStubs.newServiceStubs(
            OperatorServiceStubsOptions
                .newBuilder()
                .setTarget(target)
                .build()
        )
    }
    private val operator: OperatorServiceStubs
        get() = operatorDelegate.value

    fun newWorkerFactory(): WorkerFactory {
        ensureSearchAttributes()
        return WorkerFactory.newInstance(client)
    }

    @Synchronized
    fun ensureSearchAttributes() {
        if (searchAttributesReady) return
        val response =
            operator
                .blockingStub()
                .listSearchAttributes(ListSearchAttributesRequest.newBuilder().setNamespace(namespace).build())
        val existingAttributes = response.systemAttributesMap + response.customAttributesMap
        val mismatchedAttributes =
            WORKFLOW_SEARCH_ATTRIBUTES.filter { (name, expectedType) ->
                val existingType = existingAttributes[name]
                existingType != null && existingType != expectedType
            }
        require(mismatchedAttributes.isEmpty()) {
            "Temporal search attributes have incompatible types: ${mismatchedAttributes.keys.joinToString()}"
        }
        val missingAttributes =
            WORKFLOW_SEARCH_ATTRIBUTES.filterKeys { name -> name !in existingAttributes }
        if (missingAttributes.isNotEmpty()) {
            addSearchAttributes(missingAttributes)
        }
        searchAttributesReady = true
    }

    override fun close() {
        if (operatorDelegate.isInitialized()) {
            operator.shutdown()
        }
        if (serviceDelegate.isInitialized()) {
            service.shutdown()
        }
    }

    private fun buildDataConverter(key: String?): DataConverter {
        val objectMapper = JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule()
        val defaultConverter =
            DefaultDataConverter
                .newDefaultInstance()
                .withPayloadConverterOverrides(JacksonJsonPayloadConverter(objectMapper))
        return if (key.isNullOrBlank()) {
            defaultConverter
        } else {
            CodecDataConverter(defaultConverter, listOf(WorkflowPayloadEncryptionCodec(key)), true)
        }
    }

    private fun addSearchAttributes(attributes: Map<String, IndexedValueType>) {
        try {
            operator
                .blockingStub()
                .addSearchAttributes(
                    AddSearchAttributesRequest
                        .newBuilder()
                        .setNamespace(namespace)
                        .putAllSearchAttributes(attributes)
                        .build()
                )
        } catch (e: StatusRuntimeException) {
            if (e.status.code != Status.Code.ALREADY_EXISTS) throw e
        }
    }
}

private class WorkflowPayloadEncryptionCodec(keyMaterial: String) : PayloadCodec {
    private val secureRandom = SecureRandom()
    private val secretKey =
        SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(keyMaterial.toByteArray()).copyOf(HASH_BYTES),
            AES_ALGORITHM
        )

    override fun encode(payloads: List<io.temporal.api.common.v1.Payload>): List<io.temporal.api.common.v1.Payload> =
        payloads.map { payload -> encodePayload(payload) }

    override fun decode(payloads: List<io.temporal.api.common.v1.Payload>): List<io.temporal.api.common.v1.Payload> =
        payloads.map { payload ->
            if (payload.metadataMap[ENCODING_METADATA_KEY]?.toStringUtf8() == ENCRYPTED_ENCODING) {
                decodePayload(payload)
            } else {
                payload
            }
        }

    private fun encodePayload(payload: io.temporal.api.common.v1.Payload): io.temporal.api.common.v1.Payload {
        val nonce = ByteArray(GCM_IV_BYTES)
        secureRandom.nextBytes(nonce)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val encrypted = cipher.doFinal(payload.toByteArray())
        return io.temporal.api.common.v1.Payload
            .newBuilder()
            .putMetadata(ENCODING_METADATA_KEY, ByteString.copyFromUtf8(ENCRYPTED_ENCODING))
            .putMetadata(KEY_ID_METADATA_KEY, ByteString.copyFromUtf8(DEFAULT_KEY_ID))
            .setData(ByteString.copyFrom(nonce + encrypted))
            .build()
    }

    private fun decodePayload(payload: io.temporal.api.common.v1.Payload): io.temporal.api.common.v1.Payload {
        val data = payload.data.toByteArray()
        val nonce = data.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = data.copyOfRange(GCM_IV_BYTES, data.size)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return io.temporal.api.common.v1.Payload.parseFrom(cipher.doFinal(encrypted))
    }
}
