package com.dafeiyu.pet.weather

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * 天气查询。
 *
 * 直接移植 Python 版 `_get_weather()`：
 *   接口 https://wttr.in/{city}?format=j1
 *   取 current_condition[0].temp_C 与 weatherDesc[0].value
 *   weatherDesc 走一张英文->中文的映射表
 *
 * 用 HttpURLConnection + org.json（Android 自带），不引入第三方网络库。
 */
object WeatherClient {

    data class Weather(val temp: String, val desc: String)

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "weather-io").apply { isDaemon = true }
    }

    /** 与原程序一致的英文描述映射 */
    private val DESC_MAP = mapOf(
        "Sunny" to "晴",
        "Clear" to "晴",
        "Partly cloudy" to "多云",
        "Cloudy" to "阴",
        "Overcast" to "阴",
        "Mist" to "薄雾",
        "Fog" to "雾",
        "Light rain" to "小雨",
        "Light rain shower" to "阵雨",
        "Moderate rain" to "中雨",
        "Moderate rain at times" to "中雨",
        "Heavy rain" to "大雨",
        "Patchy rain nearby" to "局部有雨",
        "Patchy rain possible" to "可能有雨",
        "Thundery outbreaks possible" to "可能有雷阵雨",
        "Light snow" to "小雪",
        "Moderate snow" to "中雪",
        "Heavy snow" to "大雪",
    )

    /**
     * 在后台线程查询天气，结果回到主线程回调。
     * @param onResult 成功给 [Weather]，失败给异常（由调用方转成台词）
     */
    fun fetch(city: String, onResult: (Result<Weather>) -> Unit) {
        io.execute {
            val result = runCatching {
                val encoded = URLEncoder.encode(city, "UTF-8")
                val url = URL("https://wttr.in/$encoded?format=j1")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                try {
                    if (conn.responseCode != 200) {
                        error("HTTP ${conn.responseCode}")
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    parse(body)
                } finally {
                    conn.disconnect()
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onResult(result)
            }
        }
    }

    private fun parse(body: String): Weather {
        val root = JSONObject(body)
        val current = root.getJSONArray("current_condition").getJSONObject(0)
        val temp = current.optString("temp_C", "?")
        val rawDesc = current.getJSONArray("weatherDesc").getJSONObject(0)
            .optString("value", "")
        val desc = DESC_MAP[rawDesc] ?: rawDesc.ifBlank { "未知" }
        return Weather(temp, desc)
    }
}
