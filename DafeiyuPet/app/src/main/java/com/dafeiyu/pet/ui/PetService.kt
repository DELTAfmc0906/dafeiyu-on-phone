package com.dafeiyu.pet.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.dafeiyu.pet.R
import com.dafeiyu.pet.ai.AiClient
import com.dafeiyu.pet.ai.ChatHistoryStore
import com.dafeiyu.pet.data.ConfigRepo
import com.dafeiyu.pet.data.PetMode
import com.dafeiyu.pet.data.PetSize
import com.dafeiyu.pet.engine.PetEngine
import com.dafeiyu.pet.pet.Facing
import com.dafeiyu.pet.pet.PetView
import com.dafeiyu.pet.pet.SpriteLoader
import com.dafeiyu.pet.weather.WeatherClient
import kotlin.math.max

/**
 * 桌宠前台服务：悬浮窗的宿主。
 *
 * 职责：
 *   1. 用 WindowManager 挂一个透明、不可聚焦的覆盖层，里面放 [PetView]
 *   2. 用 [Choreographer] 每帧把引擎状态同步到视图并重绘
 *   3. 前台通知常驻，并提供"设置 / 切换可互动 / 收工"三个动作
 *   4. 监听电量广播，转交引擎做低电量提醒
 *   5. 监听屏幕旋转，重算边界
 */
class PetService : Service(), PetEngine.Listener, PetView.GestureHost {

    private lateinit var config: ConfigRepo
    private lateinit var windowManager: WindowManager
    private lateinit var engine: PetEngine
    private lateinit var sprites: SpriteLoader
    private var petView: PetView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var windowW = 0
    private var windowH = 0

    private val uiHandler = Handler(Looper.getMainLooper())
    private var frameScheduled = false

    private var batteryReceiver: BroadcastReceiver? = null

    // ---- v1.2 AI ----
    private lateinit var chatHistory: ChatHistoryStore
    private var chatPanel: AiChatPanel? = null
    /** 正在等 AI 回复 */
    private var aiBusy = false

    /**
     * 每帧：把引擎状态同步到视图 -> 更新窗口位置 -> 重绘 -> 排下一帧。
     * 用固定 20ms（对齐引擎 TICK）的 Handler 而不是 Choreographer，
     * 因为窗口位移本来就是按 20ms 步进算的，两者对齐才不会抖。
     */
    private val frameRunnable = object : Runnable {
        override fun run() {
            frameScheduled = false
            val v = petView ?: return
            v.syncFromEngine()
            layoutWindow()
            v.invalidate()
            scheduleFrame()
        }
    }

    // =============================== 生命周期 ===============================

    override fun onCreate() {
        super.onCreate()
        PetRuntime.isRunning = true
        config = ConfigRepo(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        engine = PetEngine(this, density)
        engine.listener = this
        engine.mode = config.mode
        engine.sizePercent = config.sizePercent
        engine.setBatteryAlertEnabled(config.batteryAlert)
        sprites = SpriteLoader(this)
        chatHistory = ChatHistoryStore(config)

        startForegroundCompat()
        registerBatteryReceiver()

        // 首次启动先把尺寸算出来，再决定窗口大小
        engine.refreshScreenMetrics(this, 0, bottomInset())
        engine.placeAtDefault()
        createOverlay()

        engine.start()
        scheduleFrame()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_TOUCH -> {
                // 只有"用户主动切换"才允许改 config.interactive。
                // 设置面板让路走的是 PetRuntime.settingsVisible，不碰这个值。
                config.interactive = !config.interactive
                applyInteractive()
                refreshNotification()
            }
            ACTION_SET_TOUCHABLE -> {
                // 设置"用户意图"。会把值写进配置 —— 只有真正代表用户选择时才用。
                val touchable = intent.getBooleanExtra(EXTRA_TOUCHABLE, true)
                config.interactive = touchable
                applyInteractive()
                refreshNotification()
            }
            ACTION_REFRESH_TOUCHABLE -> {
                // 只让服务按当前状态重算一次触摸/焦点 flags，**不修改任何配置**。
                //
                // 这是给设置面板用的：面板进出时只需要触发一次重算
                // （因为 PetRuntime.settingsVisible 变了），
                // 绝不能用 ACTION_SET_TOUCHABLE —— 那会把 config.interactive
                // 覆盖成面板自己以为的值，从而抹掉用户刚做的选择。
                applyInteractive()
            }
            ACTION_RELOAD_CONFIG -> {
                engine.mode = config.mode
                engine.sizePercent = config.sizePercent
                engine.setBatteryAlertEnabled(config.batteryAlert)
                // AI 配置可能改了，重新载入历史
                chatHistory = ChatHistoryStore(config)
                config.posX?.let { px -> config.posY?.let { py -> engine.setPosition(px, py) } }
                applyInteractive()
                remeasureWindow()
                refreshNotification()
            }
            ACTION_WEATHER -> {
                val city = intent.getStringExtra(EXTRA_CITY) ?: config.city
                WeatherClient.fetch(city) { result ->
                    result.onSuccess { w ->
                        engine.sayWeather(city, w.temp, w.desc)
                    }.onFailure {
                        engine.say(com.dafeiyu.pet.data.Lines.WEATHER_FAIL)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        PetRuntime.isRunning = false
        chatPanel?.hide()
        chatPanel = null
        uiHandler.removeCallbacks(frameRunnable)
        engine.stop()
        unregisterBatteryReceiver()
        petView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        petView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =============================== 悬浮窗 ===============================

    private fun createOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val view = PetView(this, engine, sprites).apply {
            gestureHost = this@PetService
            applyTextSize(engine.bubbleReserve * 0.26f)
        }
        petView = view

        remeasureWindow()

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        // 不可互动时让触摸穿透到下面的应用（等价于 PC 版的"鼠标穿透"）。
        // 另外：如果设置面板正开在前台，也强制穿透 —— 否则用户先开面板、
        // 再启动桌宠时，鱼一出来就盖住了面板控件，连调小都点不到。
        if (!shouldBeTouchable()) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        val lp = WindowManager.LayoutParams(
            windowW,
            windowH,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = engine.x.toInt() - (windowW - engine.petWidth) / 2
            y = engine.y.toInt() - engine.bubbleReserve
        }
        layoutParams = lp
        windowManager.addView(view, lp)
    }

    /** 依据三种视图里最宽的一张来确定窗口宽度，这样转向时不用重建窗口 */
    private fun remeasureWindow() {
        val h = engine.petHeight
        var maxW = h
        for (f in listOf(Facing.DOWN, Facing.UP, Facing.LEFT)) {
            val b = sprites.sprite(f, h) ?: continue
            maxW = max(maxW, b.width)
        }
        windowW = maxW
        windowH = engine.windowHeight
        petView?.applyTextSize(engine.bubbleReserve * 0.26f)
        layoutParams?.let { lp ->
            lp.width = windowW
            lp.height = windowH
            runCatching { windowManager.updateViewLayout(petView, lp) }
        }
    }

    /** 把引擎的本体坐标换算成窗口坐标并落到 WindowManager */
    private fun layoutWindow() {
        val lp = layoutParams ?: return
        val v = petView ?: return
        val nx = engine.x.toInt() - (windowW - engine.petWidth) / 2
        val ny = engine.y.toInt() - engine.bubbleReserve
        if (lp.x != nx || lp.y != ny) {
            lp.x = nx
            lp.y = ny
            runCatching { windowManager.updateViewLayout(v, lp) }
        }
    }

    /**
     * 统一计算"现在鱼是否应该接收触摸"。
     *
     * 两种情况都要求穿透：
     *   1. 用户把「可互动」关掉了（等价 PC 版的鼠标穿透）
     *   2. 设置面板正开在前台 —— 否则鱼一大就盖住面板控件，想调小都点不到
     */
    private fun shouldBeTouchable(): Boolean =
        config.interactive && !PetRuntime.settingsVisible

    private fun applyInteractive() {
        val lp = layoutParams ?: return
        val v = petView ?: return

        // 触摸穿透
        lp.flags = if (shouldBeTouchable()) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        // 焦点：AI 对话面板打开时不能有 NOT_FOCUSABLE，否则输入法和文本焦点会被鱼抢走
        lp.flags = if (chatPanel?.isShowing == true) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { windowManager.updateViewLayout(v, lp) }
    }

    /** 每帧驱动。固定 20ms，与引擎 TICK 对齐。 */
    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        uiHandler.postDelayed(frameRunnable, PetEngine.TICK_MS)
    }

    // =============================== 引擎回调 ===============================

    override fun onMove(x: Int, y: Int) {
        // 位置变化统一在每帧的 layoutWindow() 里处理，这里无需额外动作
    }

    override fun onInvalidate() {
        // 每帧统一重绘
    }

    override fun onSay(text: String, inner: Boolean) {
        petView?.invalidate()
    }

    // =============================== 手势回调 ===============================

    override fun onDragMove(bodyX: Float, bodyY: Float) {
        layoutWindow()
        petView?.invalidate()
    }

    override fun onInteractionEnd() {
        config.savePosition(engine.x.toInt(), engine.y.toInt())
    }

    override fun relayoutWindow() {
        layoutWindow()
    }

    override fun onLongPress(screenX: Float, screenY: Float) {
        // v1.2：长按唤醒 AI 对话
        config.savePosition(engine.x.toInt(), engine.y.toInt())
        openAiChat()
    }

    override fun onDoubleTap() {
        // v1.2：双击弹设置菜单（原来在长按上）
        PetMenu.show(this, engine, config) {
            remeasureWindow()
            applyInteractive()
            refreshNotification()
        }
        config.savePosition(engine.x.toInt(), engine.y.toInt())
    }

    override fun onThreeFingerTap() {
        // v1.2：三指轻点喂食（原来在双击上）
        showFeedPanel()
    }

    private fun showFeedPanel() {
        FeedPanel.show(this, engine, windowManager) {
            petView?.invalidate()
        }
    }

    // =============================== AI 对话（v1.2）===============================

    /**
     * 打开 AI 对话面板。
     *
     * 首次使用（没配 Key）时**仍然弹出面板**，在面板里提示去设置，
     * 而不是直接跳走 —— 这样用户明确知道"长按是干嘛的"，
     * 也能看到面板长什么样，不会一脸茫然地被切到设置页。
     */
    private fun openAiChat() {
        if (!config.aiEnabled) {
            engine.say("AI 对话关掉了，去设置里打开")
            return
        }

        if (chatPanel?.isShowing == true) {
            chatPanel?.hide()
            applyInteractive()
            return
        }

        val panel = AiChatPanel(
            context = this,
            windowManager = windowManager,
            history = chatHistory,
            onSend = { text -> sendToAi(text) },
            onOpenSettings = {
                startActivity(
                    Intent(this, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(SettingsActivity.EXTRA_FOCUS_API, true)
                )
            }
        )
        chatPanel = panel
        panel.show()
        config.apiKeyPrompted = true

        // 没配 Key 时直接在面板里给出可点的提示，引导去设置
        if (!config.isAiConfigured()) {
            panel.onError("还没设置 API Key，点这里去设置")
        }
        applyInteractive()
    }

    private fun sendToAi(userText: String) {
        if (aiBusy) return
        aiBusy = true
        // 对话期间鱼不移动，但呼吸/摇摆照常（对应原程序的 chat_paused）
        engine.onDragStart()

        val historySnapshot = chatHistory.forRequest().dropLast(1) // 最后一条是刚加进去的用户消息
        AiClient.ask(
            baseUrl = config.apiBaseUrl,
            apiKey = config.apiKey,
            model = config.apiModel,
            history = historySnapshot,
            userText = userText,
            callback = object : AiClient.Callback {
                override fun onDelta(text: String) {
                    chatPanel?.onDelta(text)
                }

                override fun onComplete(text: String) {
                    aiBusy = false
                    // 对话结束，解除"暂停移动"
                    engine.onDragEnd()
                    chatPanel?.onComplete(text)
                    // 让鱼同时用气泡说一句（截短，气泡放不下长文）
                    engine.say(shortForBubble(text))
                }

                override fun onError(message: String) {
                    aiBusy = false
                    engine.onDragEnd()
                    chatPanel?.onError(message)
                    engine.say(message)
                }
            }
        )
    }

    /** 气泡排版空间有限，长回复只取开头一小段 */
    private fun shortForBubble(text: String): String {
        val t = text.replace("\n", " ").trim()
        return if (t.length <= 30) t else t.take(28) + "…"
    }

    // =============================== 电量 ===============================

    private fun registerBatteryReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (scale <= 0) return
                val percent = level * 100 / scale
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                engine.onBatteryChanged(percent, charging)
            }
        }
        batteryReceiver = r
        registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun unregisterBatteryReceiver() {
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        batteryReceiver = null
    }

    private fun bottomInset(): Int {
        // 用导航栏高度粗略估计，避免鱼被导航栏压住
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    // =============================== 通知 ===============================

    private fun startForegroundCompat() {
        createChannel()
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val settingsIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PetService::class.java).setAction(ACTION_TOGGLE_TOUCH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val touchLabel = if (config.interactive) "摸不到它" else "可以摸它"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(settingsIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notif_action_settings), settingsIntent)
            .addAction(0, touchLabel, toggleIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    private fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIF_ID, buildNotification()) }
    }

    companion object {
        const val CHANNEL_ID = "dafeiyu_pet"
        const val NOTIF_ID = 1001

        const val ACTION_TOGGLE_TOUCH = "com.dafeiyu.pet.TOGGLE_TOUCH"
        const val ACTION_SET_TOUCHABLE = "com.dafeiyu.pet.SET_TOUCHABLE"
        const val ACTION_REFRESH_TOUCHABLE = "com.dafeiyu.pet.REFRESH_TOUCHABLE"
        const val ACTION_STOP = "com.dafeiyu.pet.STOP"
        const val ACTION_RELOAD_CONFIG = "com.dafeiyu.pet.RELOAD_CONFIG"
        const val ACTION_WEATHER = "com.dafeiyu.pet.WEATHER"
        const val EXTRA_CITY = "city"
        const val EXTRA_TOUCHABLE = "touchable"

        fun start(context: Context) {
            startCompat(context, Intent(context, PetService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PetService::class.java))
        }

        fun reload(context: Context) {
            startCompat(
                context,
                Intent(context, PetService::class.java).setAction(ACTION_RELOAD_CONFIG)
            )
        }

        fun requestWeather(context: Context, city: String) {
            startCompat(
                context,
                Intent(context, PetService::class.java)
                    .setAction(ACTION_WEATHER)
                    .putExtra(EXTRA_CITY, city)
            )
        }

        fun toggleTouch(context: Context) {
            startCompat(
                context,
                Intent(context, PetService::class.java).setAction(ACTION_TOGGLE_TOUCH)
            )
        }

        /**
         * 临时设置"可互动"。
         *
         * 用途：设置面板打开时把鱼切成触摸穿透，否则鱼一大就会盖住面板自己的控件，
         * 用户想把它调小都点不到（典型的鸡生蛋问题）。
         */
        fun setTouchable(context: Context, touchable: Boolean) {
            startCompat(
                context,
                Intent(context, PetService::class.java)
                    .setAction(ACTION_SET_TOUCHABLE)
                    .putExtra(EXTRA_TOUCHABLE, touchable)
            )
        }

        /**
         * 让服务按当前状态重算一次触摸/焦点 flags，不改配置。
         * 设置面板进出时调用它（因为 settingsVisible 变了）。
         */
        fun refreshTouchable(context: Context) {
            startCompat(
                context,
                Intent(context, PetService::class.java).setAction(ACTION_REFRESH_TOUCHABLE)
            )
        }

        /**
         * 统一的启动入口。
         *
         * **必须**用 `startForegroundService`：服务是前台服务，而 Android 8+
         * 规定"应用处于后台时用 startService() 启动服务"会抛
         * IllegalStateException: Not allowed to start service Intent。
         * 通知栏按钮、长按菜单都可能在后台场景触发，所以这里不能图省事用 startService。
         */
        private fun startCompat(context: Context, intent: Intent) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
