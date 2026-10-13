package com.dafeiyu.pet.ai

import com.dafeiyu.pet.data.ConfigRepo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 对话历史的持久化。
 *
 * 原 Python 版只把历史放在内存里（`self.chat_history`），退出即丢。
 * Android 上顺手存进 SharedPreferences，重开应用还能接着聊。
 * 条数上限沿用原程序的 40 条。
 */
class ChatHistoryStore(private val config: ConfigRepo) {

    private val messages = ArrayList<ChatMessage>()

    init {
        load()
    }

    private fun load() {
        runCatching {
            val arr = JSONArray(config.chatHistoryJson)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val role = o.optString("role")
                val content = o.optString("content")
                if (role.isNotEmpty() && content.isNotEmpty()) {
                    messages.add(ChatMessage(role, content))
                }
            }
        }
    }

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            messages.forEach {
                arr.put(JSONObject().put("role", it.role).put("content", it.content))
            }
            config.chatHistoryJson = arr.toString()
        }
    }

    /** 当前全部历史（不含 system） */
    fun all(): List<ChatMessage> = messages.toList()

    /** 参与请求的最近若干条 */
    fun forRequest(): List<ChatMessage> = messages.takeLast(AiClient.MAX_HISTORY)

    fun addUser(text: String) {
        messages.add(ChatMessage(ChatMessage.ROLE_USER, text))
        trim()
        persist()
    }

    fun addAssistant(text: String) {
        messages.add(ChatMessage(ChatMessage.ROLE_ASSISTANT, text))
        trim()
        persist()
    }

    fun clear() {
        messages.clear()
        persist()
    }

    fun size(): Int = messages.size

    private fun trim() {
        while (messages.size > AiClient.MAX_HISTORY) {
            messages.removeAt(0)
        }
    }
}
