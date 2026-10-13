package com.dafeiyu.pet.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.dafeiyu.pet.data.ConfigRepo

/**
 * 开机自启。
 *
 * 对应 Python 版的 `set_autostart()`（原版是往启动目录扔一个 .lnk 快捷方式）。
 * Android 上等价做法：开关这个 Receiver 的组件状态 + 一个延时的 AlarmManager 兜底。
 *
 * 注意：国产 ROM（小米/华为/OPPO/vivo 等）普遍会拦截开机广播，属于系统级限制，
 * 代码层面无法绕过。开启后如果没自动起来，需要去系统的"自启动管理"里放行本应用。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_DELAYED_START -> {
                // 延时兜底：确认权限还在就拉起服务
                if (android.provider.Settings.canDrawOverlays(context)) {
                    PetService.start(context)
                }
            }
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> {
                val config = ConfigRepo(context)
                if (!config.autoStart) return
                if (!android.provider.Settings.canDrawOverlays(context)) return
                // 开机后系统还在忙，稍等一下再起服务，避免被系统杀掉
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getBroadcast(
                    context,
                    REQ_CODE,
                    Intent(context, BootReceiver::class.java).setAction(ACTION_DELAYED_START),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val triggerAt = SystemClock.elapsedRealtime() + 8_000L
                runCatching {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }.onFailure {
                    runCatching { am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi) }
                }
            }
        }
    }

    companion object {
        private const val REQ_CODE = 2001
        const val ACTION_DELAYED_START = "com.dafeiyu.pet.DELAYED_START"

        /** 开关 Receiver 组件。关闭时系统连广播都不会投递，比在 onReceive 里判断更省电。 */
        fun setEnabled(context: Context, enabled: Boolean) {
            val cn = ComponentName(context, BootReceiver::class.java)
            val newState = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            runCatching {
                context.packageManager.setComponentEnabledSetting(
                    cn, newState, PackageManager.DONT_KILL_APP
                )
            }
        }
    }
}
