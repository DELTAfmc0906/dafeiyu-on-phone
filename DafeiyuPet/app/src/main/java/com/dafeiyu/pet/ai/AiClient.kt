package com.dafeiyu.pet.ai

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** 一条对话消息 */
data class ChatMessage(val role: String, val content: String) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"
    }
}

/**
 * AI 对话客户端（Ask 模式）。
 *
 * v1.2 新增。设计对齐原 Python 版 `_call_ds()`，但有几点不同：
 *
 * | | Python 版 | 这里 |
 * |---|---|---|
 * | 接口 | 写死 DeepSeek | 任意 OpenAI 兼容端点 |
 * | 模型 | 写死 deepseek-chat（**已停用**） | 可配置，默认 deepseek-flash |
 * | 返回 | 一次性 | **流式**，边生成边冒泡，体感更像"它在说话" |
 * | 超时 | 10s | 连接 15s / 读取 60s（LLM 首字延迟可能很长） |
 * | 历史 | 保留最近 40 条 | 同样 40 条 |
 *
 * 不引入 OkHttp 等第三方库，用 HttpURLConnection + org.json，保持零网络依赖。
 */
object AiClient {

    /** 系统提示词，沿用原 Python 版的人设，并补充"要贴桌宠身份"的约束 */
    const val SYSTEM_PROMPT =
        "你是桌面宠物大肥鱼，贱兮兮但可爱，每句话不超过25字，偶尔吐槽主人但别真骂人。"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    /**
     * 单次回复的 token 上限。
     *
     * **这里踩过一个坑**：原先设 200，结果 deepseek-flash 这类"带思考"的模型
     * 会把额度全烧在 `reasoning_content` 上（实测一次五个字的请求思考了 73KB），
     * 正文 `content` 始终为空，`finish_reason` 直接是 `length`。
     * 所以如果开思考，必须给足额度。
     */
    private const val MAX_TOKENS = 2048

    /** 关思考时可以小一些，省 token */
    private const val MAX_TOKENS_NO_THINK = 600

    /** 对话历史上限，与原程序一致 */
    const val MAX_HISTORY = 40

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-io").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /**
     * 把用户填的 base url 归一化成 `.../chat/completions`。
     *
     * 容错这几种写法（用户经常填错，不该为此报错）：
     *   https://api.deepseek.com            -> https://api.deepseek.com/chat/completions
     *   https://api.deepseek.com/           -> 同上
     *   https://api.deepseek.com/v1         -> https://api.deepseek.com/v1/chat/completions
     *   https://api.deepseek.com/v1/        -> 同上
     *   https://x/v1/chat/completions       -> 原样返回
     *   域名不含协议                         -> 自动补 https://
     */
    fun normalizeEndpoint(baseUrl: String): String {
        var u = baseUrl.trim()
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://$u"
        }
        u = u.trimEnd('/')
        if (u.endsWith("/chat/completions")) return u
        return "$u/chat/completions"
    }

    /** 回调。全部回主线程。 */
    interface Callback {
        /** 正文增量。可能被调用多次，每次是**到目前为止的全文** */
        fun onDelta(text: String)
        /**
         * 思考过程增量（带思考的模型才有，如 deepseek-flash）。
         * 默认空实现，不需要展示思考的实现可以不重写。
         */
        fun onThinking(text: String) {}
        /** 正常结束，text 是完整回复 */
        fun onComplete(text: String)
        /** 失败，message 是可以直接冒泡给用户看的中文短语 */
        fun onError(message: String)
    }

    /**
     * 发起一次对话。
     *
     * @param baseUrl 形如 `https://api.deepseek.com/v1`
     * @param apiKey  形如 `sk-xxxx`
     * @param model   形如 `deepseek-flash`
     * @param history 之前的对话（不含本条），会按 [MAX_HISTORY] 截断
     * @param userText 用户这次说的话
     * @param enableThinking 是否开启模型的思考（思维链）。
     *        开启后思考内容走 `onThinking`，正文走 `onDelta`；
     *        关闭可显著降低 token 消耗与延迟（实测响应体积差 50 倍）。
     */
    fun ask(
        baseUrl: String,
        apiKey: String,
        model: String,
        history: List<ChatMessage>,
        userText: String,
        enableThinking: Boolean = false,
        callback: Callback
    ) {
        val endpoint = normalizeEndpoint(baseUrl)
        if (endpoint.isEmpty()) {
            post { callback.onError("请先配置 API 地址") }
            return
        }
        if (apiKey.isBlank()) {
            post { callback.onError("请先设置 API Key") }
            return
        }
        if (model.isBlank()) {
            post { callback.onError("请先配置模型名") }
            return
        }

        io.execute {
            var conn: HttpURLConnection? = null
            try {
                val messages = JSONArray().apply {
                    put(JSONObject().put("role", ChatMessage.ROLE_SYSTEM).put("content", SYSTEM_PROMPT))
                    history.takeLast(MAX_HISTORY).forEach {
                        put(JSONObject().put("role", it.role).put("content", it.content))
                    }
                    put(
                        JSONObject().put("role", ChatMessage.ROLE_USER)
                            .put("content", userText)
                    )
                }
                val payload = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("max_tokens", if (enableThinking) MAX_TOKENS else MAX_TOKENS_NO_THINK)
                    put("temperature", 0.9)
                    put("stream", true)
                    // DeepSeek 的思考开关。不开的话模型会把 token 全烧在
                    // reasoning_content 上，正文字段一直是空的（见 MAX_TOKENS 注释）。
                    if (enableThinking) {
                        put("thinking", JSONObject().put("type", "enabled"))
                    } else {
                        put("thinking", JSONObject().put("type", "disabled"))
                    }
                }

                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Accept", "text/event-stream")
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val body = (conn.errorStream ?: conn.inputStream)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                    post { callback.onError(friendlyError(code, body)) }
                    return@execute
                }

                // ---- 解析 SSE ----
                val contentBuf = StringBuilder()
                val thinkBuf = StringBuilder()
                var sawContent = false
                var finishReason: String? = null

                BufferedReader(conn.inputStream.reader(Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line!!.trim()
                        if (l.isEmpty() || !l.startsWith("data:")) continue
                        val data = l.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        try {
                            val choice = JSONObject(data)
                                .optJSONArray("choices")
                                ?.optJSONObject(0) ?: continue
                            choice.optStringOrNull("finish_reason")
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { finishReason = it }
                            val delta = choice.optJSONObject("delta") ?: continue

                            // ★ 关键修复：
                            // 之前写 .optString("content")，而 org.json 的 optString
                            // 遇到 JSON 的 null 值会返回**字符串 "null"**（它只判断 key
                            // 存不存在，不做空值判断）。流式响应里正文帧长这样：
                            //     {"delta":{"content":null,"reasoning_content":"..."}}
                            // 于是每帧都往气泡追加一个字面量 "null"，
                            // 用户看到的就是一大串 "nullnullnull..."。
                            // 必须用 optStringOrNull 区分 null 与空串。
                            val c = delta.optStringOrNull("content")
                            if (!c.isNullOrEmpty()) {
                                sawContent = true
                                contentBuf.append(c)
                                val snapshot = contentBuf.toString()
                                post { callback.onDelta(snapshot) }
                            }
                            // 思考过程单独回调（带思考的模型才有）
                            val r = delta.optStringOrNull("reasoning_content")
                            if (!r.isNullOrEmpty()) {
                                thinkBuf.append(r)
                                val snapshot = thinkBuf.toString()
                                post { callback.onThinking(snapshot) }
                            }
                        } catch (_: Exception) {
                            // 单行解析失败不该中断整个流
                        }
                    }
                }

                // 收尾判定。三种情况：
                //   1. 有正文       -> 正常完成
                //   2. 没正文但有思考 -> 说明 token 全被思考吃掉了（或模型只回思考），
                //                       明确告诉用户，而不是丢一个空气泡
                //   3. 什么都没有     -> 接口可能不支持 stream
                val full = contentBuf.toString().trim()
                val think = thinkBuf.toString().trim()
                when {
                    full.isNotEmpty() -> {
                        if (finishReason == "length") {
                            post { callback.onComplete(full) }
                            post { callback.onError("回复被长度限制截断了，可在设置里关掉思考") }
                        } else {
                            post { callback.onComplete(full) }
                        }
                    }
                    think.isNotEmpty() -> post {
                        callback.onError("模型只输出了思考、没有正文（token 不够），试试关掉思考")
                    }
                    else -> post { callback.onError("接口没有返回内容（可能不支持 stream）") }
                }
            } catch (e: java.net.SocketTimeoutException) {
                post { callback.onError("请求超时，检查网络") }
            } catch (e: java.net.UnknownHostException) {
                post { callback.onError("连不上服务器，检查网络") }
            } catch (e: javax.net.ssl.SSLException) {
                post { callback.onError("HTTPS 握手失败，检查地址") }
            } catch (e: Exception) {
                post { callback.onError("请求失败：" + (e.message ?: e.javaClass.simpleName).take(16)) }
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    /**
     * 读取字符串字段，**能区分 JSON null 与空串**。
     *
     * 为什么不能直接用 `optString`：org.json 的 `optString(key)` 在 key 存在、
     * 但值是 JSON `null` 时会返回**字符串 "null"**（它只判断 key 在不在）。
     * 流式响应里正文帧就是 `{"content":null,...}`，于是每帧都追加一个
     * 字面量 "null"，用户看到的就是 `nullnullnull...`。这是 v1.2.2 的实际 bug。
     */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "")
    }

    /** 把 HTTP 错误码翻译成用户看得懂的中文短语（并尽量带上服务端原因） */
    private fun friendlyError(code: Int, body: String): String {
        val reason = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        val hint = when (code) {
            401, 403 -> "Key 无效或没权限"
            404 -> "地址或模型名不对"
            429 -> "请求太频繁，歇会儿"
            in 500..599 -> "服务端出错"
            else -> "HTTP $code"
        }
        return if (reason.isNotEmpty()) "$hint（${reason.take(20)}）" else hint
    }

    private fun post(block: () -> Unit) {
        main.post { runCatching { block() } }
    }
}
