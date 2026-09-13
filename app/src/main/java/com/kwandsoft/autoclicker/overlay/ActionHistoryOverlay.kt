package com.kwandsoft.autoclicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.kwandsoft.autoclicker.history.ActionHistoryManager
import com.kwandsoft.autoclicker.history.ActionRecord
import kotlinx.coroutines.*

@SuppressLint("StaticFieldLeak")
object ActionHistoryOverlay {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var currentPage = 1
    private const val PAGE_SIZE = 10

    private val scope = CoroutineScope(Dispatchers.Main)

    fun isShowing(): Boolean = overlayView != null

    fun toggle(context: Context) {
        if (isShowing()) {
            hide()
        } else {
            show(context)
        }
    }

    fun show(context: Context) {
        if (overlayView != null) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = context.resources.displayMetrics
        val screenW = displayMetrics.widthPixels
        val screenH = displayMetrics.heightPixels

        val paramsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val width = (screenW * 0.90f).toInt().coerceAtLeast(300)
        val height = (screenH * 0.88f).toInt().coerceAtLeast(200)

        val params = WindowManager.LayoutParams(
            width,
            height,
            paramsType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#F5161616"))
                cornerRadius = 24f
                setStroke(3, Color.parseColor("#8000E5FF"))
            }
            background = bg
            setPadding(16, 14, 16, 14)
            elevation = 24f
        }

        // 1. 헤더 (타이틀 + 페이지네이션 + 닫기)
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 8)
        }

        val titleView = TextView(context).apply {
            text = "📋 최근 10분 동작 블랙박스 기록"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 14f
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        headerLayout.addView(titleView)

        val prevBtn = Button(context).apply {
            text = "◀"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(12, 0, 12, 0)
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
                cornerRadius = 8f
            }
            background = btnBg
        }
        headerLayout.addView(prevBtn)

        val pageInfoView = TextView(context).apply {
            text = "1 / 1"
            setTextColor(Color.WHITE)
            textSize = 12f
            paint.isFakeBoldText = true
            setPadding(16, 0, 16, 0)
        }
        headerLayout.addView(pageInfoView)

        val nextBtn = Button(context).apply {
            text = "▶"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(12, 0, 12, 0)
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
                cornerRadius = 8f
            }
            background = btnBg
        }
        headerLayout.addView(nextBtn)

        val refreshBtn = Button(context).apply {
            text = "🔄"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(12, 0, 12, 0)
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E88E5"))
                cornerRadius = 8f
            }
            background = btnBg
        }
        headerLayout.addView(refreshBtn)

        val closeBtn = Button(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 13f
            paint.isFakeBoldText = true
            setPadding(12, 0, 12, 0)
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#E53935"))
                cornerRadius = 8f
            }
            background = btnBg
            setOnClickListener { hide() }
        }
        headerLayout.addView(closeBtn)

        rootLayout.addView(headerLayout)

        // 구분선
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                bottomMargin = 8
            }
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }
        rootLayout.addView(divider)

        // 2. 본문 스크롤 뷰
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
        }

        val itemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(itemsContainer)
        rootLayout.addView(scrollView)

        fun renderPage() {
            itemsContainer.removeAllViews()
            val allRecords = ActionHistoryManager.getRecordsNewestFirst()
            val totalCount = allRecords.size
            val maxPages = ((totalCount - 1) / PAGE_SIZE + 1).coerceAtLeast(1)
            currentPage = currentPage.coerceIn(1, maxPages)

            titleView.text = "📋 최근 10분 동작 기록 (총 ${totalCount}건)"
            pageInfoView.text = "$currentPage / $maxPages"

            prevBtn.isEnabled = currentPage > 1
            nextBtn.isEnabled = currentPage < maxPages
            prevBtn.alpha = if (prevBtn.isEnabled) 1.0f else 0.4f
            nextBtn.alpha = if (nextBtn.isEnabled) 1.0f else 0.4f

            if (allRecords.isEmpty()) {
                val emptyTv = TextView(context).apply {
                    text = "최근 10분간 기록된 동작이 없습니다.\n(캐릭키움 모드 실행 중 동작/판단 근거가 실시간으로 보관됩니다)"
                    setTextColor(Color.GRAY)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, 80, 0, 80)
                }
                itemsContainer.addView(emptyTv)
                return
            }

            val startIndex = (currentPage - 1) * PAGE_SIZE
            val pageRecords = allRecords.drop(startIndex).take(PAGE_SIZE)

            for (record in pageRecords) {
                val card = createRecordCard(context, record)
                itemsContainer.addView(card)
            }
        }

        prevBtn.setOnClickListener {
            if (currentPage > 1) {
                currentPage--
                renderPage()
                scrollView.scrollTo(0, 0)
            }
        }

        nextBtn.setOnClickListener {
            currentPage++
            renderPage()
            scrollView.scrollTo(0, 0)
        }

        refreshBtn.setOnClickListener {
            renderPage()
        }

        renderPage()

        try {
            windowManager?.addView(rootLayout, params)
            overlayView = rootLayout
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createRecordCard(context: Context, record: ActionRecord): View {
        val density = context.resources.displayMetrics.density

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#222222"))
                cornerRadius = 12f
                setStroke(1, Color.parseColor("#33FFFFFF"))
            }
            background = bg
            val p = (8 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (6 * density).toInt()
            }
        }

        // 이미지 썸네일
        val imgView = ImageView(context).apply {
            val imgW = (130 * density).toInt()
            val imgH = (75 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(imgW, imgH).apply {
                rightMargin = (10 * density).toInt()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            val imgBg = GradientDrawable().apply {
                setColor(Color.parseColor("#111111"))
                cornerRadius = 8f
                setStroke(1, Color.parseColor("#44FFFFFF"))
            }
            background = imgBg

            if (record.imagePath != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val bmp = BitmapFactory.decodeFile(record.imagePath)
                        withContext(Dispatchers.Main) {
                            if (bmp != null) {
                                setImageBitmap(bmp)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        card.addView(imgView)

        // 텍스트 정보 레이아웃
        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f
            )
        }

        // 상단 정보 줄 (시간 + 코드 배지)
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val timeTv = TextView(context).apply {
            text = "[${record.formattedTime}]"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 11f
        }
        topRow.addView(timeTv)

        val badgeColor = when (record.code) {
            1 -> "#4CAF50"     // 장착 (초록)
            150 -> "#FF9800"   // 확인 (주황)
            160 -> "#FFEB3B"   // 퀘스트수락 (노랑)
            140 -> "#BA68C8"   // 대화스킵 (보라)
            130 -> "#29B6F6"   // 던전클리어 (하늘)
            110, 111, 112 -> "#AB47BC" // 던전선택
            170 -> "#FFD700"   // 에픽 퀘스트 (황금)
            172 -> "#FF7043"   // 손가락안내 (주황)
            121, 122, 124 -> "#E91E63" // 던전전투 (핑크)
            else -> "#00E5FF"
        }

        val badgeTv = TextView(context).apply {
            text = " [#${record.code}]"
            setTextColor(Color.parseColor(badgeColor))
            textSize = 12f
            paint.isFakeBoldText = true
        }
        topRow.addView(badgeTv)
        textContainer.addView(topRow)

        // 타이틀 (상태명)
        val titleTv = TextView(context).apply {
            text = record.title
            setTextColor(Color.WHITE)
            textSize = 13f
            paint.isFakeBoldText = true
            setPadding(0, 2, 0, 2)
        }
        textContainer.addView(titleTv)

        // 세부 동작
        val actionTv = TextView(context).apply {
            text = record.actionDesc
            setTextColor(Color.parseColor("#80DEEA"))
            textSize = 12f
        }
        textContainer.addView(actionTv)

        card.addView(textContainer)
        return card
    }

    fun hide() {
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (_: Exception) {}
            overlayView = null
        }
    }
}
