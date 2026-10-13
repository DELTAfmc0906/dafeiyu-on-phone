package com.dafeiyu.pet.update

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** 一次更新检查的结果 */
data class UpdateInfo(
    /** 远端最新版本号，如 "1.2.1" */
    val version: String,
    /** Release 标题 */
    val title: String,
    /** Release 说明（Markdown 原文） */
    val notes: String,
    /** 该 Release 的网页地址 */
    val pageUrl: String,
    /** APK 资产直链。没有附带 APK 时为 null */
    val apkUrl: String?,
    /** 发布时间（原始 ISO 字符串） */
    val publishedAt: String
) {
    /** 与当前版本比较，是否需要更新 */
    fun isNewerThan(current: String): Boolean = compareVersion(version, current) > 0

    companion object {
        /**
         * 版本号比较。支持 "1.2"、"1.2.1"、"v1.2.1" 这类形式。
         *
         * 逐段按数字比，缺失的段当 0：
         *   "1.2.1" vs "1.2"   -> 1   （前者更新）
         *   "1.3"   vs "1.2.9" -> 1
         *   "1.2"   vs "1.2"   -> 0
         *   "1.2"   vs "1.10"  -> -1  （按数字比，不是字符串比）
         */
        fun compareVersion(a: String, b: String): Int {
            fun parts(s: String) = s.trim()
                .removePrefix("v").removePrefix("V")
                .split('.', '-', '+')
                .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

            val pa = parts(a)
            val pb = parts(b)
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return if (x > y) 1 else -1
            }
            return 0
        }
    }
}

/**
 * GitHub Release 更新检查。
 *
 * v1.2.1 新增。直接查 GitHub 公开 API：
 *
 *     GET https://api.github.com/repos/{owner}/{repo}/releases/latest
 *
 * 设计取舍：
 *   - **不自动下载安装 APK**。Android 8+ 对"安装未知来源应用"有额外权限
 *     （REQUEST_INSTALL_PACKAGES），而且静默安装 APK 容易被安全软件拦、
 *     也容易让用户不放心。所以只做"检查 + 跳转浏览器"，把安装交给用户点。
 *   - 未登录也能查公开仓库，但有 60 次/小时的匿名限流，所以结果缓存 30 分钟。
 *   - 没有 Release 时 GitHub 返回 404，视作"暂无发布"而不是错误。
 */
object UpdateClient {

    /** 目标仓库。改这里即可指向自己的仓库 */
    const val REPO_OWNER = "DELTAfmc0906"
    const val REPO_NAME = "dafeiyu-on-phone"

    val repoUrl: String get() = "https://github.com/$REPO_OWNER/$REPO_NAME"
    val releasesUrl: String get() = "$repoUrl/releases"
    val latestApiUrl: String
        get() = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 12_000

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "update-io").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** 缓存，避免频繁请求触发匿名限流 */
    private var cached: Pair<Long, Result<UpdateInfo?>>? = null
    private const val CACHE_MS = 30 * 60 * 1000L

    /**
     * 查询最新 Release。
     *
     * @param force 忽略缓存强制刷新
     * @param onResult 回主线程。成功且**有 Release** 时给 [UpdateInfo]；
     *                 仓库存在但还没有任何 Release 时给 null；
     *                 失败时给 failure。
     */
    fun checkLatest(
        force: Boolean = false,
        onResult: (Result<UpdateInfo?>) -> Unit
    ) {
        val now = System.currentTimeMillis()
        cached?.let { (at, r) ->
            if (!force && now - at < CACHE_MS) {
                main.post { onResult(r) }
                return
            }
        }

        io.execute {
            val result = runCatching { fetchLatest() }
            cached = now to result
            main.post { onResult(result) }
        }
    }

    /** 清掉缓存（用户手动点"检查更新"时用） */
    fun clearCache() {
        cached = null
    }

    private fun fetchLatest(): UpdateInfo? {
        val conn = (URL(latestApiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "dafeiyu-pet-android")
        }
        try {
            when (val code = conn.responseCode) {
                200 -> Unit
                404 -> return null // 仓库没有 Release（或仓库不存在），视作"暂无发布"
                403 -> error("请求过于频繁，请稍后再试")
                else -> error("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)

            val tag = o.optString("tag_name").ifBlank { o.optString("name") }
            val title = o.optString("name").ifBlank { tag }
            val notes = o.optString("body").orEmpty()
            val pageUrl = o.optString("html_url").ifBlank { releasesUrl }
            val publishedAt = o.optString("published_at").orEmpty()

            // 在 assets 里找 APK
            var apk: String? = null
            o.optJSONArray("assets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apk = a.optString("browser_download_url")
                        break
                    }
                }
            }

            return UpdateInfo(
                version = tag.removePrefix("v").removePrefix("V"),
                title = title,
                notes = notes,
                pageUrl = pageUrl,
                apkUrl = apk,
                publishedAt = publishedAt
            )
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
