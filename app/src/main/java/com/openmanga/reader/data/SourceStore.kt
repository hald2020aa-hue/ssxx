package com.openmanga.reader.data

import com.openmanga.engine.ConfigJson
import com.openmanga.engine.Json
import com.openmanga.engine.SourceConfig
import java.io.File

/**
 * Persists every source's settings locally so the user never has to add a site twice.
 * One corrupt entry is skipped instead of making the whole list unreadable.
 */
class SourceStore(private val dir: File) {
    private val file = File(dir, "sources.json")
    private var memory: MutableList<SourceConfig>? = null

    @Synchronized
    fun all(): List<SourceConfig> = load().toList()

    @Synchronized
    fun get(id: String): SourceConfig? = load().firstOrNull { it.id == id }

    @Synchronized
    fun save(config: SourceConfig) {
        val list = load()
        val i = list.indexOfFirst { it.id == config.id }
        if (i >= 0) list[i] = config else list.add(config)
        write(list)
    }

    @Synchronized
    fun delete(id: String) {
        val list = load()
        if (list.removeAll { it.id == id }) write(list)
    }

    private fun load(): MutableList<SourceConfig> {
        memory?.let { return it }
        val result = ArrayList<SourceConfig>()
        if (file.exists()) {
            try {
                val root = Json.parse(file.readText()) as? List<*> ?: emptyList<Any>()
                for (entry in root) {
                    val m = entry as? Map<*, *> ?: continue
                    runCatching { ConfigJson.fromMap(m) }.getOrNull()?.let { result.add(it) }
                }
            } catch (e: Exception) {
                file.renameTo(File(dir, "sources.corrupt.json"))
            }
        }
        memory = result
        return result
    }

    private fun write(list: List<SourceConfig>) {
        dir.mkdirs()
        val tmp = File(dir, "sources.json.tmp")
        tmp.writeText(ConfigJson.listToJson(list))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
