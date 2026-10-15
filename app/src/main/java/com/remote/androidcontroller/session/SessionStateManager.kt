package com.remote.androidcontroller.session

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.System.currentTimeMillis
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SessionStateManager: persistently manages active remote control sessions.
 * Stores a JSON map of sessionId -> SessionMeta in an atomic file under filesDir.
 */
class SessionStateManager(private val storageFile: File) {

    data class SessionMeta(
        val sessionId: String,
        val role: String,
        val startedAt: Long,
        var lastHeartbeat: Long,
        var state: String
    )

    private val lock = ReentrantLock()

    private fun readAll(): MutableMap<String, SessionMeta> {
        lock.withLock {
            if (!storageFile.exists()) return mutableMapOf()
            return try {
                val content = storageFile.readText(Charsets.UTF_8)
                val obj = JSONObject(content)
                val map = mutableMapOf<String, SessionMeta>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.getJSONObject(k)
                    map[k] = SessionMeta(
                        sessionId = k,
                        role = v.getString("role"),
                        startedAt = v.getLong("startedAt"),
                        lastHeartbeat = v.getLong("lastHeartbeat"),
                        state = v.getString("state")
                    )
                }
                map
            } catch (e: Exception) {
                mutableMapOf()
            }
        }
    }

    private fun writeAll(map: Map<String, SessionMeta>) {
        lock.withLock {
            val atomic = AtomicFile(storageFile)
            val root = JSONObject()
            map.forEach { (k, v) ->
                root.put(k, JSONObject().apply {
                    put("role", v.role)
                    put("startedAt", v.startedAt)
                    put("lastHeartbeat", v.lastHeartbeat)
                    put("state", v.state)
                })
            }
            val bytes = root.toString().toByteArray(Charsets.UTF_8)
            val out = atomic.startWrite()
            try {
                out.write(bytes)
                atomic.finishWrite(out)
            } catch (t: Throwable) {
                atomic.failWrite(out)
                throw t
            }
        }
    }

    fun saveSession(meta: SessionMeta) {
        lock.withLock {
            val map = readAll()
            map[meta.sessionId] = meta
            writeAll(map)
        }
    }

    fun getSession(sessionId: String): SessionMeta? = lock.withLock { readAll()[sessionId] }

    fun listSessions(): List<SessionMeta> = lock.withLock { readAll().values.toList() }

    fun removeSession(sessionId: String) {
        lock.withLock {
            val map = readAll()
            if (map.remove(sessionId) != null) writeAll(map)
        }
    }

    fun cleanupStaleSessions(maxAgeMs: Long): List<String> {
        val now = currentTimeMillis()
        val removed = mutableListOf<String>()
        lock.withLock {
            val map = readAll()
            val iter = map.entries.iterator()
            while (iter.hasNext()) {
                val e = iter.next()
                val meta = e.value
                if (now - meta.lastHeartbeat > maxAgeMs) {
                    removed.add(e.key)
                    iter.remove()
                }
            }
            writeAll(map)
        }
        return removed
    }
}
