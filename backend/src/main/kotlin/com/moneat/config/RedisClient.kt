package com.moneat.config

/**
 * Redis client wrapper for on-call escalation engine.
 * Provides helper methods for sorted set operations used in timeout tracking.
 */
class RedisClient {
    
    fun zadd(key: String, score: Double, member: String) {
        RedisConfig.sync().zadd(key, score, member)
    }
    
    fun zrem(key: String, member: String) {
        RedisConfig.sync().zrem(key, member)
    }
    
    fun zrange(key: String, start: Long, stop: Long): List<String> {
        return RedisConfig.sync().zrange(key, start, stop).toList()
    }
    
    @Suppress("DEPRECATION")
    fun zrangebyscore(key: String, min: Double, max: Double): List<String> {
        return RedisConfig.sync().zrangebyscore(key, min, max).toList()
    }
    
    fun zrangeWithScores(key: String, start: Long, stop: Long): List<Pair<String, Double>> {
        return RedisConfig.sync().zrangeWithScores(key, start, stop).map { 
            it.value to it.score 
        }
    }
    
    fun get(key: String): String? {
        return RedisConfig.sync().get(key)
    }
    
    fun set(key: String, value: String) {
        RedisConfig.sync().set(key, value)
    }
    
    fun del(key: String) {
        RedisConfig.sync().del(key)
    }
    
    fun expire(key: String, seconds: Long) {
        RedisConfig.sync().expire(key, seconds)
    }
}
