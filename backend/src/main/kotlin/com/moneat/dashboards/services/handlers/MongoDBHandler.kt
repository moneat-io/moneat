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

package com.moneat.dashboards.services.handlers

import com.moneat.dashboards.models.DataSourceField
import com.moneat.dashboards.models.TestConnectionRequest
import com.moneat.dashboards.models.TestConnectionResult
import com.moneat.dashboards.models.TimeRangeDef
import com.moneat.dashboards.services.DataSourceCredentials
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.bson.BsonDocument
import org.bson.BsonValue
import org.bson.Document
import org.bson.conversions.Bson

private val logger = KotlinLogging.logger {}

/**
 * MongoDB handler using mongodb-driver-sync.
 * Uses connection_string from credentials, or host:port/database.
 */
class MongoDBHandler : DataSourceHandler {

    override suspend fun testConnection(request: TestConnectionRequest): TestConnectionResult {
        val connStr = request.connectionString
            ?: buildConnectionString(request.host, request.port ?: 27017, request.databaseName, request.username, request.password)

        return try {
            MongoClients.create(connStr).use { client ->
                val databases = client.listDatabaseNames().toList()
                TestConnectionResult(true, "Connected successfully", databases = databases.take(20))
            }
        } catch (e: Exception) {
            logger.warn(e) { "MongoDB connection test failed" }
            TestConnectionResult(false, "Connection failed: ${e.message}")
        }
    }

    override suspend fun executeQuery(
        sourceId: Long,
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
        query: String,
        limit: Int,
        timeRange: TimeRangeDef?,
    ): List<Map<String, JsonElement>> {
        val connStr = credentials.connectionString
            ?: buildConnectionString(host, port ?: 27017, databaseName, credentials.username, credentials.password)
        val dbName = databaseName ?: "test"

        return try {
            MongoClients.create(connStr).use { client ->
                val db = client.getDatabase(dbName)
                val parsed = parseQuery(query)
                val (collectionName, filter, pipeline) = parsed
                val collection = db.getCollection(collectionName)

                val docs = if (pipeline != null) {
                    val limitStage = org.bson.BsonDocument("\$limit", org.bson.BsonInt32(limit))
                    collection.aggregate(pipeline + limitStage).toList()
                } else {
                    collection.find(filter).limit(limit).toList()
                }
                docs.map { docToMap(it) }
            }
        } catch (e: Exception) {
            logger.error(e) { "MongoDB query failed" }
            emptyList()
        }
    }

    override suspend fun getSchema(
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
    ): List<DataSourceField> {
        val connStr = credentials.connectionString
            ?: buildConnectionString(host, port ?: 27017, databaseName, credentials.username, credentials.password)
        val dbName = databaseName ?: "test"

        return try {
            MongoClients.create(connStr).use { client ->
                val db = client.getDatabase(dbName)
                val collections = db.listCollectionNames().toList()
                collections.map { DataSourceField(it, "collection", "MongoDB collection") }
            }
        } catch (e: Exception) {
            logger.error(e) { "MongoDB schema fetch failed" }
            emptyList()
        }
    }

    private fun buildConnectionString(
        host: String,
        port: Int,
        database: String?,
        username: String?,
        password: String?,
    ): String {
        val auth = if (username != null && password != null) "$username:$password@" else ""
        val db = database?.ifBlank { null } ?: "test"
        return "mongodb://$auth$host:$port/$db"
    }

    private fun parseQuery(query: String): Triple<String, Bson, List<Bson>?> {
        return try {
            val doc = Document.parse(query)
            val collection = doc.getString("collection") ?: doc.getString("coll") ?: "test"
            val filter = when (val f = doc.get("filter")) {
                is Document -> org.bson.BsonDocument.parse(f.toJson())
                is BsonDocument -> f
                else -> BsonDocument()
            }
            val pipeline = doc.getList("pipeline", Document::class.java)?.map {
                org.bson.BsonDocument.parse(it.toJson())
            }
            Triple(collection, filter, pipeline)
        } catch (_: Exception) {
            Triple("test", BsonDocument(), null)
        }
    }

    private fun docToMap(doc: Document): Map<String, JsonElement> {
        val map = mutableMapOf<String, JsonElement>()
        for ((k, v) in doc) {
            map[k] = bsonToJson(v)
        }
        return map
    }

    private fun bsonToJson(value: Any?): JsonElement {
        when (value) {
            null -> return JsonNull
            is Number -> return JsonPrimitive(value)
            is Boolean -> return JsonPrimitive(value)
            is String -> return JsonPrimitive(value)
            is Document -> return JsonObject(value.mapValues { bsonToJson(it.value) })
            is org.bson.types.ObjectId -> return JsonPrimitive(value.toString())
            is java.util.Date -> return JsonPrimitive(value.time)
            else -> return JsonPrimitive(value.toString())
        }
    }
}
