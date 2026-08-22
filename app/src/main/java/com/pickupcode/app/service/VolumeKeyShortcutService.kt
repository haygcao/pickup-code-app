package com.pickupcode.app.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * 双音量键快捷触发（澎湃记方案，2026-08-13）：
 * 不监听 keyevent——利用 Android 系统自带「无障碍快捷方式」（系统设置里可配置
 * "同时按住两个音量键"触发本服务）。触发后立即 disableSelf 用完即走，不常驻。
 *
 * 配置方法：系统设置 → 无障碍 → 无障碍快捷方式 → 选择「码上闪记（音量键快捷方式）」。
 * 与常驻的 [PickupCodeAccessibilityService]（截图 OCR 核心）互不干扰：
 * 本服务只负责通过 [PickupCodeAccessibilityService.armManual] 置识别标记，
 * 常驻服务消费后执行截图识别——与磁贴同路径（音量键无需等面板收起）。
 */
class VolumeKeyShortcutService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 用完即走：本服务不常驻，仅作为系统快捷方式的触发通道
        disableSelf()
        // 复用磁贴的触发路径：置标记 → 常驻服务消费后执行截图识别
        // 服务假连接防护：主无障碍服务实际未连接时标记无人消费，直接提示（同磁贴修复）。
        // fromPanel=false：音量键触发时目标界面已在当前屏幕，无需等控制面板收起，立即扫描。
        // 用同进程 connected 标志（vivo 上 AccessibilityManager API 对已绑定服务假阴性）
        if (PickupCodeAccessibilityService.connected) {
            PickupCodeAccessibilityService.armManual(fromPanel = false)
            Log.d("VolumeKeyShortcut", "音量键触发：识别标记已设置")
        } else {
            Log.w("VolumeKeyShortcut", "主无障碍服务未运行，触发标记将无人消费")
            try {
                Toast.makeText(this, "码上闪记无障碍服务未在运行，请先重新开启", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w("VolumeKeyShortcut", "Toast 失败: ${e.message}")
            }
        }
    }
}
