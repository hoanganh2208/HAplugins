package com.example.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Kênh do người dùng tự thêm trong phần cài đặt plugin. licenseKey giữ nguyên chuỗi gốc (kid:key / JSON / URL). */
data class CustomChannel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val licenseKey: String?,
    val userAgent: String?,
    val referer: String?,
)

class CustomChannelStore(context: Context) {
    companion object {
        const val DEFAULT_GROUP = "Kênh của tôi"
        private const val PREFS = "iptv_m3u_custom"
        private const val KEY = "channels"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<CustomChannel> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val url = o.optString("url")
                if (name.isBlank() || url.isBlank()) {
                    null
                } else {
                    CustomChannel(
                        name = name,
                        url = url,
                        group = o.optString("group").ifBlank { DEFAULT_GROUP },
                        logo = o.optString("logo").ifBlank { null },
                        licenseKey = o.optString("licenseKey").ifBlank { null },
                        userAgent = o.optString("userAgent").ifBlank { null },
                        referer = o.optString("referer").ifBlank { null },
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<CustomChannel>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("name", c.name)
                    .put("url", c.url)
                    .put("group", c.group)
                    .put("logo", c.logo.orEmpty())
                    .put("licenseKey", c.licenseKey.orEmpty())
                    .put("userAgent", c.userAgent.orEmpty())
                    .put("referer", c.referer.orEmpty())
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** Trả về false nếu link đã tồn tại (provider tra kênh theo link nên không cho trùng). */
    @Synchronized
    fun add(channel: CustomChannel): Boolean {
        val list = all()
        if (list.any { it.url == channel.url }) return false
        save(list + channel)
        return true
    }

    @Synchronized
    fun removeAt(indices: Set<Int>) {
        save(all().filterIndexed { i, _ -> i !in indices })
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY).apply()
    }
}
