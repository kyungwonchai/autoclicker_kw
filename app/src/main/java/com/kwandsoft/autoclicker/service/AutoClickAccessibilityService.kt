package com.kwandsoft.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.kwandsoft.autoclicker.model.ButtonSlot
import com.kwandsoft.autoclicker.model.ClickPoint
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AutoClickAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var executionJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    private var runningSlotId: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "AutoClickService"
        var instance: AutoClickAccessibilityService? = null
            private set

        val isServiceConnected: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AccessibilityService connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        stopClicking()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopClicking()
        serviceScope.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopClicking()
    }

    fun isClicking(): Boolean = isRunning.get()
    fun getRunningSlotId(): Int? = if (isRunning.get()) runningSlotId else null

    private var monitorJob: Job? = null

    fun startSlotExecution(
        slot: ButtonSlot,
        point: ClickPoint,
        onStatusChange: ((running: Boolean, slotId: Int?) -> Unit)? = null
    ) {
        stopClicking()

        val holdDurationMs = slot.holdDurationMs
        val delayAfterMs = slot.delayAfterMs
        val slotId = slot.slotId
        val targetPoint = point.copy()

        isRunning.set(true)
        runningSlotId = slotId
        onStatusChange?.invoke(true, slotId)

        val displayMetrics = resources.displayMetrics
        val screenW = displayMetrics.widthPixels
        val screenH = displayMetrics.heightPixels

        currentActiveSlot = slot
        currentActivePoint = targetPoint

        // 1. [독립 백그라운드 모니터] 캐릭키움 모드일 때만 화면 감지 동작!
        // (일반 모드에서는 오탐이나 제스처 취소, 오클릭 없이 순수하게 정해진 초 동안 꾹 누르기만 100% 보장)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isGrowthModeEnabled.get()) {
            startMonitoring(slot, targetPoint)
        }

        // 2. [기본 루프] 
        // 일반 모드: 지정된 위치(🎯)를 사용자가 설정한 초 동안 끊김 없이 꾹 누르기(Hold) 정직하게 반복
        // 캐릭키움 모드: 던전 내부일 때 공격 꾹 누르기 지속 (마을에서는 퀘스트/대화 이동 대기)
        executionJob = serviceScope.launch {
            try {
                while (isActive && isRunning.get()) {
                    if (isGrowthModeEnabled.get() && !isInDungeonState.get()) {
                        // 마을 상태에서는 🎯 공격 누르기를 하지 않고 대기 (모니터가 퀘스트/대화 처리)
                        delay(300L)
                        continue
                    }

                    val targetX = if (targetPoint.rawX > 0f) {
                        targetPoint.rawX.coerceIn(10f, screenW - 10f)
                    } else {
                        (targetPoint.xRatio * screenW).coerceIn(10f, screenW - 10f)
                    }
                    val targetY = if (targetPoint.rawY > 0f) {
                        targetPoint.rawY.coerceIn(10f, screenH - 10f)
                    } else {
                        (targetPoint.yRatio * screenH).coerceIn(10f, screenH - 10f)
                    }

                    Log.d(TAG, "Starting continuous hold for $holdDurationMs ms at ($targetX, $targetY)")

                    val path = Path().apply { moveTo(targetX, targetY) }
                    val stroke = GestureDescription.StrokeDescription(path, 0L, holdDurationMs)
                    val gesture = GestureDescription.Builder().addStroke(stroke).build()

                    isHoldingAttack.set(true)
                    try {
                        dispatchGestureSuspendResult(gesture)
                    } finally {
                        isHoldingAttack.set(false)
                    }

                    if (!isActive || !isRunning.get()) break

                    if (delayAfterMs > 0) {
                        delay(delayAfterMs)
                    } else {
                        delay(50L)
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Slot $slotId execution cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error during slot execution", e)
            } finally {
                isHoldingAttack.set(false)
                withContext(NonCancellable) {
                    stopClicking(onStatusChange)
                }
            }
        }
    }





    private var lastEntranceTriggerTime = 0L
    private var lastPotionTime = 0L
    private val isGrowthModeEnabled = AtomicBoolean(false)
    private val isInDungeonState = AtomicBoolean(false)
    private val isHoldingAttack = AtomicBoolean(false)
    private var currentActiveSlot: ButtonSlot? = null
    private var currentActivePoint: ClickPoint? = null

    fun setGrowthMode(enabled: Boolean) {
        isGrowthModeEnabled.set(enabled)
        Log.d(TAG, "🌱 캐릭키움 모드 상태 변경: $enabled")
        if (!enabled) {
            // 캐릭키움 꺼지면 모니터 작업 즉시 중단 (일반 모드 100% 순수 홀드 보장)
            monitorJob?.cancel()
            monitorJob = null
        } else if (isRunning.get() && monitorJob == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val slot = currentActiveSlot
            val point = currentActivePoint
            if (slot != null && point != null) {
                startMonitoring(slot, point)
            }
        }
    }

    private fun startMonitoring(slot: ButtonSlot, point: ClickPoint) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        monitorJob?.cancel()
        val displayMetrics = resources.displayMetrics
        val screenW = displayMetrics.widthPixels
        val screenH = displayMetrics.heightPixels
        monitorJob = serviceScope.launch {
            while (isActive && isRunning.get() && isGrowthModeEnabled.get()) {
                try {
                    checkAndHandleScreenState(screenW, screenH, slot, point)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in real-time monitor", e)
                }
                delay(300L) // 0.3초 주기 실시간 감지
            }
        }
    }

    fun isGrowthMode(): Boolean = isGrowthModeEnabled.get()

    private var lastGrowthActionTime = 0L
    private var lastTownQuestTime = 0L

    private val isDispatching = AtomicBoolean(false)

    private suspend fun checkAndHandleScreenState(
        screenW: Int,
        screenH: Int,
        slot: ButtonSlot,
        targetPoint: ClickPoint
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val bitmap = captureScreenshotSuspend() ?: return
            
            // 1. 화면이 마을인지 던전인지 엄격히 판별
            val isTown = checkTownScreenInBitmap(bitmap)
            val isMovingInTown = if (isTown) checkMovingInTownInBitmap(bitmap) else false

            // 던전 내부 판별 (마을이 아니고 + 미니맵 핀이나 보스가 있을 때)
            val inDungeon = !isTown && (checkDungeonEntranceInBitmap(bitmap) || checkBossDevilInBitmap(bitmap) || checkMiniMapPinInBitmap(bitmap))
            isInDungeonState.set(inDungeon)

            // 던전 내부(inDungeon)일 때만 포션/힐 사용! (마을 오터치 완전 차단)
            if (inDungeon) {
                checkAndHandleLowHp(bitmap, screenW, screenH)
            }

            // [우선순위 상태 감지]
            // 1. 던전 내부 아이템 줍기 (던전일 때만)
            val isPickupHand = if (inDungeon) checkItemPickupInBitmap(bitmap) else false
            // 2. 던전 클리어 메뉴 (보스 클리어 후)
            val isRetryVisible = if (!isPickupHand && !isTown) checkRetryButtonInBitmap(bitmap) else false
            // 3. 던전 초입
            val isEntrance = if (inDungeon && !isPickupHand && !isRetryVisible) checkDungeonEntranceInBitmap(bitmap) else false

            // [캐릭키움 모드 전용 상태 감지]
            // 대화 스킵 (마을/던전 공통)
            val isSkipDialog = if (isGrowthModeEnabled.get() && !isPickupHand) checkSkipDialogInBitmap(bitmap) else false
            // 완료 확인 팝업
            val isConfirmPopup = if (isGrowthModeEnabled.get() && !isPickupHand && !isSkipDialog) checkConfirmPopupInBitmap(bitmap) else false
            // 퀘스트 선택 / 보고 팝업 ([보고], [수락])
            val isQuestSelectPopup = if (isGrowthModeEnabled.get() && !isPickupHand && !isSkipDialog && !isConfirmPopup) checkQuestSelectPopupInBitmap(bitmap) else false
            // 던전 입장 / 전투시작 버튼 ([입장])
            val isBattleStart = if (isGrowthModeEnabled.get() && !isPickupHand && !isSkipDialog && !isConfirmPopup && !isQuestSelectPopup) checkBattleStartInBitmap(bitmap) else false
            // 마을 퀘스트 1순위: 마을 상태(isTown)이고 대화/확인/퀘스트팝업/전투시작 팝업이 없고, '이동 중'이 아닐 때만 터치!
            val isQuestAvailable = if (isGrowthModeEnabled.get() && isTown && !isMovingInTown && !isSkipDialog && !isConfirmPopup && !isQuestSelectPopup && !isBattleStart) checkQuestAuraInBitmap(bitmap) else false
            
            if (isPickupHand) {
                bitmap.recycle()
                Log.d(TAG, "🖐️ [아이템 줍기 손모양] 감지됨! 줍기 2회 탭 실행")
                val pickupX = screenW * 0.825f
                val pickupY = screenH * 0.825f

                isDispatching.set(true)
                try {
                    for (i in 1..2) {
                        if (!isRunning.get()) break
                        val tapPath = Path().apply { moveTo(pickupX, pickupY) }
                        val tapStroke = GestureDescription.StrokeDescription(tapPath, 0L, 40L)
                        val tapGesture = GestureDescription.Builder().addStroke(tapStroke).build()
                        dispatchGestureSuspendResult(tapGesture)

                        if (!isRunning.get()) break
                        delay(120L)
                    }
                } finally {
                    isDispatching.set(false)
                }
            } else if (isSkipDialog) {
                bitmap.recycle()
                val now = System.currentTimeMillis()
                if (now - lastGrowthActionTime > 800L) {
                    lastGrowthActionTime = now
                    Log.d(TAG, "🌱 [캐릭키움] 건너뛰기 ✕ 감지! 대화 스킵 클릭")
                    val skipX = screenW * 0.920f
                    val skipY = screenH * 0.055f
                    tapSingle(skipX, skipY, 50L)
                }
            } else if (isConfirmPopup) {
                bitmap.recycle()
                val now = System.currentTimeMillis()
                if (now - lastGrowthActionTime > 1200L) {
                    lastGrowthActionTime = now
                    Log.d(TAG, "🌱 [캐릭키움] 중앙 완료/확인/이동 팝업 감지! 확인/이동(56%) 클릭")
                    val confX = screenW * 0.560f
                    val confY = screenH * 0.630f
                    tapSingle(confX, confY, 50L)
                }
            } else if (isQuestSelectPopup) {
                bitmap.recycle()
                val now = System.currentTimeMillis()
                if (now - lastGrowthActionTime > 1200L) {
                    lastGrowthActionTime = now
                    Log.d(TAG, "🌱 [캐릭키움] NPC 퀘스트 선택/보고 팝업 감지! 최상단 에픽 [보고/수락] 버튼 클릭")
                    val bogoX = screenW * 0.655f
                    val bogoY = screenH * 0.355f
                    tapSingle(bogoX, bogoY, 50L)
                }
            } else if (isBattleStart) {
                bitmap.recycle()
                val now = System.currentTimeMillis()
                if (now - lastGrowthActionTime > 1200L) {
                    lastGrowthActionTime = now
                    Log.d(TAG, "🌱 [캐릭키움] 던전 [입장/전투시작] 버튼 감지! 클릭")
                    val batX = screenW * 0.790f
                    val batY = screenH * 0.912f
                    tapSingle(batX, batY, 60L)
                }
            } else if (isQuestAvailable) {
                bitmap.recycle()
                val now = System.currentTimeMillis()
                // 퀘스트 1회 터치 후 3.5초간 재클릭 방지하고 대기
                if (now - lastTownQuestTime > 3500L) {
                    lastTownQuestTime = now
                    Log.d(TAG, "🌱 [캐릭키움 1순위] 우상단 최상단 퀘스트(노란 아우라 박스) 1회 클릭 후 이동 대기")
                    val questX = screenW * 0.840f
                    val questY = screenH * 0.200f
                    tapSingle(questX, questY, 50L)
                }
            } else if (isRetryVisible) {
                bitmap.recycle()
                val now = System.currentTimeMillis()
                Log.d(TAG, "🎯 [던전 재도전하기/클리어 메뉴] 감지됨!")
                if (isGrowthModeEnabled.get() && now - lastGrowthActionTime > 2500L) {
                    lastGrowthActionTime = now
                    // 던전 클리어 시 우상단에 뜬 [에픽 다음 던전] 퀘스트 버튼 우선 터치
                    Log.d(TAG, "🌱 [캐릭키움] 던전 클리어 메뉴에서 다음 에픽 퀘스트(2652, 201) 클릭")
                    val nextQuestX = screenW * 0.850f
                    val nextQuestY = screenH * 0.140f
                    tapSingle(nextQuestX, nextQuestY, 50L)
                }
            } else if (isEntrance) {
                bitmap.recycle()
                val now = System.currentTimeMillis()
                if (now - lastEntranceTriggerTime > 30000L) {
                    lastEntranceTriggerTime = now
                    Log.d(TAG, "🚩 [던전 초입(첫 방)] 감지됨! 멍때림 방지 3회 탭 시작")

                    val targetX = if (targetPoint.rawX > 0f) {
                        targetPoint.rawX.coerceIn(10f, screenW - 10f)
                    } else {
                        (targetPoint.xRatio * screenW).coerceIn(10f, screenW - 10f)
                    }
                    val targetY = if (targetPoint.rawY > 0f) {
                        targetPoint.rawY.coerceIn(10f, screenH - 10f)
                    } else {
                        (targetPoint.yRatio * screenH).coerceIn(10f, screenH - 10f)
                    }

                    for (i in 1..3) {
                        if (!isRunning.get()) break
                        Log.d(TAG, "Entrance wake-up tap $i/3 at ($targetX, $targetY)")
                        val tapPath = Path().apply { moveTo(targetX, targetY) }
                        val tapStroke = GestureDescription.StrokeDescription(tapPath, 0L, 50L)
                        val tapGesture = GestureDescription.Builder().addStroke(tapStroke).build()
                        dispatchGestureSuspendResult(tapGesture)

                        if (!isRunning.get()) break
                        delay(200L)
                    }
                    Log.d(TAG, "Entrance wake-up finished. Continuing slot execution...")
                }
            } else {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking screen state", e)
        }
    }

    private suspend fun checkAndHandleLowHp(bitmap: Bitmap, screenW: Int, screenH: Int) {
        val now = System.currentTimeMillis()
        if (now - lastPotionTime < 3000L) return // 포션 3초 쿨다운

        val w = bitmap.width
        val h = bitmap.height
        val y = (h * 0.022f).toInt().coerceIn(0, h - 1)

        // HP 바 오른쪽 절반(50% ~ 100% 구간)에 빨간색 픽셀이 있는지 확인
        var rightHalfRedCount = 0
        val startX = (w * 0.15f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.22f).toInt().coerceIn(0, w)

        for (x in startX until endX step 2) {
            val pixel = bitmap.getPixel(x, y)
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            if (r > 180 && g < 50 && b < 50) {
                rightHalfRedCount++
            }
        }

        // HP가 50% 이하인 경우 (오른쪽 절반 빨간 픽셀이 거의 없음)
        if (rightHalfRedCount < 10) {
            lastPotionTime = now
            Log.d(TAG, "⚠️ [HP 50% 이하 감지됨!] 힐(손모양) 및 포션 자동 사용")

            isDispatching.set(true)
            try {
                // 손모양(힐): x=47.2%, y=91.0%
                val handX = screenW * 0.472f
                val handY = screenH * 0.910f
                val handTap = GestureDescription.Builder().addStroke(
                    GestureDescription.StrokeDescription(Path().apply { moveTo(handX, handY) }, 0L, 40L)
                ).build()
                dispatchGestureSuspendResult(handTap)

                delay(80L)

                // 물약/포션: x=52.3%, y=91.0%
                val potionX = screenW * 0.523f
                val potionY = screenH * 0.910f
                val potionTap = GestureDescription.Builder().addStroke(
                    GestureDescription.StrokeDescription(Path().apply { moveTo(potionX, potionY) }, 0L, 40L)
                ).build()
                dispatchGestureSuspendResult(potionTap)
            } finally {
                isDispatching.set(false)
            }
        }
    }

    private fun checkBossDevilInBitmap(bitmap: Bitmap): Boolean {
        // 던전 내부(미니맵 파란 핀)가 아니면 마을의 [레이드] 빨간 글씨 등으로 인한 보스 오탐 100% 방지!
        if (!checkMiniMapPinInBitmap(bitmap)) return false

        val w = bitmap.width
        val h = bitmap.height

        // 미니맵 보스방 악마 아이콘 영역 (x: 90% ~ 95%, y: 8% ~ 16%)
        val startX = (w * 0.900f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.950f).toInt().coerceIn(0, w)
        val startY = (h * 0.080f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.160f).toInt().coerceIn(0, h)

        var totalSampled = 0
        var redPixels = 0

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                totalSampled++
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // 악마 붉은색 검출 (R > 175, G < 65, B < 65)
                if (r > 175 && g < 65 && b < 65) {
                    redPixels++
                }
            }
        }

        if (totalSampled == 0) return false
        val ratio = redPixels.toFloat() / totalSampled.toFloat()
        // 미니맵 우측 보스 악마 아이콘 비율 1% 이상이면 감지
        return ratio > 0.010f
    }

    private fun checkDungeonEntranceInBitmap(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        // 1. 미니맵 첫 번째 방 위치에 파란색 캐릭터 핀(위치 마커)이 있는지 확인 (x: 89.5% ~ 91.0%, y: 10.5% ~ 13.5%)
        val pinStartX = (w * 0.895f).toInt().coerceIn(0, w - 1)
        val pinEndX = (w * 0.910f).toInt().coerceIn(0, w)
        val pinStartY = (h * 0.105f).toInt().coerceIn(0, h - 1)
        val pinEndY = (h * 0.135f).toInt().coerceIn(0, h)

        var pinSampled = 0
        var blueMarkerPixels = 0

        for (y in pinStartY until pinEndY step 2) {
            for (x in pinStartX until pinEndX step 2) {
                pinSampled++
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if (b > 160 && r < 80 && g < 180 && (b - r) > 80) {
                    blueMarkerPixels++
                }
            }
        }

        if (pinSampled == 0) return false
        val pinRatio = blueMarkerPixels.toFloat() / pinSampled.toFloat()
        val hasBluePin = pinRatio > 0.05f

        if (!hasBluePin) return false

        // 2. 핀의 왼쪽(x: 87.0% ~ 88.5%, y: 11.0% ~ 13.0%)에 지나온 이전 방(갈색 블록)이 없어야 '첫 방(초입)'임!
        val leftStartX = (w * 0.870f).toInt().coerceIn(0, w - 1)
        val leftEndX = (w * 0.885f).toInt().coerceIn(0, w)
        val leftStartY = (h * 0.110f).toInt().coerceIn(0, h - 1)
        val leftEndY = (h * 0.130f).toInt().coerceIn(0, h)

        var leftSampled = 0
        var leftRoomPixels = 0

        for (y in leftStartY until leftEndY step 2) {
            for (x in leftStartX until leftEndX step 2) {
                leftSampled++
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if (r in 90..200 && g in 70..170 && b in 30..110) {
                    leftRoomPixels++
                }
            }
        }

        if (leftSampled == 0) return false
        val leftRoomRatio = leftRoomPixels.toFloat() / leftSampled.toFloat()

        // 좌측에 지나온 방이 15% 미만이면 첫 번째 초입 방!
        return leftRoomRatio < 0.15f
    }

    private fun checkTownScreenInBitmap(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        // 마을 하단 메뉴바 [스케줄러, 상점, 캐릭터, 모험, 인벤토리] 영역 (x: 65% ~ 95%, y: 92% ~ 98%)
        val startX = (w * 0.65f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.95f).toInt().coerceIn(0, w)
        val startY = (h * 0.92f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.98f).toInt().coerceIn(0, h)

        var total = 0
        var whiteText = 0

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                total++
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                if (r > 180 && g > 180 && b > 180) {
                    whiteText++
                }
            }
        }

        if (total == 0) return false
        val ratio = whiteText.toFloat() / total.toFloat()
        return ratio > 0.04f
    }

    private fun checkMovingInTownInBitmap(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        // 퀘스트 박스 우측 '이동 중' 순수 노란색 텍스트 영역 (x: 84% ~ 92%, y: 16% ~ 22%)
        val startX = (w * 0.84f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.92f).toInt().coerceIn(0, w)
        val startY = (h * 0.16f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.22f).toInt().coerceIn(0, h)

        var total = 0
        var yellowCount = 0

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                total++
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                // 이동 중 텍스트의 고유한 밝은 노란색 (R > 210, G > 190, B < 70)
                if (r > 210 && g > 190 && b < 70) {
                    yellowCount++
                }
            }
        }

        if (total == 0) return false
        val ratio = yellowCount.toFloat() / total.toFloat()
        return ratio > 0.025f
    }

    private fun checkMiniMapPinInBitmap(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        // 미니맵 전체 영역 (x: 85% ~ 96%, y: 8% ~ 16%)
        val startX = (w * 0.85f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.96f).toInt().coerceIn(0, w)
        val startY = (h * 0.08f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.16f).toInt().coerceIn(0, h)

        var total = 0
        var blueMarkerPixels = 0

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                total++
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                if (b > 140 && r < 120 && (b - r) > 40) {
                    blueMarkerPixels++
                }
            }
        }

        if (total == 0) return false
        val ratio = blueMarkerPixels.toFloat() / total.toFloat()
        return ratio > 0.005f
    }

    private var lastPickupTime = 0L

    private fun checkItemPickupInBitmap(bitmap: Bitmap): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPickupTime < 2500L) return false // 줍기 완료 후 2.5초 쿨다운

        val w = bitmap.width
        val h = bitmap.height

        // 우하단 공격 버튼 전체 영역 (x: 78% ~ 85%, y: 75% ~ 90%)
        val startX = (w * 0.78f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.85f).toInt().coerceIn(0, w)
        val startY = (h * 0.75f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.90f).toInt().coerceIn(0, h)

        var totalSampled = 0
        var darkBgPixels = 0
        var yelPixels = 0

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                totalSampled++
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // 1. 손가락 사이 흑갈색 배경 (손모양일 땐 30% 이상, 대검일 땐 3% 미만)
                if (r < 70 && g < 55 && b < 45) {
                    darkBgPixels++
                }
                // 2. 손/화살표 노란색 픽셀
                if (r > 180 && g > 160 && b in 50..190 && (r - b) > 35) {
                    yelPixels++
                }
            }
        }

        if (totalSampled == 0) return false
        val darkRatio = darkBgPixels.toFloat() / totalSampled.toFloat()
        val yelRatio = yelPixels.toFloat() / totalSampled.toFloat()

        // 손모양일 때: Dark >= 25%, Yellow >= 12% (대검일 땐 Dark가 3% 수준으로 절대 안 걸림)
        val isPickup = darkRatio >= 0.25f && yelRatio >= 0.12f
        if (isPickup) {
            lastPickupTime = now
        }
        return isPickup
    }

    private fun checkRetryButtonInBitmap(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        // 던전 클리어 시 우측 메뉴 패널 (x: 78% ~ 95%, y: 20% ~ 45%)
        val startX = (w * 0.78f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.95f).toInt().coerceIn(0, w)
        val startY = (h * 0.20f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.45f).toInt().coerceIn(0, h)

        var totalSampled = 0
        var darkBgPixels = 0

        for (y in startY until endY step 3) {
            for (x in startX until endX step 3) {
                totalSampled++
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // 클리어 메뉴 패널 고유의 짙은 흑갈색 배경 (R < 60, G < 50, B < 45)
                if (r < 60 && g < 50 && b < 45) {
                    darkBgPixels++
                }
            }
        }

        if (totalSampled == 0) return false
        val bgRatio = darkBgPixels.toFloat() / totalSampled.toFloat()
        // 클리어 메뉴 패널이 화면에 떠 있으면 짙은 배경 비율이 60% 이상임!
        return bgRatio > 0.60f
    }

    private suspend fun tapSingle(x: Float, y: Float, durationMs: Long = 50L) {
        isDispatching.set(true)
        try {
            val tapPath = Path().apply { moveTo(x, y) }
            val tapStroke = GestureDescription.StrokeDescription(tapPath, 0L, durationMs)
            val tapGesture = GestureDescription.Builder().addStroke(tapStroke).build()
            dispatchGestureSuspendResult(tapGesture)
        } finally {
            isDispatching.set(false)
        }
    }

    private fun checkSkipDialogInBitmap(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        // 1. 하단 대화창(짙은 흑갈색 배경 박스) 존재 여부 필수 확인 (x: 20% ~ 80%, y: 75% ~ 90%)
        val dialogStartX = (w * 0.20f).toInt().coerceIn(0, w - 1)
        val dialogEndX = (w * 0.80f).toInt().coerceIn(0, w)
        val dialogStartY = (h * 0.75f).toInt().coerceIn(0, h - 1)
        val dialogEndY = (h * 0.90f).toInt().coerceIn(0, h)

        var dialogTotal = 0
        var darkDialogPixels = 0

        for (y in dialogStartY until dialogEndY step 3) {
            for (x in dialogStartX until dialogEndX step 3) {
                dialogTotal++
                val p = bitmap.getPixel(x, y)
                if (Color.red(p) < 45 && Color.green(p) < 40 && Color.blue(p) < 35) {
                    darkDialogPixels++
                }
            }
        }

        if (dialogTotal == 0) return false
        val darkDialogRatio = darkDialogPixels.toFloat() / dialogTotal.toFloat()
        // 하단에 짙은 대화창이 50% 이상 없으면 마을/전투 화면이므로 건너뛰기 아님! (마을 우상단 햄버거 메뉴 오탐 100% 방지)
        if (darkDialogRatio < 0.50f) return false

        // 2. 우상단 건너뛰기 ✕ 흰색 글자 확인 (x: 88% ~ 96%, y: 3% ~ 8%)
        val startX = (w * 0.88f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.96f).toInt().coerceIn(0, w)
        val startY = (h * 0.03f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.08f).toInt().coerceIn(0, h)

        var total = 0
        var whitePixels = 0

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                total++
                val p = bitmap.getPixel(x, y)
                if (Color.red(p) > 215 && Color.green(p) > 215 && Color.blue(p) > 215) {
                    whitePixels++
                }
            }
        }

        if (total == 0) return false
        val ratio = whitePixels.toFloat() / total.toFloat()
        return ratio > 0.035f
    }

    private fun checkConfirmPopupInBitmap(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        // 중앙 확인 버튼 (x: 45% ~ 55%, y: 60% ~ 67%)
        val startX = (w * 0.45f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.55f).toInt().coerceIn(0, w)
        val startY = (h * 0.60f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.67f).toInt().coerceIn(0, h)

        var total = 0
        var goldPixels = 0

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                total++
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (r in 160..240 && g in 100..180 && b < 50) {
                    goldPixels++
                }
            }
        }

        if (total == 0) return false
        val ratio = goldPixels.toFloat() / total.toFloat()
        return ratio > 0.20f
    }

    private fun checkQuestSelectPopupInBitmap(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        // 1. 상단 퀘스트 선택 창 타이틀 영역 (x: 45% ~ 55%, y: 18% ~ 24%)
        // 2. 최상단 에픽 퀘스트 우측 주황색 [보고] / [수락] 버튼 (x: 61% ~ 70%, y: 31% ~ 39%)
        val startX = (w * 0.61f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.70f).toInt().coerceIn(0, w)
        val startY = (h * 0.31f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.39f).toInt().coerceIn(0, h)

        var total = 0
        var orangePixels = 0

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                total++
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                if (r > 180 && g in 110..175 && b < 60) {
                    orangePixels++
                }
            }
        }

        if (total == 0) return false
        val ratio = orangePixels.toFloat() / total.toFloat()
        return ratio > 0.15f
    }

    private fun checkBattleStartInBitmap(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        // 우하단 던전 선택창의 [입장] / [전투시작] 황금색 버튼 영역 (x: 72% ~ 86%, y: 85% ~ 96%)
        val startX = (w * 0.72f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.86f).toInt().coerceIn(0, w)
        val startY = (h * 0.85f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.96f).toInt().coerceIn(0, h)

        var total = 0
        var goldPixels = 0

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                total++
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                // [입장] 버튼의 고유 황금/주황색 (R: 150~255, G: 90~195, B < 70)
                if (r in 150..255 && g in 90..195 && b < 70) {
                    goldPixels++
                }
            }
        }

        if (total == 0) return false
        val ratio = goldPixels.toFloat() / total.toFloat()
        return ratio > 0.08f
    }

    private fun checkQuestAuraInBitmap(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        // 우상단 최상단 퀘스트 텍스트/아우라 영역 (x: 75% ~ 90%, y: 15% ~ 27%)
        val startX = (w * 0.75f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.90f).toInt().coerceIn(0, w)
        val startY = (h * 0.15f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.27f).toInt().coerceIn(0, h)

        var total = 0
        var goldPixels = 0

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                total++
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                // [에픽] 퀘스트 노란 글씨 및 황금 테두리 (R > 170, G > 135, B < 100)
                if (r > 170 && g > 135 && b < 100) {
                    goldPixels++
                }
            }
        }

        if (total == 0) return false
        val ratio = goldPixels.toFloat() / total.toFloat()
        return ratio > 0.015f
    }

    private suspend fun captureScreenshotSuspend(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCoroutine { cont ->
            mainHandler.post {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainHandler::post,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                screenshotResult.hardwareBuffer,
                                screenshotResult.colorSpace
                            )
                            val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBitmap?.recycle()
                            screenshotResult.hardwareBuffer.close()
                            cont.resume(softwareBitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "takeScreenshot failed: $errorCode")
                            cont.resume(null)
                        }
                    }
                )
            }
        }
    }

    enum class GestureResult { COMPLETED, CANCELLED, FAILED }

    private suspend fun dispatchGestureSuspendResult(gesture: GestureDescription): GestureResult {
        return suspendCoroutine { cont ->
            mainHandler.post {
                val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        cont.resume(GestureResult.COMPLETED)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        cont.resume(GestureResult.CANCELLED)
                    }
                }, null)

                if (!dispatched) {
                    cont.resume(GestureResult.FAILED)
                }
            }
        }
    }


    fun stopClicking(onStatusChange: ((running: Boolean, slotId: Int?) -> Unit)? = null) {
        val wasRunning = isRunning.getAndSet(false)
        runningSlotId = null
        currentActiveSlot = null
        currentActivePoint = null
        executionJob?.cancel()
        executionJob = null
        monitorJob?.cancel()
        monitorJob = null

        if (wasRunning) {
            // 실행 중인 제스처 즉각 중단을 위해 1ms 취소 스트로크 디스패치
            serviceScope.launch {
                try {
                    val cancelPath = Path().apply { moveTo(1f, 1f) }
                    val cancelStroke = GestureDescription.StrokeDescription(cancelPath, 0L, 1L)
                    val cancelGesture = GestureDescription.Builder().addStroke(cancelStroke).build()
                    dispatchGesture(cancelGesture, null, null)
                } catch (e: Exception) {
                }
            }
        }

        if (onStatusChange != null) {
            mainHandler.post {
                onStatusChange.invoke(false, null)
            }
        }
    }


    private suspend fun dispatchGestureSuspend(gesture: GestureDescription): Boolean {
        return suspendCoroutine { cont ->
            mainHandler.post {
                val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        cont.resume(false)
                    }
                }, null)

                if (!dispatched) {
                    cont.resume(false)
                }
            }
        }
    }
}



