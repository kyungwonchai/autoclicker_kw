package com.kwandsoft.autoclicker.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.kwandsoft.autoclicker.data.PresetStorage
import com.kwandsoft.autoclicker.model.ButtonSlot
import com.kwandsoft.autoclicker.model.ClickPoint
import com.kwandsoft.autoclicker.service.AutoClickAccessibilityService

class FloatingOverlayManager(
    private val context: Context,
    private val onExitRequested: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var controlMenuView: View? = null
    private var menuLayoutParams: WindowManager.LayoutParams? = null

    private val slots = mutableListOf<ButtonSlot>()
    private var targetPoint: ClickPoint = ClickPoint()
    private var pointOverlay: PointTargetOverlay? = null

    private var isCollapsed = false
    private var buttonsContainer: LinearLayout? = null
    private var toggleCollapseBtn: TextView? = null

    private val slotPlayButtons = mutableListOf<Button>()
    private var growthButton: Button? = null

    fun show() {
        if (controlMenuView != null) return

        slots.clear()
        slots.addAll(PresetStorage.getSlots())
        targetPoint = PresetStorage.loadTargetPoint(context)
        val (savedX, savedY) = PresetStorage.getMenuPosition(context)

        val paramsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        menuLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            paramsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#E6181818"))
                cornerRadius = 16f
                setStroke(2, Color.parseColor("#44FFFFFF"))
            }
            background = bg
            setPadding(8, 6, 8, 8)
            elevation = 16f
        }

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dragHandle = TextView(context).apply {
            text = "⚡KW"
            setTextColor(Color.WHITE)
            textSize = 10f
            setPadding(4, 2, 4, 2)
        }
        setupDrag(dragHandle)
        topBar.addView(dragHandle)

        toggleCollapseBtn = TextView(context).apply {
            text = "▲"
            setTextColor(Color.LTGRAY)
            textSize = 9f
            setPadding(4, 2, 4, 2)
            setOnClickListener {
                toggleCollapse()
            }
        }
        topBar.addView(toggleCollapseBtn)
        container.addView(topBar)

        val menuScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
        }

        buttonsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 2, 0, 0)
        }

        // --- 15, 10, 5 버튼 생성 ---
        slotPlayButtons.clear()
        for (i in 0 until slots.size) {
            val slot = slots[i]
            // 표시 라벨: "15" (15초), "10" (10초), "5" (5초)
            val btn = createButton(slot.name, slot.colorHex) {
                toggleSlotPlay(i)
            }
            slotPlayButtons.add(btn)
            buttonsContainer?.addView(btn)
        }

        // 구분선
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(2, 4, 2, 4)
            }
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }
        buttonsContainer?.addView(divider)

        // 캐릭키움 모드 토글 버튼 (🌱)
        val growthModeBtn = createButton("🌱", "#2E7D32") {
            val service = AutoClickAccessibilityService.instance
            if (service != null) {
                val newState = !service.isGrowthMode()
                service.setGrowthMode(newState)
                updateGrowthButtonState(newState)
                val msg = if (newState) "🌱 [캐릭키움 모드] 활성화: 퀘스트 이동/스킵/전투 자동 순환" else "🌱 [캐릭키움 모드] 비활성화"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
        growthButton = growthModeBtn
        buttonsContainer?.addView(growthModeBtn)

        // 타겟 위치 초기화/중앙 이동 버튼 (🎯)
        val centerTargetBtn = createButton("🎯", "#FF9800") {
            targetPoint.xRatio = 0.5f
            targetPoint.yRatio = 0.5f
            saveTargetPoint()
            pointOverlay?.updateScreenOrientation()
            Toast.makeText(context, "🎯 타겟 위치가 화면 중앙으로 이동되었습니다.", Toast.LENGTH_SHORT).show()
        }
        buttonsContainer?.addView(centerTargetBtn)

        // 닫기 (❌)
        val closeBtn = createButton("❌", "#F44336") {
            stopAll()
            onExitRequested()
        }
        buttonsContainer?.addView(closeBtn)

        menuScrollView.addView(buttonsContainer)
        container.addView(menuScrollView)
        controlMenuView = container
        windowManager.addView(controlMenuView, menuLayoutParams)

        // 초기 캐릭키움 상태 반영
        updateGrowthButtonState(AutoClickAccessibilityService.instance?.isGrowthMode() ?: false)

        // 단일 타겟 포인트 오버레이 표시
        showTargetOverlay()
    }

    private fun showTargetOverlay() {
        pointOverlay?.remove()
        pointOverlay = PointTargetOverlay(
            context = context,
            windowManager = windowManager,
            point = targetPoint,
            colorHex = "#FF9800",
            onPositionUpdated = { updatedPoint ->
                targetPoint = updatedPoint
                saveTargetPoint()
            }
        )
        pointOverlay?.show()
    }

    private fun saveTargetPoint() {
        PresetStorage.saveTargetPoint(context, targetPoint)
    }

    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        buttonsContainer?.visibility = if (isCollapsed) View.GONE else View.VISIBLE
        toggleCollapseBtn?.text = if (isCollapsed) "▼" else "▲"
    }

    private fun createButton(labelText: String, hexColor: String, onClick: () -> Unit): Button {
        val density = context.resources.displayMetrics.density
        val btnSize = (38 * density).toInt()

        return Button(context).apply {
            text = labelText
            textSize = 12f
            setTextColor(Color.WHITE)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor(hexColor))
                cornerRadius = 14f
            }
            background = bg
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                setMargins(0, 3, 0, 3)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setupDrag(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = menuLayoutParams?.x ?: 0
                    initialY = menuLayoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    menuLayoutParams?.x = initialX + dx
                    menuLayoutParams?.y = initialY + dy
                    windowManager.updateViewLayout(controlMenuView, menuLayoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    menuLayoutParams?.let {
                        PresetStorage.saveMenuPosition(context, it.x, it.y)
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun handleConfigurationChanged(newConfig: Configuration) {
        pointOverlay?.updateScreenOrientation()
    }

    private fun toggleSlotPlay(slotIndex: Int) {
        val service = AutoClickAccessibilityService.instance ?: return
        val runningSlotId = service.getRunningSlotId()
        val targetSlot = slots[slotIndex]

        if (runningSlotId != null) {
            if (runningSlotId == targetSlot.slotId) {
                // 현재 실행 중인 버튼을 누르면 즉시 중지
                service.stopClicking { running, activeId ->
                    updatePlayStates(running, activeId)
                }
                updatePlayStates(false, null)
            }
            return
        }

        // 실행 중인 동작이 없을 때만 새 동작 시작
        service.startSlotExecution(
            slot = targetSlot,
            point = targetPoint,
            onStatusChange = { running, activeId ->
                updatePlayStates(running, activeId)
            }
        )
        updatePlayStates(true, targetSlot.slotId)
    }

    private fun updatePlayStates(running: Boolean, activeSlotId: Int?) {
        for (i in 0 until slotPlayButtons.size) {
            val btn = slotPlayButtons[i]
            val slot = slots.getOrNull(i) ?: continue

            if (running) {
                if (activeSlotId == slot.slotId) {
                    // 실행 중인 본인 버튼 -> 빨간색 중지(■) 활성화
                    btn.text = "■${slot.name}"
                    val bg = GradientDrawable().apply {
                        setColor(Color.parseColor("#D50000"))
                        cornerRadius = 14f
                        setStroke(2, Color.WHITE)
                    }
                    btn.background = bg
                    btn.alpha = 1.0f
                    btn.isEnabled = true
                } else {
                    // 실행 중이 아닌 다른 버튼 -> 회색 비활성화 (클릭 불가)
                    btn.text = slot.name
                    val bg = GradientDrawable().apply {
                        setColor(Color.parseColor("#424242"))
                        cornerRadius = 14f
                    }
                    btn.background = bg
                    btn.alpha = 0.35f
                    btn.isEnabled = false
                }
            } else {
                // 모두 정지 상태 -> 모든 버튼 원래 색상으로 활성화
                btn.text = slot.name
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor(slot.colorHex))
                    cornerRadius = 14f
                }
                btn.background = bg
                btn.alpha = 1.0f
                btn.isEnabled = true
            }
        }
        pointOverlay?.updateColor(if (running) "#D50000" else "#FF9800")
    }

    private fun updateGrowthButtonState(enabled: Boolean) {
        growthButton?.let { btn ->
            if (enabled) {
                btn.text = "🌱ON"
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#43A047"))
                    cornerRadius = 14f
                    setStroke(2, Color.WHITE)
                }
                btn.background = bg
                btn.alpha = 1.0f
            } else {
                btn.text = "🌱"
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#37474F"))
                    cornerRadius = 14f
                }
                btn.background = bg
                btn.alpha = 0.8f
            }
        }
    }


    fun stopAll() {
        AutoClickAccessibilityService.instance?.stopClicking()
        pointOverlay?.remove()
        pointOverlay = null
        controlMenuView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
            }
            controlMenuView = null
        }
    }
}



