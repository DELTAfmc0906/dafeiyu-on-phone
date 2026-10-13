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
    /** 正在等 AI 回复（气泡摘要用） */
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
        // 把系统字体缩放告知引擎 —— 气泡预留高度要按同一系数放大，
        // 否则用户调大系统字体后气泡文字会溢出重合（v1.4.0 修）
        engine.setFontScale(resources.configuration.fontScale)
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
            ACTION_SAY -> {
                // 让鱼用气泡说一句（由对话界面转发 AI 回复）
                val text = intent.getStringExtra(EXTRA_SAY_TEXT).orEmpty()
                engine.say(shortForBubble(text))
            }
            ACTION_RELOAD_CONFIG -> {
                engine.mode = config.mode
                engine.sizePercent = config.sizePercent
                // 字体缩放可能在运行期间被系统改过，每次重载都同步一次
                engine.setFontScale(resources.configuration.fontScale)
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
        // 无条件清理浮层。
        // v1.2.2 修：早期版本喂食面板不在 onDestroy 里清理，
        // 停服务时可能留下一个没人管的悬浮窗持续吃掉触摸。
        runCatching { FeedPanel.dismiss(windowManager) }
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
    /**
     * 统一计算"现在鱼是否应该接收触摸"。
     *
     * 三种情况要求穿透：
     *   1. 用户把「可互动」关掉了（等价 PC 版的鼠标穿透）
     *   2. 设置面板正开在前台，且用户没有开「强制可互动」
     *      —— 这是为了避免鱼太大挡住面板控件（v1.1 引入）
     *
     * 关于第 2 条：v1.1 无条件穿透，导致用户一打开 app 鱼就"摸不到"，
     * 看起来像卡死（实测可长达 50 秒以上）。v1.2.1 加了 forceInteractive
     * 开关让用户自己权衡。
     */
    private fun shouldBeTouchable(): Boolean {
        if (!config.interactive) return false
        if (config.forceInteractive) return true
        return !PetRuntime.settingsVisible
    }

    private fun applyInteractive() {
        val lp = layoutParams ?: return
        val v = petView ?: return

        /*
         * 触摸穿透（v1.4.0 调整）。
         *
         * ## 为什么不再因为"对话界面打开"就强制穿透
         *
         * v1.2.2 起这里有一句 `shouldBeTouchable() && !PetRuntime.chatVisible`，
         * 用意是防止鱼的悬浮窗盖住对话界面的控件。但那个前提**已经过时了**：
         * 当时对话界面是画在鱼自己窗口里的一个面板，两者确实重叠；
         * 现在对话界面是独立的 Activity 窗口（v1.2.2 重构的成果），鱼在上面
         * 并不会挡住它 —— 系统会让后打开的 Activity 窗口浮在悬浮窗之上。
         *
         * 保留那句的代价却是实打实的：用户一打开对话，鱼就变成
         * NOT_TOUCHABLE，而系统对"不可触摸且 alpha>0.8"的悬浮窗会
         * **自动把 alpha 压到 0.8**（WindowManager 的行为），
         * 于是鱼看起来变透明了，同时也点不动 —— 用户反馈的正是这个现象。
         *
         * 现在只在真正需要让路的时候穿透（用户关了可互动、或设置面板在前台）。
         */
        val touchable = shouldBeTouchable()
        lp.flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        /*
         * 显式设成不透明。
         *
         * 系统只对"NOT_TOUCHABLE 且 alpha > 0.8"的悬浮窗做压暗处理，
         * 但某些 ROM（含 MIUI）会读到一个非 1.0 的默认值就动手。
         * 明确写 1f，避免被误判。
         */
        lp.alpha = 1f

        // 焦点：鱼窗口**永远**保持 NOT_FOCUSABLE。
        //
        // v1.2/v1.2.1 曾在这里"面板打开时去掉 NOT_FOCUSABLE"，那是错的：
        // 同一时刻只能有一个窗口持有输入焦点，鱼一旦变成可聚焦就会跟 AI 面板抢，
        // 结果是输入法连不上面板（键盘弹不出来）；
        // 更糟的是面板关闭后鱼仍处于可聚焦状态，把输入焦点一直攥着不放，
        // 表现为整个手机界面点不动，只能重启。
        //
        // 面板要拿焦点，应该由面板自己 requestFocus + 显式 showSoftInput，
        // 配合它自己的 FLAG_ALT_FOCUSABLE_IM，不需要鱼让出 NOT_FOCUSABLE。
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

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

    // =============================== AI 对话（v1.2 起，v1.2.2 改为 Activity）===============================

    /**
     * 打开 AI 对话。
     *
     * v1.2.2 起改为启动 [ChatActivity]，而不是自己 addView 一个悬浮窗。
     * 原因见 ChatActivity 的类注释：悬浮窗拿不到输入法的 InputConnection
     * （键盘弹出来但打字进不去），而且脱离 Activity 生命周期容易残留成
     * "吃掉全局触摸"的孤儿窗口。
     *
     * 首次使用（没配 Key）时仍然打开对话界面，在里面给可点提示，
     * 而不是直接跳设置页 —— 用户得先知道"长按"是干什么的。
     */
    private fun openAiChat() {
        if (!config.aiEnabled) {
            engine.say("AI 对话关掉了，去设置里打开")
            return
        }
        config.apiKeyPrompted = true
        // 对话界面是真正的 Activity 窗口，会被系统置顶；
        // 鱼在这个窗口打开期间要触摸穿透，避免挡住输入区。
        PetRuntime.chatVisible = true
        applyInteractive()
        ChatActivity.start(this)
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
        const val ACTION_SAY = "com.dafeiyu.pet.SAY"
        const val EXTRA_SAY_TEXT = "say_text"
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
         * 让桌宠用气泡说一句（v1.2.3 新增）。
         *
         * 对话界面是独立 Activity，不能直接操作桌宠的引擎，
         * 所以通过服务转达。用于 AI 回复完后让鱼也"说一句"，
         * 保留原 Python 版的桌宠存在感。
         */
        fun say(context: Context, text: String) {
            if (text.isBlank()) return
            startCompat(
                context,
                Intent(context, PetService::class.java)
                    .setAction(ACTION_SAY)
                    .putExtra(EXTRA_SAY_TEXT, text)
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
