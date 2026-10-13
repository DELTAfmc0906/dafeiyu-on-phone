package com.dafeiyu.pet.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.dafeiyu.pet.data.Lines
import com.dafeiyu.pet.data.PetMode
import com.dafeiyu.pet.data.PetSize
import com.dafeiyu.pet.pet.Facing
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * 桌宠行为引擎。
 *
 * 严格对照 Python 版 `桌宠.py` 的以下部分移植：
 *   - `tick()`                 主循环状态机（三种模式的移动、惯性、休息）
 *   - `_maybe_idle_action()`   空闲时的小动作与自言自语
 *   - `_sprite_key()` / `_set_dir()`  三视图切换与转向交叉淡化
 *   - `check_system_status()`  系统状态提醒（PC 的 CPU/内存/GPU -> 手机的电池）
 *
 * ## 坐标约定（重要）
 * 引擎坐标 **(x, y) 表示"角色本体"左上角的绝对屏幕坐标**，不含气泡区域。
 * 悬浮窗比本体高出一个气泡预留高度 [bubbleReserve]，因此：
 *   窗口 y = engine.y - bubbleReserve
 * 这样引擎只需要关心角色本身，气泡区域由绘制层自由使用。
 *
 * 时间基准与原程序一致，[TICK_MS] = 20ms。
 */
class PetEngine(context: Context, private val density: Float = 2f) {

    interface Listener {
        /** 本体位置变化，外部据此更新窗口布局 */
        fun onMove(x: Int, y: Int)
        /** 需要重绘 */
        fun onInvalidate()
        /** 弹气泡。inner = true 表示思维链心声（灰色斜体） */
        fun onSay(text: String, inner: Boolean)
    }

    var listener: Listener? = null

    // =============================== 常量（对照原程序）===============================

    companion object {
        const val TICK_MS = 20L
        /** 原程序 SPEED = 380 px/s；手机上按屏高比例缩放才跟手 */
        private const val SPEED_SCREEN_RATIO = 0.20f
        private const val ARRIVE_EPSILON = 12f
        private const val REST_MIN_MS = 8_000L
        private const val REST_MAX_MS = 18_000L
        private const val DRAG_REST_MIN_MS = 6_000L
        private const val DRAG_REST_MAX_MS = 14_000L
        private const val IDLE_ACTION_CHANCE = 0.01f
        private const val TARGET_JUMP_CHANCE = 0.002f
        private const val INNER_SPEAK_GAP_TICKS = 1500L
        private const val BUBBLE_DURATION_MS = 2800L
        private const val BATTERY_LOW_PERCENT = 15
        private const val BATTERY_REWARN_MS = 5 * 60_000L
        /** 角色最小像素高度：屏高 8% 在 1080x2400 上约 192px，这里兜住极低分辨率设备 */
        private const val MIN_PET_PX = 96
    }

    enum class ActionKind { NONE, SWAY, STRETCH }

    // =============================== 屏幕 ===============================

    var screenW = 1080; private set
    var screenH = 1920; private set
    private var insetTop = 0
    private var insetBottom = 0

    // =============================== 位置与尺寸 ===============================

    /** 角色本体左上角（绝对屏幕坐标） */
    var x = 0f; private set
    var y = 0f; private set

    /** 角色本体显示尺寸（像素） */
    var petWidth = 200; private set
    var petHeight = 400; private set

    /** 气泡预留高度：窗口比本体高出这么多 */
    var bubbleReserve = 120; private set

    /** 悬浮窗总高度 = 本体 + 气泡预留 */
    val windowHeight: Int get() = petHeight + bubbleReserve

    // =============================== 视图状态 ===============================

    var facing = Facing.DOWN; private set
    var prevFacing: Facing? = null; private set

    /** 1.0 -> 0.0，用于转向交叉淡化 */
    var crossT = 0f; private set

    // =============================== 动画状态（绘制层读取）===============================

    /** 主时间轴（秒）。原程序所有动画都基于这一个 t 乘不同频率。 */
    var clock = 0f; private set
    var jumpT = 0f; private set
    var eatT = 0f; private set
    var actionKind = ActionKind.NONE; private set
    var actionT = 0f; private set
    var walking = false; private set

    // =============================== 气泡 ===============================

    var bubbleText = ""; private set
    var bubbleInner = false; private set
    private var bubbleUntilMs = 0L
    private var lastLine = ""

    // =============================== 可变配置 ===============================

    var mode: PetMode = PetMode.WANDER
        set(v) { field = v; targetX = null; targetY = null }

    /**
     * 角色大小：屏高百分比（8~50）。
     * 改小之后一定要重新 `clampIntoBounds()`，否则鱼会被留在屏幕外。
     */
    var sizePercent: Int = PetSize.DEFAULT_PERCENT
        set(v) {
            field = PetSize.clamp(v)
            applySize()
            clampIntoBounds()
        }

    // =============================== 内部状态 ===============================

    private var targetX: Float? = null
    private var targetY: Float? = null
    private var restUntilMs = 0L
    private var curSpeed = 0f
    private var tickCount = 0L
    private var lastSpeakTick = 0L
    private var dragging = false
    private var fingerX = 0f
    private var fingerY = 0f

    private var running = false
    private val handler = Handler(Looper.getMainLooper())
    private val rand = Random(System.currentTimeMillis())

    // 电池监控（替代 PC 版的 CPU / 内存 / GPU 温度监控）
    private var batteryLow = false
    private var lastBatteryWarnMs = 0L
    private var batteryEnabled = true

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        refreshScreenMetrics(context)
    }
    // =============================== 生命周期 ===============================

    fun start() {
        if (running) return
        running = true
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tickRunnable)
    }

    /**
     * 重算屏幕尺寸、安全区与速度。旋转屏幕或切换尺寸后都要调。
     * @param insetTop     状态栏等顶部安全区
     * @param insetBottom  导航栏等底部安全区
     */
    fun refreshScreenMetrics(context: Context, insetTop: Int = 0, insetBottom: Int = 0) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        this.insetTop = insetTop
        this.insetBottom = insetBottom
        applySize()
        clampIntoBounds()
    }

    fun setBatteryAlertEnabled(enabled: Boolean) {
        batteryEnabled = enabled
    }

    /** 服务收到电量广播时调用 */
    fun onBatteryChanged(levelPercent: Int, charging: Boolean) {
        if (!batteryEnabled) return
        val now = System.currentTimeMillis()
        when {
            charging && batteryLow -> {
                batteryLow = false
                say(Lines.BATTERY_OK)
            }
            !charging && levelPercent <= BATTERY_LOW_PERCENT -> {
                if (!batteryLow) {
                    batteryLow = true
                    lastBatteryWarnMs = now
                    say(Lines.BATTERY_LOW)
                } else if (now - lastBatteryWarnMs > BATTERY_REWARN_MS) {
                    lastBatteryWarnMs = now
                    say(Lines.BATTERY_LOW)
                }
            }
        }
        if (levelPercent > 20) batteryLow = false
    }

    // =============================== 尺寸 ===============================

    private fun applySize() {
        petHeight = (screenH * sizePercent / 100f).toInt().coerceAtLeast(MIN_PET_PX)
        // 本体高度变了，气泡预留也要跟着变
        bubbleReserve = (petHeight * 0.34f).toInt().coerceAtLeast(dp(44))
        if (petWidth <= 0) petWidth = petHeight
    }

    /** 外部（服务）在换图后告知本体的实际像素宽度，用于精确夹取边界 */
    fun setPetWidth(w: Int) {
        if (w > 0) petWidth = w
        clampIntoBounds()
    }

    // =============================== 主循环 ===============================

    private fun tick() {
        tickCount++
        val nowMs = tickCount * TICK_MS
        clock += TICK_MS / 1000f

        if (jumpT > 0f) jumpT = (jumpT - 0.06f).coerceAtLeast(0f)
        if (eatT > 0f) eatT = (eatT - 0.03f).coerceAtLeast(0f)
        if (crossT > 0f) crossT = (crossT - 0.15f).coerceAtLeast(0f)
        if (actionT > 0f) {
            actionT = (actionT - 0.03f).coerceAtLeast(0f)
            if (actionT == 0f) actionKind = ActionKind.NONE
        }
        if (bubbleText.isNotEmpty() && nowMs > bubbleUntilMs) bubbleText = ""

        if (dragging) {
            walking = false
            listener?.onInvalidate()
            return
        }

        when (mode) {
            PetMode.FOLLOW -> updateFollowTarget()
            PetMode.WANDER -> if (targetX == null) {
                if (nowMs < restUntilMs) {
                    maybeIdleAction()
                    walking = false
                    listener?.onInvalidate()
                    return
                }
                pickRandomTarget()
            }
            PetMode.STILL -> {
                maybeIdleAction()
                walking = false
                listener?.onInvalidate()
                return
            }
        }

        val tx = targetX
        val ty = targetY
        if (tx != null && ty != null) {
            val cx = x + petWidth / 2f
            val cy = y + petHeight / 2f
            val dx = tx - cx
            val dy = ty - cy
            val dist = hypot(dx, dy)

            if (dist < ARRIVE_EPSILON) {
                targetX = null; targetY = null
                restUntilMs = nowMs + rand.nextLong(REST_MIN_MS, REST_MAX_MS)
                setFacing(Facing.DOWN)
                curSpeed = 0f
            } else {
                val step = curSpeed * TICK_MS / 1000f
                x = cx + dx / dist * step - petWidth / 2f
                y = cy + dy / dist * step - petHeight / 2f
                clampIntoBounds()
                if (abs(dx) > abs(dy) * 1.15f) {
                    setFacing(if (dx < 0) Facing.LEFT else Facing.RIGHT)
                } else {
                    setFacing(if (dy < 0) Facing.UP else Facing.DOWN)
                }
                listener?.onMove(x.toInt(), y.toInt())
            }

            if (rand.nextFloat() < TARGET_JUMP_CHANCE && jumpT == 0f) jumpT = 0.5f

            val targetSpeed = speedPxPerSec()
            curSpeed += (targetSpeed - curSpeed) * 0.3f
            walking = true
        } else {
            curSpeed += (0f - curSpeed) * 0.3f
            walking = false
        }

        listener?.onInvalidate()
    }

    private fun speedPxPerSec(): Float = screenH * SPEED_SCREEN_RATIO

    // ---------- 跟随手指模式 ----------

    private fun updateFollowTarget() {
        // 与原程序"鼠标靠近就不追"同义：手指离得近就停下
        val near = abs(fingerX - x) <= petWidth + dp(100) &&
                abs(fingerY - y) <= petHeight + dp(100)
        if (near) {
            targetX = null; targetY = null
        } else {
            targetX = (fingerX - petWidth / 2f).coerceIn(0f, (screenW - petWidth).toFloat())
            // 原程序是 cursor.y - 90；这里按本体高度比例，让鱼"站"在手指上方
            targetY = (fingerY - petHeight * 0.55f)
                .coerceIn(0f, (screenH - petHeight).toFloat())
        }
    }

    /** 服务在触摸时告知手指位置（对应 PC 版的鼠标位置） */
    fun updateFinger(px: Float, py: Float) {
        fingerX = px
        fingerY = py
    }

    private fun pickRandomTarget() {
        val loX = dp(40).toFloat()
        val hiX = (screenW - petWidth - dp(40)).toFloat().coerceAtLeast(loX + 1f)
        val loY = (insetTop + dp(40)).toFloat()
        val hiY = (screenH - insetBottom - petHeight - dp(40)).toFloat().coerceAtLeast(loY + 1f)
        targetX = rand.nextDouble(loX.toDouble(), hiX.toDouble()).toFloat()
        targetY = rand.nextDouble(loY.toDouble(), hiY.toDouble()).toFloat()
    }

    // ---------- 空闲小动作（对照 _maybe_idle_action）----------

    private fun maybeIdleAction() {
        if (rand.nextFloat() >= IDLE_ACTION_CHANCE) return
        val pick = rand.nextFloat()
        when {
            pick < 0.35f -> jumpT = 1.0f
            pick < 0.60f -> { actionKind = ActionKind.SWAY; actionT = 1.0f }
            pick < 0.80f -> { actionKind = ActionKind.STRETCH; actionT = 1.0f }
            pick < 0.90f -> if (tickCount - lastSpeakTick >= INNER_SPEAK_GAP_TICKS) {
                lastSpeakTick = tickCount
                if (pick < 0.82f) say(Lines.INNER.random(rand), inner = true)
                else say(Lines.RANDOM.random(rand))
            }
        }
    }

    // =============================== 朝向 ===============================

    private fun setFacing(f: Facing) {
        if (f != facing) {
            prevFacing = facing
            crossT = 1.0f
            facing = f
        }
    }

    /** 拖拽时侧身朝向拖动方向 */
    fun faceWhileDragging(dx: Float) {
        if (abs(dx) > dp(10)) setFacing(if (dx < 0) Facing.LEFT else Facing.RIGHT)
    }

    // =============================== 气泡 ===============================

    fun say(text: String, inner: Boolean = false) {
        // 原程序逻辑：同一句话不重复说（天气除外）
        if (text == lastLine && !text.startsWith("天气")) return
        lastLine = text
        bubbleInner = inner
        bubbleText = if (inner) "（$text）" else text
        bubbleUntilMs = tickCount * TICK_MS + BUBBLE_DURATION_MS
        listener?.onSay(bubbleText, inner)
    }

    // =============================== 交互回调 ===============================

    fun onDragStart() {
        dragging = true
        targetX = null; targetY = null
        curSpeed = 0f
    }

    fun onDragMove(newX: Float, newY: Float) {
        x = newX; y = newY
        clampIntoBounds()
        listener?.onMove(x.toInt(), y.toInt())
        listener?.onInvalidate()
    }

    fun onDragEnd() {
        dragging = false
        facing = Facing.DOWN
        crossT = 0f
        prevFacing = null
        targetX = null; targetY = null
        curSpeed = 0f
        restUntilMs = tickCount * TICK_MS + rand.nextLong(DRAG_REST_MIN_MS, DRAG_REST_MAX_MS)
        if (rand.nextFloat() < 0.5f) say(Lines.DRAG.random(rand))
    }

    /** 单击：蹦跳 + 回嘴（原程序还会弹功能面板，Android 上改为长按出菜单） */
    fun onClick() {
        if (rand.nextFloat() < 0.7f) jumpT = 1.0f
        if (rand.nextFloat() < 0.6f) say(Lines.REACT.random(rand))
    }

    /** 双击：喂食 */
    fun onFeed(food: String) {
        eatT = 1.0f
        jumpT = 0.6f
        say((Lines.FOOD[food] ?: listOf("好吃！")).random(rand))
    }

    fun onCitySet(city: String) = say(Lines.citySet(city))

    fun sayWeather(city: String, temp: String, desc: String) =
        say(Lines.weatherLine(city, temp, desc))

    // =============================== 位置工具 ===============================

    fun clampIntoBounds() {
        val maxX = (screenW - petWidth).coerceAtLeast(0)
        // 上边界留出气泡位置；下边界避开导航栏
        val minY = (insetTop + bubbleReserve).coerceAtLeast(0)
        val maxY = (screenH - insetBottom - petHeight).coerceAtLeast(minY)
        x = x.coerceIn(0f, maxX.toFloat())
        y = y.coerceIn(minY.toFloat(), maxY.toFloat())
    }

    /** 初始位置：右下角，与原程序一致 */
    fun placeAtDefault() {
        x = (screenW - petWidth - dp(24)).toFloat().coerceAtLeast(0f)
        y = (screenH - insetBottom - petHeight - dp(80)).toFloat().coerceAtLeast(0f)
        clampIntoBounds()
    }

    fun setPosition(px: Int, py: Int) {
        x = px.toFloat(); y = py.toFloat()
        clampIntoBounds()
    }

    private fun dp(v: Int): Int = (v * density).toInt()
}
