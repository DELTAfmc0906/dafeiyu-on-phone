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
 * 语音识别封装（v1.2.3 新增）。
 *
 * 用系统自带的 [SpeechRecognizer]，**不引入任何第三方语音 SDK**：
 *   - 免费、无需额外 API Key
 *   - 小米 / 华为 / OPPO 等国产 ROM 通常内置识别引擎
 *   - 谷歌服务缺失时可能不可用，所以 [isAvailable] 要做降级判断
 *
 * 关于「按住说话」：`SpeechRecognizer` 本身是"启动后持续听、自动断句"的模式，
 * 没有真正的"按键期间才录音"接口。这里用组合方式模拟：
 *   按下 -> startListening
 *   松开 -> stopListening（让它立刻结束并回调已识别内容）
 * 配合 `EXTRA_PARTIAL_RESULTS` 能拿到实时中间结果，体感接近按住说话。
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
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    private var listening = false

    /** 系统是否有可用的语音识别服务 */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** 设备是否支持中文识别（大致判断，仅用于提示） */
    fun isOnDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun start(callback: Listener) {
        listener = callback
        if (!isAvailable()) {
            main.post {
                callback.onError("这台设备没有可用的语音识别服务")
                callback.onFinished()
            }
            return
        }
        if (listening) return

        main.post {
            releaseRecognizer()
            val r = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = r
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

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // 关闭"说完自动停"，让我们自己控制结束时机（配合按住说话）
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 60000L)
            }
            runCatching { r.startListening(intent) }.onFailure {
                listener?.onError("启动语音识别失败")
                listener?.onFinished()
            }
        }
    }

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

    /** 彻底释放（界面关闭时务必调用，否则会漏麦克风） */
    fun release() {
        main.post {
            listening = false
            releaseRecognizer()
            listener = null
        }
    }

    private fun releaseRecognizer() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错了"
        SpeechRecognizer.ERROR_CLIENT -> "识别客户端出错"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误，识别失败"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次？"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙，稍后再试"
        SpeechRecognizer.ERROR_SERVER -> "识别服务返回错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音"
        else -> "语音识别失败（$code）"
    }
}
