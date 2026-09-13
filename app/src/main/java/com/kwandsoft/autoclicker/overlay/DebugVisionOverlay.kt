package com.kwandsoft.autoclicker.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object DebugVisionOverlay {
    private var windowManager: WindowManager? = null
    private var containerView: LinearLayout? = null
    private var labelView: TextView? = null
    private var cropImageView: ImageView? = null
    private var metricsView: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(context: Context) {
        mainHandler.post {
            if (containerView != null) return@post
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
                    gravity = Gravity.TOP or Gravity.START
                    x = 24
                    y = 36 // 좌측 상단 노치/UI 아래 클릭 안 하는 안전 영역
                }

                val density = context.resources.displayMetrics.density

                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val bg = GradientDrawable().apply {
                        setColor(Color.parseColor("#E6000000")) // 검정 반투명 배경
                        cornerRadius = 12f
                        setStroke(2, Color.parseColor("#00E5FF")) // 청록색 테두리
                    }
                    background = bg
                    val pad = (6 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                }

                val label = TextView(context).apply {
                    setTextColor(Color.parseColor("#00E5FF"))
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    text = "🔍 [AI 판단 근거]"
                }
                container.addView(label)
                labelView = label

                val iv = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (140 * density).toInt(),
                        (70 * density).toInt()
                    ).apply {
                        topMargin = (3 * density).toInt()
                        bottomMargin = (3 * density).toInt()
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val ivBg = GradientDrawable().apply {
                        setColor(Color.BLACK)
                        cornerRadius = 6f
                        setStroke(1, Color.GRAY)
                    }
                    background = ivBg
                }
                container.addView(iv)
                cropImageView = iv

                val metrics = TextView(context).apply {
                    setTextColor(Color.YELLOW)
                    textSize = 9f
                    typeface = Typeface.MONOSPACE
                    text = "초기화 대기 중"
                }
                container.addView(metrics)
                metricsView = metrics

                wm.addView(container, params)
                containerView = container
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateVision(label: String, cropBitmap: Bitmap?, metricsText: String) {
        mainHandler.post {
            labelView?.text = label
            metricsView?.text = metricsText
            if (cropBitmap != null && !cropBitmap.isRecycled) {
                cropImageView?.setImageBitmap(cropBitmap)
            }
        }
    }

    fun hide() {
        mainHandler.post {
            try {
                containerView?.let {
                    windowManager?.removeView(it)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                containerView = null
                labelView = null
                cropImageView = null
                metricsView = null
                windowManager = null
            }
        }
    }
}
