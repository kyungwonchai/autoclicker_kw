package com.kwandsoft.autoclicker.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.kwandsoft.autoclicker.model.ClickPoint

class PointTargetOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    val point: ClickPoint,
    var colorHex: String = "#FF9800",
    private val onPositionUpdated: (ClickPoint) -> Unit
) {
    private var targetView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun show() {
        if (targetView != null) return

        val size = 36 // dp
        val density = context.resources.displayMetrics.density
        val pxSize = (size * density).toInt()

        val displayMetrics = context.resources.displayMetrics
        val screenW = displayMetrics.widthPixels
        val screenH = displayMetrics.heightPixels

        val paramsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val px = (point.xRatio * screenW).toInt() - pxSize / 2
        val py = (point.yRatio * screenH).toInt() - pxSize / 2

        layoutParams = WindowManager.LayoutParams(
            pxSize,
            pxSize,
            paramsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px
            y = py
        }


        val textView = TextView(context).apply {
            text = "🎯"
            textSize = 18f
            gravity = Gravity.CENTER
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(colorHex))
                setStroke(2, Color.WHITE)
            }
            background = drawable
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        textView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams?.x ?: 0
                    initialY = layoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
                        isDragging = true
                    }
                    layoutParams?.x = initialX + dx
                    layoutParams?.y = initialY + dy
                    windowManager.updateViewLayout(textView, layoutParams)

                    point.rawX = event.rawX
                    point.rawY = event.rawY

                    val curScreenW = context.resources.displayMetrics.widthPixels
                    val curScreenH = context.resources.displayMetrics.heightPixels
                    point.xRatio = (event.rawX / curScreenW).coerceIn(0f, 1f)
                    point.yRatio = (event.rawY / curScreenH).coerceIn(0f, 1f)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    point.rawX = (layoutParams?.x ?: 0) + pxSize / 2f
                    point.rawY = (layoutParams?.y ?: 0) + pxSize / 2f
                    val curScreenW = context.resources.displayMetrics.widthPixels
                    val curScreenH = context.resources.displayMetrics.heightPixels
                    point.xRatio = (point.rawX / curScreenW).coerceIn(0f, 1f)
                    point.yRatio = (point.rawY / curScreenH).coerceIn(0f, 1f)
                    onPositionUpdated(point)
                    true
                }
                else -> false
            }
        }


        targetView = textView
        windowManager.addView(targetView, layoutParams)
    }

    fun updateScreenOrientation() {
        if (targetView == null || layoutParams == null) return
        val size = 36
        val density = context.resources.displayMetrics.density
        val pxSize = (size * density).toInt()

        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels

        layoutParams?.x = (point.xRatio * screenW).toInt() - pxSize / 2
        layoutParams?.y = (point.yRatio * screenH).toInt() - pxSize / 2
        windowManager.updateViewLayout(targetView, layoutParams)
    }

    fun updateColor(newColorHex: String) {
        colorHex = newColorHex
        (targetView as? TextView)?.let { tv ->
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(newColorHex))
                setStroke(2, Color.WHITE)
            }
            tv.background = drawable
        }
    }

    fun remove() {
        targetView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
            }
            targetView = null
        }
    }
}

