package com.cheese.app.data.cloud

import android.util.Xml
import com.cheese.app.domain.CLOUD_BASE_URL
import com.cheese.app.domain.CLOUD_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One configuration published in the shared bucket. */
data class CloudEntry(
    val key: String,
    val sizeBytes: Long,
    /** Raw S3 timestamp; compared as a string, which sorts correctly for ISO-8601. */
    val lastModified: String,
)

/**
 * Reads the shared configuration bucket: a plain S3 bucket with public list
 * access, so the listing is its own `ListBucketResult` XML and each config is
 * fetched by key. Every request carries [CLOUD_USER_AGENT] so the owner can tell
 * app traffic from a browser.
 */
class CloudConfigs(private val baseUrl: String = CLOUD_BASE_URL) {

    suspend fun list(): List<CloudEntry> = withContext(Dispatchers.IO) {
        parseListing(get(baseUrl))
    }

    suspend fun fetch(key: String): String = withContext(Dispatchers.IO) {
        get("$baseUrl/${encodeKey(key)}")
    }

    /** True when a configuration with this key is already published. */
    suspend fun exists(key: String): Boolean = list().any { it.key == key }

    /**
     * Publish one configuration.
     *
     * The bucket accepts anonymous PUT but not DELETE, so an entry cannot be
     * taken back once written — callers must check [exists] first and never
     * overwrite somebody else's entry.
     */
    suspend fun upload(key: String, body: String) = withContext(Dispatchers.IO) {
        val connection = (URL("$baseUrl/${encodeKey(key)}").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", CLOUD_USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", CLOUD_USER_AGENT)
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    /** Keep only the `.json` objects, newest first. */
    private fun parseListing(xml: String): List<CloudEntry> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val entries = mutableListOf<CloudEntry>()
        var inContents = false
        var key: String? = null
        var size = 0L
        var modified = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Contents" -> {
                        inContents = true
                        key = null
                        size = 0L
                        modified = ""
                    }

                    "Key" -> if (inContents) key = parser.nextText()
                    "Size" -> if (inContents) size = parser.nextText().trim().toLongOrNull() ?: 0L
                    "LastModified" -> if (inContents) modified = parser.nextText()
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "Contents") {
                inContents = false
                val name = key
                if (name != null && name.endsWith(".json", ignoreCase = true)) {
                    entries += CloudEntry(name, size, modified)
                }
            }
            event = parser.next()
        }
        return entries.sortedByDescending { it.lastModified }
    }

    private fun encodeKey(key: String): String =
        URLEncoder.encode(key, "UTF-8").replace("+", "%20")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
