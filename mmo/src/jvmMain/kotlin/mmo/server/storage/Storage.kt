package mmo.server.storage

import java.util.*
import kotlin.collections.LinkedHashMap

interface Storage {
    fun set(collection: String): StorageSet
    fun map(collection: String): StorageMap
}

interface StorageSet {
    suspend fun add(item: String)
    suspend fun contains(item: String): Boolean
    suspend fun remove(item: String)
    suspend fun clear()
    suspend fun list(): List<String>
}

interface StorageMap {
    suspend fun put(key: String, value: String)
    suspend fun get(key: String): String?
    suspend fun contains(key: String): Boolean
    suspend fun remove(key: String)
    suspend fun listKeys(): List<String>
    suspend fun map(): Map<String, String>
    suspend fun clear()

    suspend fun incr(key: String, delta: Long = +1): Long
}

/////////////////////////////////////////////////////////////////////////////////////////////////

class InmemoryStorage : Storage {
    val sets = Collections.synchronizedMap(LinkedHashMap<String, MutableSet<String>>())
    val maps = Collections.synchronizedMap(LinkedHashMap<String, MutableMap<String, String>>())

    override fun set(collection: String): StorageSet = object : StorageSet {
        val set get() = sets.getOrPut(collection) { Collections.synchronizedSet(LinkedHashSet()) }

        override suspend fun add(item: String): Unit = run { set.add(item) }
        override suspend fun contains(item: String) = item in set
        override suspend fun remove(item: String): Unit = run { set.remove(item) }
        override suspend fun clear(): Unit = run { sets.remove(collection) }
        override suspend fun list(): List<String> = set.toList()
    }

    override fun map(collection: String): StorageMap = object : StorageMap {
        val map get() = maps.getOrPut(collection) { Collections.synchronizedMap(LinkedHashMap()) }

        override suspend fun put(key: String, value: String) = run { map[key] = value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun contains(key: String): Boolean = key in map
        override suspend fun remove(key: String): Unit = run { map.remove(key) }
        override suspend fun listKeys(): List<String> = map.keys.toList()
        override suspend fun clear(): Unit = run { maps.remove(collection) }
        override suspend fun map(): Map<String, String> = map.toMap()

        override suspend fun incr(key: String, delta: Long): Long {
            val vv = ((map[key]?.toLong() ?: 0L) + delta)
            map[key] = vv.toString()
            return vv
        }
    }
}

/////////////////////////////////////////////////////////////////////////////////////////////////
/*
class RedisStorage(val redis: Redis, val prefix: String = "") : Storage {
    override fun set(collection: String): StorageSet = object : StorageSet {
        val pcol = "$prefix$collection"
        override suspend fun add(item: String): Unit = run { redis.sadd(pcol, item) }
        override suspend fun contains(item: String): Boolean = redis.sismember(pcol, item)
        override suspend fun remove(item: String): Unit = run { redis.srem(pcol, item) }
        override suspend fun list(): List<String> = redis.smembers(pcol).toList()
        override suspend fun clear(): Unit = run { redis.del(pcol) }
    }

    override fun map(collection: String): StorageMap = object : StorageMap {
        val pcol = "$prefix$collection"
        override suspend fun put(key: String, value: String): Unit = run { redis.hset(pcol, key, value) }
        override suspend fun get(key: String): String? = redis.hget(pcol, key)
        override suspend fun contains(key: String): Boolean = redis.hexists(pcol, key)
        override suspend fun remove(key: String): Unit = run { redis.hdel(pcol, key) }
        override suspend fun listKeys(): List<String> = redis.hkeys(pcol).toList()
        override suspend fun map(): Map<String, String> = redis.hgetall(pcol)
        override suspend fun incr(key: String, delta: Long): Long = redis.hincrby(pcol, key, delta)
        override suspend fun clear(): Unit = run { redis.del(pcol) }
    }
}
*/
