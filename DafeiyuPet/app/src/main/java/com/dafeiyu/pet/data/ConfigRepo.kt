package com.dafeiyu.pet.data

import android.content.Context

/** 行为模式。取值名与原 Python 版 config.json 保持一致（wander / follow / still）。 */
enum class PetMode(val key: String) {
    WANDER("wander"),
    FOLLOW("follow"),
    STILL("still");

    companion object {
        fun from(key: String?): PetMode =
            entries.firstOrNull { it.key == key } ?: WANDER
    }
}

/**
 * AI 接口预设。
 *
 * v1.2 新增。原 Python 版只支持 DeepSeek 一家，且把地址和模型写死在代码里
 * （`DS_BASE_URL = "https://api.deepseek.com/v1"`、`DS_MODEL = "deepseek-chat"`）。
 * Android 版改成可配置，既能一键用 DeepSeek，也能指向任意 OpenAI 兼容服务
 * （通义、智谱、Kimi、本地 ollama / vLLM 等都行）。
 */
enum class ApiPreset(
    val key: String,
    val label: String,
    val baseUrl: String,
    val model: String
) {
    DEEPSEEK(
        "deepseek",
        "DeepSeek",
        // 官方文档（2026-10）：OpenAI 兼容端点的 base_url 是 https://api.deepseek.com
        // 旧版文档写的是 .../v1，两者都接受，我们统一存带 /v1 的形式，
        // 由 AiClient 负责归一化（见 normalizeBaseUrl）。
        "https://api.deepseek.com/v1",
        // 注意：deepseek-chat / deepseek-reasoner 已于 2025-07-24 停用，
        // V4 时代是 deepseek-flash 与 deepseek-v4-pro。
        "deepseek-flash"
    ),
    CUSTOM(
        "custom",
        "自定义（OpenAI 格式）",
        "",
        ""
    );

    companion object {
        fun from(key: String?): ApiPreset =
            entries.firstOrNull { it.key == key } ?: DEEPSEEK
    }
}

/**
 * 角色大小：**占屏幕高度的百分比**，可自由调节。
 *
 * 演进历史（v1.1 起）：
 *   PC 版 340px 固定倍率（0.55/0.7/0.9）
 *   → v1.0 三档枚举（屏高 18% / 25% / 34%）
 *   → v1.1 改成 8%~50% 连续可调，并保留三档快捷值
 *
 * 之所以把下限压到 8%，是因为桌宠是"常驻悬浮"的，
 * 尺寸过大会挡到正常使用手机 —— 这是手机与 PC 最大的体感差异。
 */
object PetSize {

    /** 最小 8%：屏高 2400px 时约 192px，小到不太挡事但仍然看得清 */
    const val MIN_PERCENT = 8

    /** 最大 50%：再大就完全挡住半屏了 */
    const val MAX_PERCENT = 50

    /** 默认 20%：比 v1.0 的 25% 再小一档，日常使用更省地方 */
    const val DEFAULT_PERCENT = 20

    /** 三档快捷值 */
    const val SMALL_PERCENT = 10
    const val MEDIUM_PERCENT = 20
    const val LARGE_PERCENT = 34

    /** 滑杆步进：2% 一档，兼顾精细与好拖 */
    const val STEP = 2

    const val KEY_SMALL = "small"
    const val KEY_MEDIUM = "medium"
    const val KEY_LARGE = "large"

    /** 夹取到合法区间 */
    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** 把旧版本存的档位字符串迁到百分比 */
    fun migrateLegacy(key: String?): Int? = when (key) {
        KEY_SMALL -> SMALL_PERCENT
        KEY_MEDIUM -> MEDIUM_PERCENT
        KEY_LARGE -> LARGE_PERCENT
        else -> null
    }
}

/**
 * 配置存储。
 *
 * 替代 Python 版的 config.json。用 SharedPreferences 而非文件，
 * 避免多进程（服务 + Activity）同时写文件互相覆盖。
 *
 * 与原 config.json 的字段对应关系：
 *   mode        -> mode
 *   size        -> size（但语义从"倍率"变成"档位"）
 *   topmost     -> 移除（Android 悬浮窗天然置顶）
 *   passthrough -> interactive 取反
 *   autostart   -> autostart
 *   x / y       -> posX / posY
 *   ds_api_key  -> 移除（AI 对话功能已砍掉）
 *   city        -> city
 */
class ConfigRepo(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var mode: PetMode
        get() = PetMode.from(sp.getString(KEY_MODE, null))
        set(v) = sp.edit().putString(KEY_MODE, v.key).apply()

    /**
     * 角色大小，单位是"屏高百分比"的整数（8 ~ 50）。
     *
     * v1.1 从"三档枚举"改成百分比后，需要兼容两种历史数据：
     *   - 从未存过          -> 用默认值
     *   - 存的是旧档位字符串 -> 迁移成对应百分比
     *   - 存的是 Int        -> 直接用
     */
    var sizePercent: Int
        get() {
            val raw = sp.getInt(KEY_SIZE_PERCENT, Int.MIN_VALUE)
            if (raw != Int.MIN_VALUE) return PetSize.clamp(raw)
            // 兼容 v1.0 的字符串档位
            val legacy = sp.getString(KEY_SIZE, null)
            PetSize.migrateLegacy(legacy)?.let { migrated ->
                // 顺手写回新键，避免每次读都走迁移分支
                sp.edit().putInt(KEY_SIZE_PERCENT, migrated).apply()
                return PetSize.clamp(migrated)
            }
            return PetSize.DEFAULT_PERCENT
        }
        set(v) = sp.edit().putInt(KEY_SIZE_PERCENT, PetSize.clamp(v)).apply()

    /** 是否可互动。对应原程序 passthrough 的反面。 */
    var interactive: Boolean
        get() = sp.getBoolean(KEY_INTERACTIVE, true)
        set(v) = sp.edit().putBoolean(KEY_INTERACTIVE, v).apply()

    /**
     * 强制可互动（v1.2.1 新增）。
     *
     * 背景：v1.1 为了避免"鱼太大挡住设置面板控件"，让鱼在 app 打开期间触摸穿透。
     * 副作用是——只要 app 在前台，鱼就一直"看得见但摸不到"，
     * 用户会以为它卡死了（实测确实能持续 50 秒以上）。
     *
     * 打开这个开关后，鱼在 app 打开期间也保持可互动。
     * 代价是：如果鱼正好压在某个设置控件上，会挡到那个控件
     * （把鱼拖开即可；或从通知栏临时切「摸不到它」）。
     */
    var forceInteractive: Boolean
        get() = sp.getBoolean(KEY_FORCE_INTERACTIVE, false)
        set(v) = sp.edit().putBoolean(KEY_FORCE_INTERACTIVE, v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, false)
        set(v) = sp.edit().putBoolean(KEY_AUTOSTART, v).apply()

    var batteryAlert: Boolean
        get() = sp.getBoolean(KEY_BATTERY, true)
        set(v) = sp.edit().putBoolean(KEY_BATTERY, v).apply()

    // =============================== AI（v1.2 新增）===============================

    var apiPreset: ApiPreset
        get() = ApiPreset.from(sp.getString(KEY_API_PRESET, null))
        set(v) = sp.edit().putString(KEY_API_PRESET, v.key).apply()

    /** API Key。DeepSeek 的是 sk- 开头 */
    var apiKey: String
        get() = sp.getString(KEY_API_KEY, "") ?: ""
        set(v) = sp.edit().putString(KEY_API_KEY, v.trim()).apply()

    /**
     * 实际的 base url。
     * 选 DeepSeek 预设时用预设值（忽略手填），选自定义时用用户填的。
     */
    var apiBaseUrl: String
        get() {
            val custom = sp.getString(KEY_API_BASE_URL, "") ?: ""
            return if (apiPreset == ApiPreset.DEEPSEEK) ApiPreset.DEEPSEEK.baseUrl
            else custom.ifBlank { ApiPreset.DEEPSEEK.baseUrl }
        }
        set(v) = sp.edit().putString(KEY_API_BASE_URL, v.trim()).apply()

    /** 实际的模型名，逻辑同上 */
    var apiModel: String
        get() {
            val custom = sp.getString(KEY_API_MODEL, "") ?: ""
            return if (apiPreset == ApiPreset.DEEPSEEK) ApiPreset.DEEPSEEK.model
            else custom.ifBlank { ApiPreset.DEEPSEEK.model }
        }
        set(v) = sp.edit().putString(KEY_API_MODEL, v.trim()).apply()

    /** 是否已经引导过用户填 Key（避免每次长按都弹） */
    var apiKeyPrompted: Boolean
        get() = sp.getBoolean(KEY_API_PROMPTED, false)
        set(v) = sp.edit().putBoolean(KEY_API_PROMPTED, v).apply()

    /** 对话历史（JSON 数组字符串，由 AiChatStore 负责序列化） */
    var chatHistoryJson: String
        get() = sp.getString(KEY_CHAT_HISTORY, "[]") ?: "[]"
        set(v) = sp.edit().putString(KEY_CHAT_HISTORY, v).apply()

    /** 是否启用 AI 功能（长按唤醒）。关掉后长按不再弹对话 */
    var aiEnabled: Boolean
        get() = sp.getBoolean(KEY_AI_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_AI_ENABLED, v).apply()

    /**
     * 是否开启模型的思考（思维链）。
     *
     * 默认**关闭**，原因（实测数据）：
     *   - 开：一次五个字的请求产生 81KB 响应，思考烧掉大量 token，
     *         200 token 上限能让正文完全为空
     *   - 关：同样请求只有 1.5KB，响应快、便宜
     *
     * 喜欢看鱼"思考"的过程可以打开 —— 思考内容会以灰色斜体气泡显示，
     * 正好呼应原 Python 版的「思维链心声」。
     */
    var aiThinking: Boolean
        get() = sp.getBoolean(KEY_AI_THINKING, false)
        set(v) = sp.edit().putBoolean(KEY_AI_THINKING, v).apply()

    /** 语音识别：识别完是否自动发送（false 则只填进输入框） */
    var voiceAutoSend: Boolean
        get() = sp.getBoolean(KEY_VOICE_AUTO_SEND, true)
        set(v) = sp.edit().putBoolean(KEY_VOICE_AUTO_SEND, v).apply()

    /** 配置是否可用（有 Key 才算配好） */
    fun isAiConfigured(): Boolean = apiKey.isNotBlank()

    var city: String
        get() = sp.getString(KEY_CITY, DEFAULT_CITY) ?: DEFAULT_CITY
        set(v) = sp.edit().putString(KEY_CITY, v).apply()

    /** 鱼在屏幕上的位置。null 表示还没放过，由服务决定初始位置（右下角）。 */
    var posX: Int?
        get() = sp.getInt(KEY_X, -1).takeIf { it >= 0 }
        set(v) = sp.edit().putInt(KEY_X, v ?: -1).apply()

    var posY: Int?
        get() = sp.getInt(KEY_Y, -1).takeIf { it >= 0 }
        set(v) = sp.edit().putInt(KEY_Y, v ?: -1).apply()

    fun savePosition(x: Int, y: Int) {
        sp.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    /** 是否已经提示过悬浮窗权限，避免每次启动都弹 */
    var overlayHinted: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_HINTED, false)
        set(v) = sp.edit().putBoolean(KEY_OVERLAY_HINTED, v).apply()

    private companion object {
        const val PREFS = "dafeiyu_config"
        const val KEY_MODE = "mode"
        /** v1.1 起：Int 百分比 */
        const val KEY_SIZE_PERCENT = "size_percent"
        /** v1.0 的字符串档位键，只用于迁移读取 */
        const val KEY_SIZE = "size"
        const val KEY_INTERACTIVE = "interactive"
        const val KEY_FORCE_INTERACTIVE = "force_interactive"
        const val KEY_AUTOSTART = "autostart"
        const val KEY_BATTERY = "battery_alert"
        const val KEY_CITY = "city"
        const val KEY_X = "pos_x"
        const val KEY_Y = "pos_y"
        const val KEY_OVERLAY_HINTED = "overlay_hinted"
        // v1.2 AI
        const val KEY_API_PRESET = "api_preset"
        const val KEY_API_KEY = "api_key"
        const val KEY_API_BASE_URL = "api_base_url"
        const val KEY_API_MODEL = "api_model"
        const val KEY_API_PROMPTED = "api_key_prompted"
        const val KEY_CHAT_HISTORY = "chat_history"
        const val KEY_AI_ENABLED = "ai_enabled"
        const val KEY_AI_THINKING = "ai_thinking"
        const val KEY_VOICE_AUTO_SEND = "voice_auto_send"
        const val DEFAULT_CITY = "汕头"
    }
}
