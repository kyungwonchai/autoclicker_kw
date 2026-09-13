package com.kwandsoft.autoclicker.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

object StatusHudOverlay {
    private var windowManager: WindowManager? = null
    private var hudView: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentText: String = ""

    fun show(context: Context) {
        mainHandler.post {
            if (hudView != null) return@post
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                windowManager = wm

                val paramsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    paramsType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    x = 0
                    y = 25 // 최상단 약간 아래
                }

                val textView = TextView(context).apply {
                    val bg = GradientDrawable().apply {
                        setColor(Color.parseColor("#E6000000")) // 검정 배경 (90% 불투명)
                        cornerRadius = 14f
                        setStroke(2, Color.parseColor("#AAFFFFFF")) // 흰색 테두리
                    }
                    background = bg
                    setTextColor(Color.WHITE) // 흰 글자
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    val density = context.resources.displayMetrics.density
                    val padH = (16 * density).toInt()
                    val padV = (6 * density).toInt()
                    setPadding(padH, padV, padH, padV)
                    text = "[#000] KW 오토클리커 준비 완료"
                }

                wm.addView(textView, params)
                hudView = textView
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateStatus(code: Int, description: String) {
        val newText = "[#%03d] %s".format(code, description)
        if (newText == currentText) return
        currentText = newText
        mainHandler.post {
            hudView?.text = newText
        }
    }

    fun hide() {
        mainHandler.post {
            try {
                hudView?.let {
                    windowManager?.removeView(it)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                hudView = null
                windowManager = null
                currentText = ""
            }
        }
    }
}
