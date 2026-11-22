package com.example.iptv

import android.util.Base64
import org.json.JSONObject
import java.util.UUID

/** kid/key lưu dạng hex 32 ký tự; hoặc licenseUrl nếu trỏ tới license server ClearKey. */
data class ClearKey(
    val kidHex: String?,
    val keyHex: String?,
    val licenseUrl: String?,
)

object ClearKeyUtil {
    // UUID của scheme ClearKey (giống C.CLEARKEY_UUID của ExoPlayer).
    val UUID_CLEARKEY: UUID = UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")

    // true  = gửi kid/key cho player dạng base64url (chuẩn JWK của ClearKey).
    // false = gửi nguyên dạng hex. Nếu phát ra lỗi license/key thì thử đảo cờ này.
    const val BASE64URL = true

    private val hex32Regex = Regex("[0-9a-fA-F]{32}")

    // Nhận hex 32 ký tự (có hoặc không có dấu '-') hoặc base64url 16 byte -> trả về hex thường.
    private fun toHexOrNull(s: String): String? {
        val t = s.trim().replace("-", "")
        if (hex32Regex.matches(t)) return t.lowercase()
        return try {
            val b = Base64.decode(s.trim(), Base64.URL_SAFE)
            if (b.size == 16) b.joinToString("") { "%02x".format(it) } else null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun encode(hex: String): String {
        if (!BASE64URL) return hex
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    // Các dạng license_key hay gặp:
    //   1. kid:key (hex)   -> "0123...:abcd..."  (nhiều cặp cách nhau dấu ',' -> chỉ lấy cặp đầu)
    //   2. JSON JWK        -> {"keys":[{"kty":"oct","k":"...","kid":"..."}]}
    //   3. URL license     -> https://...
    // Trả về null nếu không đọc được.
    fun parse(raw: String): ClearKey? {
        val v = raw.trim()
        return when {
            v.startsWith("http", ignoreCase = true) -> ClearKey(null, null, v)
            v.startsWith("{") -> try {
                val k = JSONObject(v).getJSONArray("keys").getJSONObject(0)
                val kid = toHexOrNull(k.getString("kid"))
                val key = toHexOrNull(k.getString("k"))
                if (kid != null && key != null) ClearKey(kid, key, null) else null
            } catch (e: Exception) {
                null
            }
            else -> {
                val first = v.split(",").first()
                val kid = toHexOrNull(first.substringBefore(":", ""))
                val key = toHexOrNull(first.substringAfter(":", ""))
                if (kid != null && key != null) ClearKey(kid, key, null) else null
            }
        }
    }
}
