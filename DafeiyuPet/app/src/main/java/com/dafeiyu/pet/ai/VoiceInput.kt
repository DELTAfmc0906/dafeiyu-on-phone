package com.dafeiyu.pet.ai

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 语音识别封装（v1.3.2 稳定性重做）。
 *
 * ## 只做一件事，但要做对：别再用网络识别
 *
 * 之前一直用 `SpeechRecognizer.createSpeechRecognizer()` —— 它默认走**云端**：
 * 音频要上传、识别结果要下载。这就是"连接很卡、经常断连"的根源，而且是
 * 架构性的，靠调参解决不了：
 *
 *   - 每次都要重新建立与服务端的连接
 *   - 网络一抖就 `ERROR_NETWORK` / `ERROR_SERVER`
 *   - 用户说一句话要等一个来回
 *
 * Android 12（API 31）起提供了**设备端识别**（`createOnDeviceSpeechRecognizer`）：
 * 音频不离开手机，没有网络环节，因此**不会断连、延迟也低得多**。
 *
 * ## 本类的 5 项优化
 *
 * 1. **优先设备端识别**：能离线就离线，彻底绕开网络
 * 2. **复用识别器实例**：不再每次按下都销毁重建（省掉重复的连接开销）
 * 3. **瞬时错误自动重试**：网络抖动 / 服务忙 时退避重试，而不是直接失败
 * 4. **静音超时调优**：默认值容易在用户思考时被判定"没听到声音"而断掉
 * 5. **可切回网络识别**：设备端识别在部分机型上中文效果一般，留开关
 *
 * ## 关于「按住说话」
 *
 * [SpeechRecognizer] 没有"只在按住期间录音"的接口。这里用组合模拟：
 * 按下 -> startListening；松开 -> stopListening。
 * 配合 `EXTRA_PARTIAL_RESULTS` 拿到实时中间结果，体感接近按住说话。
 */
class VoiceInput(private val context: Context) {

    /** 回调，全部回主线程 */
    interface Listener {
        /** 已准备好，可以开始说话 */
        fun onReady()
        /** 实时中间结果（边说边显示） */
        fun onPartial(text: String)
        /** 最终识别结果 */
        fun onResult(text: String)
        /** 出错，message 已是中文 */
        fun onError(message: String)
        /** 音量变化，用于画波形/提示 0..1 */
        fun onLevel(level: Float) {}
        /** 结束（无论成功失败），用于复位按钮状态 */
        fun onFinished() {}
        /**
         * 状态说明（诊断用）：告诉界面这次用的是离线还是在线、
         * 是不是在重试。用户反馈"卡/断连"时能看出原因。
         */
        fun onStatus(text: String) {}
    }

    /** 识别方式 */
    enum class Engine {
        /** 设备端离线识别。不联网、不断连、延迟低 —— 优先用 */
        ON_DEVICE,
        /** 云端识别。设备不支持离线或用户手动指定时用 */
        NETWORK
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    private var listening = false

    /** 当前识别器是哪种引擎，用于决定重试策略 */
    private var engine: Engine? = null

    /** 本次会话已重试次数 */
    private var retryCount = 0

    /** 是否要求优先离线（由设置项控制） */
    var preferOnDevice: Boolean = true

    /** 上一次实际使用的引擎，供界面显示 */
    var lastEngine: Engine? = null
        private set

    // =============================== 能力探测 ===============================

    /** 系统是否有可用的语音识别服务（在线或离线任一） */
    fun isAvailable(): Boolean =
        hasOnDevice() || SpeechRecognizer.isRecognitionAvailable(context)

    /** 是否有设备端离线识别（Android 12+ 且系统已装语言包） */
    fun hasOnDevice(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                    .getOrDefault(false)

    /** 是否在线识别可用 */
    fun hasNetwork(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    /**
     * 决定这次用哪个引擎。
     *
     * 优先离线：一来不受网络影响，二来延迟明显更低。
     * 只有离线不可用、或用户主动要求在线时才退回网络。
     */
    private fun pickEngine(): Engine? = when {
        preferOnDevice && hasOnDevice() -> Engine.ON_DEVICE
        hasNetwork() -> Engine.NETWORK
        hasOnDevice() -> Engine.ON_DEVICE   // 在线不可用但离线可用
        else -> null
    }

    // =============================== 启动 ===============================

    fun start(callback: Listener) {
        listener = callback
        val picked = pickEngine()
        if (picked == null) {
            main.post {
                callback.onError("这台设备没有可用的语音识别服务")
                callback.onFinished()
            }
            return
        }
        if (listening) return

        retryCount = 0
        main.post { startInternal(picked) }
    }

    /**
     * 真正启动。
     *
     * 关键优化：**复用识别器实例**。
     * 之前每次按下都 `releaseRecognizer()` + `createSpeechRecognizer()`，
     * 等于每次都要重新建立与识别服务的连接 —— 既慢又容易失败。
     * 现在只在引擎类型变化或首次使用时创建。
     */
    private fun startInternal(useEngine: Engine, isRetry: Boolean = false) {
        if (recognizer == null || engine != useEngine) {
            releaseRecognizer()
            recognizer = createRecognizer(useEngine)
            engine = useEngine
            lastEngine = useEngine
        }
        val r = recognizer ?: run {
            listener?.onError("语音识别初始化失败")
            listener?.onFinished()
            return
        }

        listener?.onStatus(
            when {
                isRetry -> "网络不稳，正在重试…"
                useEngine == Engine.ON_DEVICE -> "离线识别（不耗流量）"
                else -> "在线识别"
            }
        )

        runCatching { r.startListening(buildIntent()) }.onFailure {
            // startListening 本身失败（服务忙等），退避重试一次
            if (!isRetry) {
                main.postDelayed({ startInternal(useEngine, isRetry = true) }, 350L)
            } else {
                listener?.onError("启动语音识别失败")
                listener?.onFinished()
            }
        }
    }

    private fun createRecognizer(useEngine: Engine): SpeechRecognizer? = runCatching {
        if (useEngine == Engine.ON_DEVICE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }.getOrNull()?.also { attachListener(it) }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

            /*
             * 静音超时调优。
             *
             * 默认值偏短：用户说完一半停下来想一想，就会被判定"没听到声音"
             * 而提前结束 —— 体感上很像"断连"。
             * 这里给足思考时间，结束时机交给用户松手。
             */
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 60000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 60000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)

            // 设备端识别不支持语言偏好提示，设了也无害；在线识别用它提高准确率
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useEngineIsOffline())
        }

    private fun useEngineIsOffline(): Boolean = engine == Engine.ON_DEVICE

    // =============================== 回调 ===============================

    private fun attachListener(r: SpeechRecognizer) {
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                listener?.onReady()
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                // rmsdB 大约在 -2 ~ 10 之间，归一化到 0..1
                listener?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                listening = false
                if (shouldRetry(error)) {
                    retryCount++
                    // 退避重试：网络类错误多半是瞬时抖动，重试常常就好了
                    main.postDelayed({
                        listener?.onStatus("连接不稳，正在重试（第 $retryCount 次）…")
                        startInternal(engine ?: Engine.NETWORK, isRetry = true)
                    }, 300L * retryCount)
                    return
                }
                listener?.onError(errorText(error))
                listener?.onFinished()
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isBlank()) {
                    listener?.onError("没听清，再说一次？")
                } else {
                    listener?.onResult(text)
                }
                listener?.onFinished()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) listener?.onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    /**
     * 哪些错误值得重试。
     *
     * 只重试**瞬时性**问题：网络抖动、服务端临时抽风、识别器正忙。
     * 权限、没听清、无匹配这些重试也没意义，直接如实报给用户。
     * 最多重试 2 次，避免网络真断时无限转圈。
     */
    private fun shouldRetry(error: Int): Boolean {
        if (retryCount >= MAX_RETRY) return false
        return when (error) {
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> true
            else -> false
        }
    }

    // =============================== 停止 / 释放 ===============================

    /** 松开按钮时调用：结束收音并取回结果 */
    fun stop() {
        main.post {
            if (!listening) {
                // 还没真正开始听就松手了，直接收尾避免卡住
                listener?.onFinished()
                return@post
            }
            listening = false
            runCatching { recognizer?.stopListening() }
        }
    }

    /**
     * 彻底释放（界面关闭时务必调用），否则会漏麦克风。
     *
     * 注意与 [stop] 的区别：stop 只是结束本次收音，
     * 识别器实例**保留**以便下次复用（这正是减少连接开销的关键）。
     */
    fun release() {
        main.post {
            listening = false
            retryCount = 0
            releaseRecognizer()
            listener = null
        }
    }

    private fun releaseRecognizer() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        engine = null
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错了"
        SpeechRecognizer.ERROR_CLIENT -> "识别客户端出错"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误，识别失败（可在设置里改用离线识别）"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时（可在设置里改用离线识别）"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次？"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙，稍后再试"
        SpeechRecognizer.ERROR_SERVER -> "识别服务返回错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音"
        else -> "语音识别失败（$code）"
    }

    companion object {
        /** 瞬时错误最多重试几次 */
        private const val MAX_RETRY = 2
    }
}
