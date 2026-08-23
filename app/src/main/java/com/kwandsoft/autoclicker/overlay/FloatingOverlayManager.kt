package com.kwandsoft.autoclicker.overlay

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.*
import android.widget.*
import com.kwandsoft.autoclicker.data.PresetStorage
import com.kwandsoft.autoclicker.model.ActionTrigger
import com.kwandsoft.autoclicker.model.ActionType
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
    private var activeSlotIndex = 0 // 현재 화면에 표시/편집 중인 슬롯 인덱스 (0, 1, 2)
    private val pointOverlays = mutableListOf<PointTargetOverlay>()

    private var isCollapsed = false
    private var buttonsContainer: LinearLayout? = null
    private var toggleCollapseBtn: TextView? = null
    private var slotIndicatorView: TextView? = null

    private val slotPlayButtons = mutableListOf<Button>()

    fun show() {
        if (controlMenuView != null) return

        // 저장된 3개 슬롯 로드
        slots.clear()
        slots.addAll(PresetStorage.loadSlots(context))
        if (slots.isEmpty()) {
            slots.add(ButtonSlot(1, "1번 (15초 홀드)"))
            slots.add(ButtonSlot(2, "2번 (단발 탭)"))
            slots.add(ButtonSlot(3, "3번 (연타 10회)"))
        }

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
            x = 20
            y = 120
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#F0181818"))
                cornerRadius = 18f
                setStroke(2, Color.parseColor("#55FFFFFF"))
            }
            background = bg
            setPadding(10, 8, 10, 10)
            elevation = 16f
        }

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dragHandle = TextView(context).apply {
            text = "⚡ KW"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(4, 4, 8, 4)
        }
        setupDrag(dragHandle)
        topBar.addView(dragHandle)

        toggleCollapseBtn = TextView(context).apply {
            text = "▲"
            setTextColor(Color.LTGRAY)
            textSize = 10f
            setPadding(8, 4, 4, 4)
            setOnClickListener {
                toggleCollapse()
            }
        }
        topBar.addView(toggleCollapseBtn)
        container.addView(topBar)

        buttonsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 0)
        }

        // --- 1, 2, 3번 시작/중지 버튼 ---
        slotPlayButtons.clear()
        for (i in 0 until 3) {
            val slot = slots.getOrNull(i) ?: ButtonSlot(i + 1, "동작 ${i + 1}")
            val btn = createButton("▶${i + 1}", slot.colorHex) {
                toggleSlotPlay(i)
            }
            btn.setOnLongClickListener {
                // 길게 누르면 해당 슬롯 선택 및 설정 열기
                selectSlot(i)
                showSlotConfigDialog(slots[i])
                true
            }
            slotPlayButtons.add(btn)
            buttonsContainer?.addView(btn)
        }

        // 구분선
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(2, 4, 2, 4)
            }
            setBackgroundColor(Color.parseColor("#44FFFFFF"))
        }
        buttonsContainer?.addView(divider)

        // 슬롯 선택/전환 버튼 (편집 대상 변경)
        slotIndicatorView = TextView(context).apply {
            text = "[${activeSlotIndex + 1}번 편집]"
            setTextColor(Color.parseColor(getCurrentSlot().colorHex))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(2, 2, 2, 2)
            setOnClickListener {
                // 슬롯 순환 전환 (1 -> 2 -> 3 -> 1)
                val nextSlot = (activeSlotIndex + 1) % 3
                selectSlot(nextSlot)
                Toast.makeText(context, "${nextSlot + 1}번 슬롯 선택됨 (길게 누르면 상세설정)", Toast.LENGTH_SHORT).show()
            }
            setOnLongClickListener {
                showSlotConfigDialog(getCurrentSlot())
                true
            }
        }
        buttonsContainer?.addView(slotIndicatorView)

        // 포인트 추가 (+)
        val addBtn = createButton("➕", "#37474F") {
            addNewPointToCurrentSlot()
        }
        buttonsContainer?.addView(addBtn)

        // 포인트 제거 (-)
        val removeBtn = createButton("➖", "#455A64") {
            removeLastPointFromCurrentSlot()
        }
        buttonsContainer?.addView(removeBtn)

        // 슬롯 상세 설정 (⚙️)
        val configBtn = createButton("⚙️", "#607D8B") {
            showSlotConfigDialog(getCurrentSlot())
        }
        buttonsContainer?.addView(configBtn)

        // 닫기 (❌)
        val closeBtn = createButton("❌", "#F44336") {
            stopAll()
            onExitRequested()
        }
        buttonsContainer?.addView(closeBtn)

        container.addView(buttonsContainer)
        controlMenuView = container
        windowManager.addView(controlMenuView, menuLayoutParams)

        // 현재 슬롯의 타겟 포인트 표시
        refreshTargetOverlays()
    }

    private fun getCurrentSlot(): ButtonSlot {
        return slots[activeSlotIndex]
    }

    private fun selectSlot(index: Int) {
        activeSlotIndex = index
        slotIndicatorView?.apply {
            text = "[${activeSlotIndex + 1}번 편집]"
            setTextColor(Color.parseColor(getCurrentSlot().colorHex))
        }
        refreshTargetOverlays()
    }

    private fun refreshTargetOverlays() {
        pointOverlays.forEach { it.remove() }
        pointOverlays.clear()

        val curSlot = getCurrentSlot()
        for (point in curSlot.points) {
            val overlay = PointTargetOverlay(
                context = context,
                windowManager = windowManager,
                point = point,
                colorHex = curSlot.colorHex,
                onPositionUpdated = {
                    saveCurrentState()
                },
                onPointClicked = { clickedPoint ->
                    showPointConfigDialog(clickedPoint)
                }
            )
            pointOverlays.add(overlay)
            overlay.show()
        }
    }

    private fun saveCurrentState() {
        PresetStorage.saveSlots(context, slots)
    }

    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        buttonsContainer?.visibility = if (isCollapsed) View.GONE else View.VISIBLE
        toggleCollapseBtn?.text = if (isCollapsed) "▼" else "▲"
    }

    private fun createButton(iconText: String, hexColor: String, onClick: () -> Unit): Button {
        val density = context.resources.displayMetrics.density
        val btnSize = (38 * density).toInt()

        return Button(context).apply {
            this.text = iconText
            this.textSize = 12f
            this.setTextColor(Color.WHITE)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor(hexColor))
                cornerRadius = 14f
            }
            background = bg
            setPadding(0, 0, 0, 0)
            val layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                setMargins(0, 3, 0, 3)
            }
            this.layoutParams = layoutParams
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
                else -> false
            }
        }
    }

    private fun addNewPointToCurrentSlot() {
        val curSlot = getCurrentSlot()
        val nextId = (curSlot.points.maxOfOrNull { it.id } ?: 0) + 1
        val newPoint = ClickPoint(
            id = nextId,
            xRatio = 0.5f,
            yRatio = 0.4f + (curSlot.points.size * 0.08f).coerceAtMost(0.4f),
            actionType = if (activeSlotIndex == 0) ActionType.HOLD else if (activeSlotIndex == 1) ActionType.TAP else ActionType.MULTI_TAP,
            holdDurationMs = 15000L,
            delayAfterMs = 300L,
            repeatCount = 5
        )
        curSlot.points.add(newPoint)
        saveCurrentState()

        val overlay = PointTargetOverlay(
            context = context,
            windowManager = windowManager,
            point = newPoint,
            colorHex = curSlot.colorHex,
            onPositionUpdated = { saveCurrentState() },
            onPointClicked = { clickedPoint -> showPointConfigDialog(clickedPoint) }
        )
        pointOverlays.add(overlay)
        overlay.show()
    }

    private fun removeLastPointFromCurrentSlot() {
        val curSlot = getCurrentSlot()
        if (curSlot.points.isNotEmpty()) {
            curSlot.points.removeAt(curSlot.points.size - 1)
            saveCurrentState()
            if (pointOverlays.isNotEmpty()) {
                val lastOverlay = pointOverlays.removeAt(pointOverlays.size - 1)
                lastOverlay.remove()
            }
        }
    }

    fun handleConfigurationChanged(newConfig: Configuration) {
        pointOverlays.forEach { it.updateScreenOrientation() }
    }

    // 포인트 개별 설정 다이얼로그 (동작 방식: 꾹 누르기 / 단발 탭 / 연타 선택 가능)
    private fun showPointConfigDialog(point: ClickPoint) {
        val paramsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val scrollView = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        // 동작 방식 선택 라디오 버튼
        val typeLabel = TextView(context).apply {
            text = "📌 동작 종류"
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }

        val rbHold = RadioButton(context).apply { text = "꾹 누르기 (홀드 - 지정 초 유지)"; id = View.generateViewId() }
        val rbTap = RadioButton(context).apply { text = "단발 탭 (빠른 클릭 1회)"; id = View.generateViewId() }
        val rbMultiTap = RadioButton(context).apply { text = "연타 (지정 횟수 빠른 연타)"; id = View.generateViewId() }

        radioGroup.addView(rbHold)
        radioGroup.addView(rbTap)
        radioGroup.addView(rbMultiTap)

        // 1. 홀드 시간
        val holdLabel = TextView(context).apply {
            text = "① 꾹 누르고 있을 시간 (초)"
            textSize = 13f
            setPadding(0, 12, 0, 0)
        }
        val holdInput = EditText(context).apply {
            setText((point.holdDurationMs / 1000.0).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        // 2. 연타 횟수
        val countLabel = TextView(context).apply {
            text = "② 연타 횟수 (회)"
            textSize = 13f
            setPadding(0, 12, 0, 0)
        }
        val countInput = EditText(context).apply {
            setText(point.repeatCount.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        // 3. 딜레이
        val delayLabel = TextView(context).apply {
            text = "③ 뗀 후 대기 시간 (초)"
            textSize = 13f
            setPadding(0, 12, 0, 0)
        }
        val delayInput = EditText(context).apply {
            setText((point.delayAfterMs / 1000.0).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        // 동작에 따라 불필요한 입력칸 숨김/표시 처리
        fun updateInputVisibility(actionType: ActionType) {
            when (actionType) {
                ActionType.HOLD -> {
                    holdLabel.visibility = View.VISIBLE
                    holdInput.visibility = View.VISIBLE
                    countLabel.visibility = View.GONE
                    countInput.visibility = View.GONE
                    delayLabel.text = "② 뗀 후 다음 누르기까지 대기 (초)"
                }
                ActionType.TAP -> {
                    holdLabel.visibility = View.GONE
                    holdInput.visibility = View.GONE
                    countLabel.visibility = View.GONE
                    countInput.visibility = View.GONE
                    delayLabel.text = "① 탭 후 다음 동작까지 대기 (초)"
                }
                ActionType.MULTI_TAP -> {
                    holdLabel.visibility = View.GONE
                    holdInput.visibility = View.GONE
                    countLabel.visibility = View.VISIBLE
                    countInput.visibility = View.VISIBLE
                    delayLabel.text = "② 연타 사이 간격/대기 (초)"
                }
            }
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val type = when (checkedId) {
                rbHold.id -> ActionType.HOLD
                rbTap.id -> ActionType.TAP
                rbMultiTap.id -> ActionType.MULTI_TAP
                else -> ActionType.HOLD
            }
            updateInputVisibility(type)
        }

        when (point.actionType) {
            ActionType.HOLD -> radioGroup.check(rbHold.id)
            ActionType.TAP -> radioGroup.check(rbTap.id)
            ActionType.MULTI_TAP -> radioGroup.check(rbMultiTap.id)
        }
        updateInputVisibility(point.actionType)

        layout.addView(typeLabel)
        layout.addView(radioGroup)
        layout.addView(holdLabel)
        layout.addView(holdInput)
        layout.addView(countLabel)
        layout.addView(countInput)
        layout.addView(delayLabel)
        layout.addView(delayInput)

        scrollView.addView(layout)

        val dialog = AlertDialog.Builder(context)
            .setTitle("포인트 #${point.id} 동작 설정")
            .setView(scrollView)
            .setPositiveButton("저장") { _, _ ->
                val selectedType = when (radioGroup.checkedRadioButtonId) {
                    rbHold.id -> ActionType.HOLD
                    rbTap.id -> ActionType.TAP
                    rbMultiTap.id -> ActionType.MULTI_TAP
                    else -> ActionType.HOLD
                }
                point.actionType = selectedType
                val holdSec = holdInput.text.toString().toDoubleOrNull() ?: 15.0
                val delaySec = delayInput.text.toString().toDoubleOrNull() ?: 0.3
                point.holdDurationMs = (holdSec * 1000).toLong().coerceAtLeast(50L)
                point.delayAfterMs = (delaySec * 1000).toLong().coerceAtLeast(50L)
                point.repeatCount = countInput.text.toString().toIntOrNull() ?: 1

                saveCurrentState()
                pointOverlays.find { it.point.id == point.id }?.updateLabel()
                Toast.makeText(context, "포인트 #${point.id} 설정 저장됨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .create()

        dialog.window?.setType(paramsType)
        dialog.show()
    }

    // 슬롯 전체 설정 다이얼로그 (버튼 이름, 루프 대기시간)
    private fun showSlotConfigDialog(slot: ButtonSlot) {
        val paramsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val scrollView = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val nameLabel = TextView(context).apply { text = "버튼 이름 / 메모:"; textSize = 13f }
        val nameInput = EditText(context).apply { setText(slot.name) }

        val loopInfoLabel = TextView(context).apply {
            text = "⚡ 동작 모드: ♾️ 무한 반복 (중지 누를 때까지 계속 실행)"
            textSize = 13f
            setTextColor(Color.parseColor("#4CAF50"))
            setPadding(0, 16, 0, 8)
        }

        val loopDelayLabel = TextView(context).apply {
            text = "한 사이클 끝난 후 다음 반복까지 대기 (초):"
            textSize = 13f
            setPadding(0, 10, 0, 0)
        }
        val loopDelayInput = EditText(context).apply {
            setText((slot.loopDelayMs / 1000.0).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        layout.addView(nameLabel)
        layout.addView(nameInput)
        layout.addView(loopInfoLabel)
        layout.addView(loopDelayLabel)
        layout.addView(loopDelayInput)

        scrollView.addView(layout)

        val dialog = AlertDialog.Builder(context)
            .setTitle("[버튼 ${slot.slotId}] 설정")
            .setView(scrollView)
            .setPositiveButton("저장") { _, _ ->
                slot.name = nameInput.text.toString().trim()
                val loopDelaySec = loopDelayInput.text.toString().toDoubleOrNull() ?: 0.0
                slot.loopDelayMs = (loopDelaySec * 1000).toLong()

                saveCurrentState()
                Toast.makeText(context, "${slot.name} 저장 완료", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .create()

        dialog.window?.setType(paramsType)
        dialog.show()
    }

    private fun toggleSlotPlay(slotIndex: Int) {
        val service = AutoClickAccessibilityService.instance
        if (service == null) {
            Toast.makeText(context, "접근성 서비스를 먼저 켜주세요!", Toast.LENGTH_SHORT).show()
            return
        }

        val runningSlotId = service.getRunningSlotId()
        val targetSlot = slots[slotIndex]

        if (runningSlotId == targetSlot.slotId) {
            // 현재 실행 중인 동일 슬롯이면 중지
            service.stopClicking { running, activeId ->
                updatePlayStates(running, activeId)
            }
        } else {
            // 다른 슬롯 실행 또는 새로 실행
            if (targetSlot.points.isEmpty()) {
                selectSlot(slotIndex)
                Toast.makeText(context, "${targetSlot.slotId}번 슬롯에 ➕ 버튼으로 포인트를 먼저 추가해주세요.", Toast.LENGTH_SHORT).show()
                return
            }

            selectSlot(slotIndex)
            service.startSlotExecution(
                slot = targetSlot,
                onStatusChange = { running, activeId ->
                    updatePlayStates(running, activeId)
                }
            )
            updatePlayStates(true, targetSlot.slotId)
        }
    }

    private fun updatePlayStates(running: Boolean, activeSlotId: Int?) {
        for (i in 0 until slotPlayButtons.size) {
            val btn = slotPlayButtons[i]
            val slot = slots.getOrNull(i) ?: continue
            if (running && activeSlotId == slot.slotId) {
                btn.text = "■${i + 1}"
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#E91E63")) // 실행 중 핑크/레드
                    cornerRadius = 14f
                }
                btn.background = bg
            } else {
                btn.text = "▶${i + 1}"
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor(slot.colorHex))
                    cornerRadius = 14f
                }
                btn.background = bg
            }
        }
    }

    fun stopAll() {
        AutoClickAccessibilityService.instance?.stopClicking()
        pointOverlays.forEach { it.remove() }
        pointOverlays.clear()
        controlMenuView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
            }
            controlMenuView = null
        }
    }
}

