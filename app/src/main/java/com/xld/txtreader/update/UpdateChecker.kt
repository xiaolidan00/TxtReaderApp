package com.xld.txtreader.update

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** GitHub Release 中可安装的最新版本信息 */
data class AppUpdate(
    /** 版本号（tag 去掉 v 前缀后的数字段），如 "1.1" */
    val versionName: String,
    /** 新版本描述（release body） */
    val description: String,
    /** APK 下载地址（release asset 中以 .apk 结尾的项） */
    val apkUrl: String,
    /** APK 文件字节数（release asset 的 size），用于校验本地已下载文件完整性，0 = 未知 */
    val apkSize: Long
)

/**
 * 检查 GitHub Release 是否有新版本。
 *
 * 输出：有新版且带 APK 资源时返回 [AppUpdate]，否则返回 null。
 * 网络失败 / 无新版 / tag 无法解析一律静默返回 null，不打扰用户。
 */
object UpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/xiaolidan00/TxtReaderApp/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    private val VERSION_PATTERN = Regex("""[vV]?\d+(?:\.\d+)*""")

    suspend fun check(localVersion: String): AppUpdate? = withContext(Dispatchers.IO) {
        try {
            val json = fetchLatestRelease() ?: return@withContext null
            val version = normalizeVersion(json.optString("tag_name"))
            if (version.isEmpty() || !isNewer(version, localVersion)) {
                return@withContext null
            }
            val apkAsset = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length())
                    .mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            } ?: return@withContext null
            val apkUrl = apkAsset.optString("browser_download_url")
                .takeIf { it.isNotBlank() } ?: return@withContext null

            AppUpdate(
                versionName = version,
                description = json.optString("body").trim(),
                apkUrl = apkUrl,
                apkSize = apkAsset.optLong("size", 0L)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchLatestRelease(): JSONObject? {
        val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "TxtReaderApp")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { JSONObject(it.readBytes().decodeToString()) }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 归一化版本号：匹配开头的版本字段并去掉 v 前缀。
     * "v1.2.3-beta" → "1.2.3"，"1.0" → "1.0"，无法匹配 → ""
     */
    fun normalizeVersion(tag: String): String =
        VERSION_PATTERN.find(tag.trim())?.value?.trimStart('v', 'V').orEmpty()

    /**
     * 远端版本是否比本地版本新。
     *
     * 按 `.` 分段做数值比较（1.10 > 1.9），段数不同时较短一侧补 0；
     * 任一侧解析不出数字时返回 false。
     */
    fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = remote.split('.').mapNotNull { it.toIntOrNull() }
        val localParts = local.split('.').mapNotNull { it.toIntOrNull() }
        if (remoteParts.isEmpty() || localParts.isEmpty()) return false
        for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }
}
