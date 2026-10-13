package com.dafeiyu.pet.ui

/**
 * 桌宠运行状态。
 *
 * 设置面板需要知道服务是否在跑，但 Activity 直接持有 Service 引用会泄漏，
 * 用一个进程内的简单标记就够了（服务与 Activity 在同一进程）。
 */
object PetRuntime {

    @Volatile
    var isRunning: Boolean = false
        internal set

    /**
     * 设置面板是否正显示在前台。
     *
     * 服务创建悬浮窗时会查询这个标记：面板开着就把鱼设成触摸穿透，
     * 否则鱼一大就会盖住面板自己的控件，用户想把它调小都点不到。
     *
     * 为什么需要这个标记而不是只靠 onResume 发指令：
     * 用户常见的操作顺序是「先打开设置面板 -> 再点启动桌宠」。
     * 那一刻服务还没启动，onResume 发的指令没人接收；
     * 等服务起来时面板已经在前台了，没人再告诉它该穿透。
     */
    @Volatile
    var settingsVisible: Boolean = false
        internal set

    /**
     * AI 对话界面是否在前台（v1.2.2 新增）。
     *
     * 由 [ChatActivity] 维护。服务据此让鱼触摸穿透 ——
     * 否则鱼的悬浮窗会盖住对话界面底部的输入区，用户点输入框时点到的是鱼。
     *
     * 与 [settingsVisible] 分开而不是合并，是因为两者语义不同：
     * 设置面板是"用户正在调参数"，对话界面是"用户正在打字"，
     * 将来可能对它们做不同处理。
     */
    @Volatile
    var chatVisible: Boolean = false
        internal set
}
