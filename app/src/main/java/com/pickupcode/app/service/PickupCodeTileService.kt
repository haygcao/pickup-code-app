package com.pickupcode.app.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast

/**
 * 快捷设置磁贴：点击 → 触发截图识别。
 *
 * 触发链路（与双音量键完全同路径）：置 [PickupCodeAccessibilityService.triggerRequested] 标记，
 * 常驻无障碍服务的心跳（3 秒）/ 系统事件 / 服务重连消费标记后执行截图 OCR。
 *
 * 修复要点（v1.0.8 起「点击磁贴但没有识别」的根因之一）：
 * 旧版只查 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 字符串。Xiaomi/HyperOS 杀进程、
 * 省电冻结后常常「设置里仍显示开启、服务实际未连接」——标记无处消费，点击静默失效。
 * 因此：
 *  - [onClick] 分三支：真连接 → 置标记并 Toast 确认；假连接 → 明示并引导重开；未开启 → 引导设置。
 *  - [onStartListening] 磁贴灰态以「真实连接」为准，不再"看起来开着其实没反应"。
 */
class PickupCodeTileService : TileService() {

    // 回主线程更新磁贴用（updateTile 必须在主线程）
    private val mainHandler = Handler(Looper.getMainLooper())

    // 磁贴状态检查用单线程执行器（复用，替代每次 onStartListening new 裸 Thread；daemon 不阻止进程退出）
    private val stateExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "tile-state").apply { isDaemon = true }
    }

    override fun onClick() {
        super.onClick()
        val enabledInSettings = isEnabledInSettings()
        val reallyConnected = PickupCodeAccessibilityService.isReallyConnected(this)
        when {
            // ① 服务真实连接：置触发标记，由常驻服务消费后截图识别；Toast 即时确认，避免"点了没反应"
            enabledInSettings && reallyConnected -> {
                PickupCodeAccessibilityService.triggerRequested.set(true)
                Log.d(TAG_TILE, "触发标记已设置（服务已连接）")
                toast("已触发识别，结果稍后以通知提示")
            }
            // ② 设置里开着但服务实际没连上（Xiaomi/HyperOS 杀进程后常见）：
            //    标记会无人消费而静默失效——必须明示并引导重开，而不是假装触发
            enabledInSettings && !reallyConnected -> {
                Log.w(TAG_TILE, "无障碍服务设置开启但实际未连接，跳转设置引导重开")
                toast("无障碍服务未在运行，请重新开启")
                startAccessibilitySettings()
            }
            // ③ 未开启：引导设置（M4）
            else -> {
                Log.d(TAG_TILE, "无障碍服务未开启，跳转设置")
                startAccessibilitySettings()
            }
        }
    }

    private fun startAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e(TAG_TILE, "打开无障碍设置失败: ${e.message}")
        }
    }

    /** 设置字符串检查：仅判断「开关状态」，不能代表服务真实连接。 */
    private fun isEnabledInSettings(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        // split + 精确比对包名+服务类名，避免 contains 模糊匹配误判（M4/MainActivity低危项同源）
        val target = "$packageName/${PickupCodeAccessibilityService::class.java.name}"
        return enabledServices.split(':').any { it.trim() == target }
    }

    override fun onStartListening() {
        super.onStartListening()
        // 磁贴可用态以「服务真实连接」为准（Low-2 基础上再收紧一层）：
        // 避免设置字符串显示开启但服务实际被杀时磁贴仍亮着 → 点击静默无反应。
        // Settings.Secure / AccessibilityManager 涉及 Binder/IO，放子线程；磁贴更新回主线程
        stateExecutor.execute {
            val ok = isEnabledInSettings() &&
                PickupCodeAccessibilityService.isReallyConnected(this@PickupCodeTileService)
            mainHandler.post {
                qsTile?.apply {
                    state = if (ok) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
                    updateTile()
                }
            }
        }
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG_TILE, "Toast 失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        stateExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG_TILE = "PickupCodeTile"
    }
}