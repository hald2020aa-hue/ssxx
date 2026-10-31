package com.openmanga.reader.data

import com.openmanga.engine.Chapter
import com.openmanga.engine.Json
import com.openmanga.engine.ListingPage
import com.openmanga.engine.MangaItem
import java.io.File
import java.security.MessageDigest

class Cached<T>(val value: T, val savedAt: Long)

/** Keeps the last successful data of every list so it can be shown (clearly labelled) when the network fails. */
class ListCache(private val dir: File) {
    init { dir.mkdirs() }

    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }

    private fun write(f: File, data: Any) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(Json.stringify(data))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }

    private fun read(f: File): Map<*, *>? = try {
        if (f.exists()) Json.parse(f.readText()) as? Map<*, *> else null
    } catch (e: Exception) {
        null
    }

    fun saveListing(sourceId: String, kind: String, page: Int, p: ListingPage) {
        write(
            file("L|$sourceId|$kind|$page"),
            mapOf(
                "savedAt" to System.currentTimeMillis(), "hasNext" to p.hasNext,
                "items" to p.items.map {
                    mapOf("title" to it.title, "url" to it.url, "cover" to it.cover, "latest" to it.latestChapter, "updatedText" to it.updatedText, "updatedAt" to it.updatedAt)
                },
            ),
        )
    }

    fun loadListing(sourceId: String, kind: String, page: Int): Cached<ListingPage>? {
        val m = read(file("L|$sourceId|$kind|$page")) ?: return null
        val items = (m["items"] as? List<*>)?.mapNotNull { e ->
            val o = e as? Map<*, *> ?: return@mapNotNull null
            MangaItem(
                title = o["title"] as? String ?: return@mapNotNull null, url = o["url"] as? String ?: return@mapNotNull null,
                cover = o["cover"] as? String, latestChapter = o["latest"] as? String, updatedText = o["updatedText"] as? String,
                updatedAt = (o["updatedAt"] as? Number)?.toLong(),
            )
        } ?: return null
        return Cached(ListingPage(items, m["hasNext"] as? Boolean ?: false), (m["savedAt"] as? Number)?.toLong() ?: 0L)
    }

    fun saveChapters(sourceId: String, mangaUrl: String, list: List<Chapter>) {
        write(
            file("C|$sourceId|$mangaUrl"),
            mapOf(
                "savedAt" to System.currentTimeMillis(),
                "chapters" to list.map { mapOf("name" to it.name, "url" to it.url, "number" to it.number, "dateText" to it.dateText, "dateMillis" to it.dateMillis) },
            ),
        )
    }

    fun loadChapters(sourceId: String, mangaUrl: String): Cached<List<Chapter>>? {
        val m = read(file("C|$sourceId|$mangaUrl")) ?: return null
        val list = (m["chapters"] as? List<*>)?.mapNotNull { e ->
            val o = e as? Map<*, *> ?: return@mapNotNull null
            Chapter(
                name = o["name"] as? String ?: return@mapNotNull null, url = o["url"] as? String ?: return@mapNotNull null,
                number = (o["number"] as? Number)?.toDouble(), dateText = o["dateText"] as? String, dateMillis = (o["dateMillis"] as? Number)?.toLong(),
            )
        } ?: return null
        return Cached(list, (m["savedAt"] as? Number)?.toLong() ?: 0L)
    }
}
