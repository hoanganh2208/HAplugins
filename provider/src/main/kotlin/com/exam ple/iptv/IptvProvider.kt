package com.example.iptv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlin.coroutines.cancellation.CancellationException

class IptvProvider(private val store: CustomChannelStore? = null) : MainAPI() {
    override var name = "IPTV M3U"
    override var mainUrl = "https://example.com"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "vi"

    companion object {
        // ĐỔI thành link playlist .m3u của mày (link http/https công khai hoặc của chính mày).
        const val PLAYLIST_URL = "https://example.com/playlist.m3u"
    }

    data class Channel(
        val name: String,
        val url: String,
        val logo: String?,
        val group: String,
        val userAgent: String?,
        val referer: String?,
        val clearKey: ClearKey?,
        val manifestType: String?,
    )

    private var cache: List<Channel>? = null

    // Playlist tải một lần rồi cache. Tải lỗi thì trả rỗng (không cache) để kênh tự thêm vẫn dùng được.
    private suspend fun playlistChannels(): List<Channel> {
        cache?.let { return it }
        return try {
            parseM3u(app.get(PLAYLIST_URL).text).also { cache = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun CustomChannel.toChannel(): Channel? {
        // Có nhập license key mà không đọc được thì bỏ kênh (UI đã chặn lúc thêm, đây là phòng dữ liệu hỏng).
        val ck = licenseKey?.let { ClearKeyUtil.parse(it) ?: return null }
        return Channel(name, url, logo, group, userAgent, referer, ck, null)
    }

    // Kênh tự thêm đọc lại mỗi lần gọi nên thêm/xóa trong cài đặt có hiệu lực ngay, không cần cài lại plugin.
    private suspend fun channels(): List<Channel> {
        val custom = store?.all().orEmpty().mapNotNull { it.toChannel() }
        return custom + playlistChannels()
    }

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

    // Kênh free: phát bình thường. Kênh ClearKey: đọc kid/key từ #KODIPROP (hoặc thuộc tính license_* trong #EXTINF).
    // Kênh DRM khác (Widevine, PlayReady...) hoặc ClearKey không đọc được key thì bị bỏ qua.
    // #KODIPROP / #EXTVLCOPT được phép nằm trước hoặc sau dòng #EXTINF.
    private fun parseM3u(text: String): List<Channel> {
        val result = mutableListOf<Channel>()
        var name: String? = null
        var logo: String? = null
        var group = "Khác"
        var ua: String? = null
        var ref: String? = null
        var licenseType: String? = null
        var licenseKey: String? = null
        var manifestType: String? = null

        fun reset() {
            name = null; logo = null; group = "Khác"; ua = null; ref = null
            licenseType = null; licenseKey = null; manifestType = null
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    val attrs = attrRegex.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    logo = attrs["tvg-logo"]?.ifBlank { null }
                    group = attrs["group-title"]?.ifBlank { null } ?: "Khác"
                    name = line.substringAfterLast("\",", line.substringAfterLast(",")).trim()
                    attrs["license_type"]?.let { licenseType = it }
                    attrs["license_key"]?.let { licenseKey = it }
                }
                line.startsWith("#EXTVLCOPT:") -> {
                    val kv = line.removePrefix("#EXTVLCOPT:")
                    val key = kv.substringBefore("=").lowercase()
                    val value = kv.substringAfter("=", "").trim()
                    when (key) {
                        "http-user-agent" -> ua = value
                        "http-referrer", "http-referer" -> ref = value
                    }
                }
                line.startsWith("#KODIPROP:") -> {
                    val kv = line.removePrefix("#KODIPROP:")
                    val value = kv.substringAfter("=", "").trim() // value có thể chứa '=' (base64, URL)
                    when (kv.substringBefore("=").trim().lowercase()) {
                        "inputstream.adaptive.license_type" -> licenseType = value
                        "inputstream.adaptive.license_key" -> licenseKey = value
                        "inputstream.adaptive.manifest_type" -> manifestType = value.lowercase()
                    }
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val n = name
                    if (n != null && line.startsWith("http")) {
                        val hasDrm = licenseType != null || licenseKey != null
                        if (!hasDrm) {
                            result += Channel(n, line, logo, group, ua, ref, null, manifestType)
                        } else {
                            // Không có license_type mà có license_key thì coi như ClearKey.
                            val isClearKey = licenseType?.contains("clearkey", ignoreCase = true) ?: true
                            val ck = if (isClearKey) licenseKey?.let { ClearKeyUtil.parse(it) } else null
                            if (ck != null) {
                                result += Channel(n, line, logo, group, ua, ref, ck, manifestType)
                            }
                        }
                    }
                    reset()
                }
            }
        }
        return result
    }

    private fun Channel.toSearch(): LiveSearchResponse =
        newLiveSearchResponse(name, url, TvType.Live) { posterUrl = logo }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = channels()
            .groupBy { it.group }
            .map { (group, items) -> HomePageList(group, items.map { it.toSearch() }) }
        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        channels().filter { it.name.contains(query, ignoreCase = true) }.map { it.toSearch() }

    override suspend fun load(url: String): LoadResponse {
        val ch = channels().firstOrNull { it.url == url }
            ?: throw ErrorLoadingException("Không tìm thấy kênh")
        return newLiveStreamLoadResponse(ch.name, ch.url, ch.url) {
            posterUrl = ch.logo
            plot = ch.group
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ch = channels().firstOrNull { it.url == data } ?: return false
        val type = when {
            ch.manifestType == "mpd" || ch.url.contains(".mpd", true) -> ExtractorLinkType.DASH
            ch.manifestType == "hls" || ch.url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            ch.clearKey != null -> ExtractorLinkType.DASH // ClearKey trong IPTV gần như luôn là DASH
            else -> ExtractorLinkType.VIDEO
        }
        val ck = ch.clearKey
        if (ck == null) {
            callback(
                newExtractorLink(name, ch.name, ch.url, type) {
                    this.referer = ch.referer ?: ""
                    this.quality = Qualities.Unknown.value
                    ch.userAgent?.let { this.headers = mapOf("User-Agent" to it) }
                }
            )
        } else {
            callback(
                newDrmExtractorLink(name, ch.name, ch.url, type, ClearKeyUtil.UUID_CLEARKEY) {
                    this.referer = ch.referer ?: ""
                    this.quality = Qualities.Unknown.value
                    ch.userAgent?.let { this.headers = mapOf("User-Agent" to it) }
                    ck.kidHex?.let { this.kid = ClearKeyUtil.encode(it) }
                    ck.keyHex?.let { this.key = ClearKeyUtil.encode(it) }
                    ck.licenseUrl?.let { this.licenseUrl = it }
                }
            )
        }
        return true
    }
}
