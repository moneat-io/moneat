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

package com.moneat.logs.repositories

import kotlinx.serialization.SerializationException
import java.io.IOException

import com.moneat.config.ClickHouseClient
import com.moneat.config.isClickHouseError
import io.ktor.client.statement.bodyAsText
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class LogRepositoryImpl : LogRepository {

    override suspend fun executeClickHouseInsert(sql: String): Boolean =
        try {
            val response = ClickHouseClient.execute(sql)
            val body = response.bodyAsText()
            !response.isClickHouseError(body)
        } catch (e: SerializationException) {
            logger.error(e) { "ClickHouse log insert failed" }
            false
        } catch (e: IOException) {
            logger.error(e) { "ClickHouse log insert failed" }
            false
        } catch (e: IllegalStateException) {
            logger.error(e) { "ClickHouse log insert failed" }
            false
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "ClickHouse log insert failed" }
            false
        }

    override suspend fun executeClickHouseQuery(sql: String): String =
        try {
            val response = ClickHouseClient.execute(sql)
            response.bodyAsText()
        } catch (e: SerializationException) {
            logger.error(e) { "ClickHouse log query failed" }
            ""
        } catch (e: IOException) {
            logger.error(e) { "ClickHouse log query failed" }
            ""
        } catch (e: IllegalStateException) {
            logger.error(e) { "ClickHouse log query failed" }
            ""
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "ClickHouse log query failed" }
            ""
        }
}
