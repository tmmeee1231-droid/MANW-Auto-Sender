package com.manw.autosender

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/** User-enabled accessibility bridge for explicit remote taps, swipes and text entry. */
class RemoteControlAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var active: RemoteControlAccessibilityService? = null
        fun handle(payload: JSONObject): Boolean = active?.performRemoteAction(payload) ?: false
    }

    override fun onServiceConnected() { super.onServiceConnected(); active = this }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }

    private fun performRemoteAction(data: JSONObject): Boolean {
        return try {
            when (data.optString("action")) {
                "tap" -> {
                    val (w,h)=screenSize()
                    val x=(data.optDouble("x",0.5).coerceIn(0.0,1.0)*w).toFloat()
                    val y=(data.optDouble("y",0.5).coerceIn(0.0,1.0)*h).toFloat()
                    val path=Path().apply { moveTo(x,y) }
                    val stroke=GestureDescription.StrokeDescription(path,0,90)
                    dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),null,null)
                }
                "swipe" -> {
                    val (w,h)=screenSize()
                    val x1=(data.optDouble("x1",0.5).coerceIn(0.0,1.0)*w).toFloat()
                    val y1=(data.optDouble("y1",0.5).coerceIn(0.0,1.0)*h).toFloat()
                    val x2=(data.optDouble("x2",0.5).coerceIn(0.0,1.0)*w).toFloat()
                    val y2=(data.optDouble("y2",0.5).coerceIn(0.0,1.0)*h).toFloat()
                    val path=Path().apply { moveTo(x1,y1); lineTo(x2,y2) }
                    val stroke=GestureDescription.StrokeDescription(path,0,data.optLong("duration",350).coerceIn(100,1200))
                    dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),null,null)
                }
                "text" -> {
                    val value=data.optString("value").take(1000)
                    val node=findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (node != null && value.isNotEmpty()) {
                        val args=Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value) }
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)
                    } else false
                }
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                else -> false
            }
        } catch (_: Exception) { false }
    }

    private fun screenSize(): Pair<Int,Int> {
        val metrics=DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        return Pair(metrics.widthPixels,metrics.heightPixels)
    }
}