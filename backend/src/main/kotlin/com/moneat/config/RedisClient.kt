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

package com.moneat.config

import io.lettuce.core.Range

/**
 * Redis client wrapper for on-call escalation engine.
 * Provides helper methods for sorted set operations used in timeout tracking.
 */
class RedisClient {

    fun zadd(
        key: String,
        score: Double,
        member: String
    ) {
        RedisConfig.sync().zadd(key, score, member)
    }

    fun zrem(
        key: String,
        member: String
    ) {
        RedisConfig.sync().zrem(key, member)
    }

    fun zrange(
        key: String,
        start: Long,
        stop: Long
    ): List<String> {
        return RedisConfig.sync().zrange(key, start, stop).toList()
    }

    fun zrangebyscore(
        key: String,
        min: Double,
        max: Double
    ): List<String> {
        return RedisConfig.sync().zrangebyscore(key, Range.create(min, max)).toList()
    }

    fun zrangeWithScores(
        key: String,
        start: Long,
        stop: Long
    ): List<Pair<String, Double>> {
        return RedisConfig.sync().zrangeWithScores(key, start, stop).map {
            it.value to it.score
        }
    }

    fun get(key: String): String? {
        return RedisConfig.sync().get(key)
    }

    fun set(
        key: String,
        value: String
    ) {
        RedisConfig.sync().set(key, value)
    }

    fun del(key: String) {
        RedisConfig.sync().del(key)
    }

    fun expire(
        key: String,
        seconds: Long
    ) {
        RedisConfig.sync().expire(key, seconds)
    }
}
