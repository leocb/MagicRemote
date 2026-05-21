package com.leobottaro.magicremote.data.storage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val pairedAt: Long = System.currentTimeMillis()
)

class ConnectionRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("connections", Context.MODE_PRIVATE)

    fun list(): List<SavedConnection> {
        val json = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            SavedConnection(
                id = obj.getString("id"),
                name = obj.getString("name"),
                host = obj.getString("host"),
                pairedAt = obj.optLong("pairedAt", System.currentTimeMillis())
            )
        }
    }

    fun add(connection: SavedConnection) {
        val list = list().toMutableList()
        list.add(connection)
        saveAll(list)
    }

    fun update(connection: SavedConnection) {
        val list = list().toMutableList()
        val idx = list.indexOfFirst { it.id == connection.id }
        if (idx >= 0) {
            list[idx] = connection
            saveAll(list)
        }
    }

    fun delete(id: String) {
        val list = list().toMutableList()
        list.removeAll { it.id == id }
        saveAll(list)
    }

    fun deleteAll() {
        prefs.edit().remove(KEY).apply()
    }

    fun count(): Int = list().size

    private fun saveAll(connections: List<SavedConnection>) {
        val arr = JSONArray()
        connections.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("host", c.host)
                put("pairedAt", c.pairedAt)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "saved_connections"
    }
}
