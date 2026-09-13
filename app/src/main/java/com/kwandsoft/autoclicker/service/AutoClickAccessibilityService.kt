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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        isInDungeonState.set(true) // 5번 클릭 즉시 꾹 누르기(Hold) 즉각 시작!
        hasDoneDungeonInitialClicks.set(false)
        growthState = GrowthState.DUNGEON_COMBAT
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
                var moveDirectionIndex = 0
                while (isActive && isRunning.get()) {
                    if (isGrowthModeEnabled.get()) {
                        // 던전 전투 중이 아니거나 대화 스킵/팝업 처리 중일 때는 공격 홀드 정지 (마을/던전선택 오클릭 원천 차단)
                        if (!isInDungeonState.get() || growthState == GrowthState.DIALOG_PROGRESS) {
                            delay(100L)
                            continue
                        }
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

                    // 1. 던전 처음 진입 시에만 5회 클릭 후 진입
                    if (isGrowthModeEnabled.get() && !hasDoneDungeonInitialClicks.get()) {
                        hasDoneDungeonInitialClicks.set(true)
                        Log.d(TAG, "⚔️ [던전 진입] 던전 초입 5회 클릭 실행")
                        for (i in 1..5) {
                            if (!isActive || !isRunning.get() || !isInDungeonState.get()) break
                            tapSingle(targetX, targetY, 40L)
                            delay(80L)
                        }
                        if (!isActive || !isRunning.get() || !isInDungeonState.get()) continue
                    }

                    // 2. 그 다음부터는 텀을 두고 꾹 누르기(5초 홀드 공격)를 이어서 계속 반복
                    Log.d(TAG, "Starting continuous hold for $holdDurationMs ms at ($targetX, $targetY)")

                    isHoldingAttack.set(true)
                    try {
                        val path = Path().apply {
                            moveTo(targetX, targetY)
                            lineTo(targetX + 1f, targetY + 1f)
                        }
                        val stroke = GestureDescription.StrokeDescription(path, 0L, holdDurationMs)
                        val gesture = GestureDescription.Builder().addStroke(stroke).build()
                        dispatchGestureSuspendResult(gesture)
                    } finally {
                        isHoldingAttack.set(false)
                    }

                    if (!isActive || !isRunning.get()) break

                    // 3. 홀드 후 텀(간격 150ms)을 두고 이어서 반복
                    val termMs = if (delayAfterMs > 0 && !isGrowthModeEnabled.get()) delayAfterMs else 150L
                    delay(termMs)
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





    // 던전 전투 시 시전할 스킬 위치 목록 (기본 공격 🎯의 좌측 및 좌상단 원형 스킬들만 집중 타격, 우측 점프/회피 제외)
    private val combatSkills = listOf(
        Pair(0.710f, 0.890f), // 하단 1 (공격 바로 좌측)
        Pair(0.650f, 0.890f), // 하단 2
        Pair(0.585f, 0.890f), // 하단 3
        Pair(0.710f, 0.740f), // 중단 1 (공격 좌상단)
        Pair(0.650f, 0.740f), // 중단 2
        Pair(0.585f, 0.740f), // 중단 3
        Pair(0.760f, 0.590f), // 상단 1 (공격 바로 위)
        Pair(0.690f, 0.590f)  // 상단 2
    )
    private var nextSkillIndex = 0

    private var lastEntranceTriggerTime = 0L
    private var lastPotionTime = 0L
    private val isGrowthModeEnabled = AtomicBoolean(true)
    private val isInDungeonState = AtomicBoolean(false)
    private val hasDoneDungeonInitialClicks = AtomicBoolean(false)
    private val isHoldingAttack = AtomicBoolean(false)
    private var currentActiveSlot: ButtonSlot? = null
    private var currentActivePoint: ClickPoint? = null

    fun setGrowthMode(enabled: Boolean) {
        isGrowthModeEnabled.set(enabled)
        Log.d(TAG, "🌱 캐릭키움 모드 상태 변경: $enabled")
        growthState = GrowthState.TOWN_IDLE
        townMoveStartTime = 0L
        lastDialogActionTime = 0L
        if (!enabled) {
            // 캐릭키움 꺼지면 모니터 작업 즉시 중단 (일반 모드 100% 순수 홀드 보장)
            monitorJob?.cancel()
            monitorJob = null
        } else if (isRunning.get() && (monitorJob == null || monitorJob?.isActive != true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
                delay(200L) // 0.2초 주기 안정적 실시간 감지 (안드로이드 캡처 레이트리밋 방지)
            }
        }
    }

    fun isGrowthMode(): Boolean = isGrowthModeEnabled.get()

    enum class GrowthState {
        TOWN_IDLE,        // 마을 대기: 퀘스트/안내손가락 클릭 가능
        TOWN_MOVING,      // 이동 중: 퀘스트 재클릭 절대 금지! 대화/팝업/입장 나올 때까지 100% 대기
        DIALOG_PROGRESS,  // 대화/팝업 처리 중: [건너뛰기], [보고], [수락], [확인] 순차 처리
        DUNGEON_COMBAT,   // 던전 전투: 공격 꾹 누르기 + 스킬 + 방 이동
        DUNGEON_CLEAR     // 던전 클리어: 보상 획득 + 다음 퀘스트 클릭
    }

    private var growthState = GrowthState.TOWN_IDLE
    private var townMoveStartTime = 0L
    private var lastDialogActionTime = 0L
    private var lastGrowthActionTime = 0L
    private var lastTownQuestTime = 0L
    private var lastClearQuestClickTime = 0L
    private var lastMapCardClickTime = 0L
    private var lastEpicQuestClickTime = 0L
    private var hasHeldForClearLoot = false

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
            val now = System.currentTimeMillis()

            // 0순위: 장비 획득 [장착] 팝업 (어떤 상태에서든 즉시 0순위로 장착 터치)
            val isEquipPopup = checkEquipPopupInBitmap(bitmap)
            if (isEquipPopup) {
                bitmap.recycle()
                if (now - lastGrowthActionTime > 200L) {
                    lastGrowthActionTime = now
                    Log.d(TAG, "🛡️ [0순위: 장비 장착] [장착] 버튼 클릭")
                    tapSingle(screenW * 0.905f, screenH * 0.680f, 50L)
                }
                return
            }

            // 1순위: 던전 클리어 메뉴 (보스 처치 후 우측 세로 패널: 마을로 가기, 다음 퀘스트 등)
            // 전투가 끝났으므로 공격 홀드를 즉각 중단하고 최우선으로 다음 에픽 퀘스트 클릭!
            val isDungeonClear = checkRetryButtonInBitmap(bitmap)
            if (isDungeonClear) {
                bitmap.recycle()
                growthState = GrowthState.DUNGEON_CLEAR
                isInDungeonState.set(false) // 전투 즉시 중단 및 홀드 차단
                hasDoneDungeonInitialClicks.set(false)

                if (now - lastClearQuestClickTime > 400L) {
                    lastClearQuestClickTime = now
                    Log.d(TAG, "🌱 [1순위: 던전 클리어] 최상단 다음 에픽 퀘스트(18.3%) 즉시 클릭")
                    val nextQuestX = screenW * 0.865f
                    val nextQuestY = screenH * 0.183f
                    tapSingle(nextQuestX, nextQuestY, 60L)
                }
                return
            }

            // 2순위: 던전 선택 화면 (반짝이는 사각형 지도 맵 카드 및 [입장] 버튼)
            val isDungeonSelect = checkDungeonSelectScreenInBitmap(bitmap)
            if (isDungeonSelect) {
                isInDungeonState.set(false)
                hasDoneDungeonInitialClicks.set(false)

                // 1) 반짝이는 퀘스트 타겟 맵 카드 감지 시 즉시 클릭!
                val targetMapCard = findQuestMapCardInBitmap(bitmap)
                if (targetMapCard != null) {
                    if (now - lastMapCardClickTime > 600L) {
                        lastMapCardClickTime = now
                        growthState = GrowthState.DIALOG_PROGRESS
                        lastDialogActionTime = now
                        val (mcX, mcY) = targetMapCard
                        bitmap.recycle()
                        Log.d(TAG, "🗺️ [2순위: 던전 선택] 반짝이는 퀘스트 맵 카드 감지! ($mcX, $mcY) 즉시 클릭")
                        tapSingle(mcX, mcY, 50L)
                        return
                    }
                }

                // 2) 우하단 [입장/전투시작] 버튼 감지 시 즉시 클릭!
                val battleStartCoord = findBattleStartButtonInBitmap(bitmap)
                if (battleStartCoord != null) {
                    if (now - lastGrowthActionTime > 300L) {
                        lastGrowthActionTime = now
                        growthState = GrowthState.DIALOG_PROGRESS
                        lastDialogActionTime = now
                        val (batX, batY) = battleStartCoord
                        bitmap.recycle()
                        Log.d(TAG, "🌱 [2순위: 던전 선택] [입장/전투시작] 버튼 감지! ($batX, $batY) 클릭")
                        tapSingle(batX, batY, 50L)
                        return
                    }
                }

                bitmap.recycle()
                return // 던전 선택 화면에서는 마을 퀘스트 헛클릭 일체 차단!
            }

            // 3순위: [던전 전투 중] 화면에 공격 버튼(황금검) 또는 조이스틱 감지 시 100% 전투 중!
            // 대화 스킵/팝업/마을 퀘스트 등 화면 상단 터치(미니맵 오클릭 등)를 일체 금지하고 공격 홀드 100% 보장!
            val isCombat = checkCombatControlsInBitmap(bitmap)
            if (isCombat) {
                isInDungeonState.set(true)
                growthState = GrowthState.DUNGEON_COMBAT

                val isPickupHand = checkItemPickupInBitmap(bitmap)
                if (isPickupHand) {
                    bitmap.recycle()
                    Log.d(TAG, "🖐️ [아이템 줍기] 손모양 감지됨! 줍기 탭")
                    val pickupX = screenW * 0.825f
                    val pickupY = screenH * 0.825f
                    tapSingle(pickupX, pickupY, 40L)
                    return
                }
                bitmap.recycle()
                return // 전투 중에는 화면 상단 미니맵 등 헛클릭 원천 차단!
            }

            // 이하 비전투 화면 (전투 컨트롤 부재)
            isInDungeonState.set(false)

            // 4순위: 대화 스킵 (✕) (건너뛰기)
            val isSkipDialog = checkSkipDialogInBitmap(bitmap)
            if (isSkipDialog) {
                bitmap.recycle()
                growthState = GrowthState.DIALOG_PROGRESS
                lastDialogActionTime = now
                if (now - lastGrowthActionTime > 200L) {
                    lastGrowthActionTime = now
                    Log.d(TAG, "🌱 [4순위: 대화 스킵] 건너뛰기 ✕ 클릭")
                    val skipX = screenW * 0.915f
                    val skipY = screenH * 0.060f
                    tapSingle(skipX, skipY, 50L)
                }
                return
            }

            // 5순위: 완료/확인/이동 팝업 (화면 중앙 모달)
            val isConfirmPopup = checkConfirmPopupInBitmap(bitmap)
            if (isConfirmPopup) {
                bitmap.recycle()
                growthState = GrowthState.DIALOG_PROGRESS
                lastDialogActionTime = now
                if (now - lastGrowthActionTime > 200L) {
                    lastGrowthActionTime = now
                    Log.d(TAG, "🌱 [5순위: 완료/확인/이동 팝업] 중앙 확인(56%) 클릭")
                    val confX = screenW * 0.560f
                    val confY = screenH * 0.630f
                    tapSingle(confX, confY, 50L)
                }
                return
            }

            // 6순위: NPC 퀘스트 선택 / [보고]/[수락] (목록 최상단 에픽 퀘스트)
            val isQuestSelectPopup = checkQuestSelectPopupInBitmap(bitmap)
            if (isQuestSelectPopup) {
                bitmap.recycle()
                growthState = GrowthState.DIALOG_PROGRESS
                lastDialogActionTime = now
                if (now - lastGrowthActionTime > 200L) {
                    lastGrowthActionTime = now
                    Log.d(TAG, "🌱 [6순위: NPC 퀘스트] 최상단 에픽 [보고/수락] 버튼 클릭")
                    val bogoX = screenW * 0.655f
                    val bogoY = screenH * 0.355f
                    tapSingle(bogoX, bogoY, 50L)
                }
                return
            }

            // 7순위: 마을 우상단 [에픽] 퀘스트 배너 (마을 길찾기 / 이동)
            val hasEpicQuest = checkEpicBannerInBitmap(bitmap) || checkQuestAuraInBitmap(bitmap)
            if (hasEpicQuest) {
                bitmap.recycle()
                if (now - lastEpicQuestClickTime > 3500L) {
                    lastEpicQuestClickTime = now
                    growthState = GrowthState.TOWN_MOVING
                    townMoveStartTime = now
                    lastDialogActionTime = now
                    Log.d(TAG, "⭐ [7순위: 우상단 에픽 퀘스트] 클릭 후 이동 대기 (86.5%, 18.3%)")
                    val epicX = screenW * 0.865f
                    val epicY = screenH * 0.183f
                    tapSingle(epicX, epicY, 50L)
                }
                return
            }

            // 8순위: 가이드 손가락 / 노란 원형 링 안내 (👆) (튜토리얼/안내 돌파)
            val pointingTip = checkPointingGuideInBitmap(bitmap)
            if (pointingTip != null) {
                bitmap.recycle()
                growthState = GrowthState.DIALOG_PROGRESS
                lastDialogActionTime = now
                if (now - lastGrowthActionTime > 200L) {
                    lastGrowthActionTime = now
                    val (gx, gy) = pointingTip
                    Log.d(TAG, "👆 [8순위: 안내 손가락/원형 링] 안내 위치 클릭 ($gx, $gy)")
                    tapSingle(gx, gy, 50L)
                }
                return
            }

            bitmap.recycle()

            // 대화/팝업/던전클리어 종료 후 마을 대기(TOWN_IDLE) 전이
            if (growthState == GrowthState.DIALOG_PROGRESS || growthState == GrowthState.DUNGEON_CLEAR) {
                if (now - lastDialogActionTime > 600L) {
                    Log.d(TAG, "🌿 [캐릭키움 상태전이] 대화/팝업 종료 확인 -> 마을 대기(TOWN_IDLE) 전이")
                    growthState = GrowthState.TOWN_IDLE
                    lastClearQuestClickTime = 0L
                    hasHeldForClearLoot = false
                }
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
        val w = bitmap.width
        val h = bitmap.height

        // 미니맵 보스방 악마 아이콘 영역 (x: 88% ~ 95%, y: 8% ~ 16%)
        val startX = (w * 0.880f).toInt().coerceIn(0, w - 1)
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

                // 악마 붉은색 검출 (R > 160, G < 65, B < 65)
                if (r > 160 && g < 65 && b < 65) {
                    redPixels++
                }
            }
        }

        if (totalSampled == 0) return false
        val ratio = redPixels.toFloat() / totalSampled.toFloat()
        // 미니맵 우측 보스 악마 아이콘 비율 1% 이상이면 보스방 감지!
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

        // 마을 우상단 미니맵 원형 지구본/레이더 버튼 (x: 95% ~ 98%, y: 2% ~ 7%)
        // 던전에서는 일시정지 버튼(회색/갈색)이 위치하며, 파란색 구체 레이더는 오직 마을에만 존재함!
        val startX = (w * 0.95f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.98f).toInt().coerceIn(0, w)
        val startY = (h * 0.02f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.07f).toInt().coerceIn(0, h)

        var total = 0
        var bluePixels = 0

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                total++
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                if (b > 140 && r < 60 && g in 50..130 && (b - r) > 80) {
                    bluePixels++
                }
            }
        }

        if (total == 0) return false
        val ratio = bluePixels.toFloat() / total.toFloat()
        return ratio > 0.02f
    }

    private fun checkEpicBannerInBitmap(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        // 우측 상단 퀘스트 패널 영역 (x: 78% ~ 95%, y: 10% ~ 26%)
        val startX = (w * 0.78f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.95f).toInt().coerceIn(0, w)
        val startY = (h * 0.10f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.26f).toInt().coerceIn(0, h)

        var goldPixels = 0
        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                // [에픽] 고유 황금/주황 텍스트
                if (r > 170 && g in 100..190 && b < 80 && (r - b) > 70) {
                    goldPixels++
                }
            }
        }
        return goldPixels > 60
    }

    private fun checkMiniMapPinInBitmap(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        // 우상단 거의 정사각형 모양의 던전 미니맵 영역 (x: 84% ~ 97%, y: 3% ~ 22%)
        val startX = (w * 0.84f).toInt().coerceIn(0, w - 1)
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
        // 실제 미니맵 핀은 0.005 ~ 0.040 범위 (0.040 초과는 웨스트코스트 등 마을 푸른 하늘 오탐 방지)
        return ratio in 0.005f..0.040f
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
        // 0. 던전 선택 화면이면 절대 던전 클리어 메뉴가 아님!
        if (checkDungeonSelectScreenInBitmap(bitmap)) return false

        val w = bitmap.width
        val h = bitmap.height

        // 1) 던전 클리어 시 우측 메뉴 패널의 [마을로 가기] 고유 파란색 텍스트 (x: 78% ~ 94%, y: 44% ~ 53%)
        val startX = (w * 0.78f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.94f).toInt().coerceIn(0, w)
        val startY = (h * 0.44f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.53f).toInt().coerceIn(0, h)

        var bluePixels = 0
        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // [마을로 가기] 하늘색/파란색 텍스트
                if (b > 110 && (b - r) > 30 && (b - g) > 10) {
                    bluePixels++
                }
            }
        }
        val hasBlueTown = bluePixels > 100

        // 2) 던전 클리어 메뉴 최상단 [에픽] 황금색 퀘스트 버튼 (x: 78% ~ 92%, y: 12% ~ 22%)
        val qStartX = (w * 0.78f).toInt().coerceIn(0, w - 1)
        val qEndX = (w * 0.92f).toInt().coerceIn(0, w)
        val qStartY = (h * 0.12f).toInt().coerceIn(0, h - 1)
        val qEndY = (h * 0.22f).toInt().coerceIn(0, h)

        var goldPixels = 0
        for (y in qStartY until qEndY step 2) {
            for (x in qStartX until qEndX step 2) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // 황금색/노란색 퀘스트 텍스트
                if (r > 160 && g > 110 && b < 85 && (r - b) > 60) {
                    goldPixels++
                }
            }
        }
        val hasGoldQuest = goldPixels > 150

        // 던전 클리어 메뉴에서는 파란색 [마을로 가기]와 황금색 [에픽]이 함께 존재함
        return hasBlueTown && hasGoldQuest
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

    private suspend fun holdSingle(x: Float, y: Float, durationMs: Long = 5000L) {
        isDispatching.set(true)
        try {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 1f, y + 1f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGestureSuspendResult(gesture)
        } finally {
            isDispatching.set(false)
        }
    }

    private suspend fun swipeSingle(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 500L) {
        isDispatching.set(true)
        try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGestureSuspendResult(gesture)
        } finally {
            isDispatching.set(false)
        }
    }

    private fun checkPointingGuideInBitmap(bitmap: Bitmap): Pair<Float, Float>? {
        val w = bitmap.width
        val h = bitmap.height

        val startX = (w * 0.12f).toInt()
        val endX = (w * 0.95f).toInt()
        val startY = (h * 0.06f).toInt()
        val endY = (h * 0.94f).toInt()

        val cellSize = 40
        val cols = (endX - startX) / cellSize + 1
        val rows = (endY - startY) / cellSize + 1
        val grid = IntArray(cols * rows)

        for (y in startY until endY step 4) {
            val gy = (y - startY) / cellSize
            for (x in startX until endX step 4) {
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                // 손가락 특유의 밝은 아이보리/연노랑 (R: 235~255, G: 210~255, B: 120~210, R-B: 30~105, |R-G| <= 35)
                if (r >= 235 && g >= 210 && b in 120..210 && (r - b) in 30..105 && kotlin.math.abs(r - g) <= 35) {
                    val gx = (x - startX) / cellSize
                    grid[gy * cols + gx]++
                }
            }
        }

        var bestCount = 0
        var bestGx = -1
        var bestGy = -1

        for (gy in 1 until rows - 1) {
            for (gx in 1 until cols - 1) {
                var sum = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        sum += grid[(gy + dy) * cols + (gx + dx)]
                    }
                }
                if (sum > bestCount) {
                    bestCount = sum
                    bestGx = gx
                    bestGy = gy
                }
            }
        }

        if (bestCount >= 380 && bestGx >= 0 && bestGy >= 0) {
            val centerPixelX = startX + bestGx * cellSize + cellSize / 2
            val centerPixelY = startY + bestGy * cellSize + cellSize / 2

            // 채팅창/넓은 팝업 방지: 손가락 가로 폭 검사 (40px ~ 170px 사이여야 손가락임)
            var maxWidth = 0
            for (y in (centerPixelY - 60)..(centerPixelY + 60) step 10) {
                if (y !in 0 until h) continue
                var minSpanX = w
                var maxSpanX = -1
                for (x in (centerPixelX - 160)..(centerPixelX + 160) step 4) {
                    if (x !in 0 until w) continue
                    val p = bitmap.getPixel(x, y)
                    val r = Color.red(p)
                    val g = Color.green(p)
                    val b = Color.blue(p)
                    if (r >= 235 && g >= 210 && b in 120..210 && (r - b) in 30..105 && kotlin.math.abs(r - g) <= 35) {
                        if (x < minSpanX) minSpanX = x
                        if (x > maxSpanX) maxSpanX = x
                    }
                }
                if (maxSpanX >= minSpanX) {
                    val spanW = maxSpanX - minSpanX
                    if (spanW > maxWidth) maxWidth = spanW
                }
            }

            if (maxWidth !in 35..180) {
                return null // 채팅창이나 넓은 팝업 박스 제외
            }

            // 손가락 끝(검지 tip) 위치 계산
            var minY = h
            val scanMinX = (centerPixelX - 100).coerceIn(0, w - 1)
            val scanMaxX = (centerPixelX + 100).coerceIn(0, w - 1)
            val scanMinY = (centerPixelY - 180).coerceIn(0, h - 1)
            val scanMaxY = (centerPixelY + 150).coerceIn(0, h - 1)

            for (y in scanMinY..scanMaxY step 2) {
                for (x in scanMinX..scanMaxX step 2) {
                    val p = bitmap.getPixel(x, y)
                    val r = Color.red(p)
                    val g = Color.green(p)
                    val b = Color.blue(p)
                    if (r >= 235 && g >= 210 && b in 120..210 && (r - b) in 30..105 && kotlin.math.abs(r - g) <= 35) {
                        if (y < minY) minY = y
                    }
                }
            }

            var tipXSum = 0L
            var tipYSum = 0L
            var tipCount = 0

            for (y in minY..(minY + 25).coerceAtMost(h - 1) step 2) {
                for (x in scanMinX..scanMaxX step 2) {
                    val p = bitmap.getPixel(x, y)
                    val r = Color.red(p)
                    val g = Color.green(p)
                    val b = Color.blue(p)
                    if (r >= 235 && g >= 210 && b in 120..210 && (r - b) in 30..105 && kotlin.math.abs(r - g) <= 35) {
                        tipXSum += x
                        tipYSum += y
                        tipCount++
                    }
                }
            }

            if (tipCount > 0) {
                val tipX = tipXSum.toFloat() / tipCount.toFloat()
                val tipY = tipYSum.toFloat() / tipCount.toFloat()

                var ringXSum = 0L
                var ringYSum = 0L
                var yellowRingPixels = 0
                val checkRadiusMinSq = 35 * 35
                val checkRadiusMaxSq = 130 * 130
                for (dy in -120..120 step 4) {
                    val py = (tipY.toInt() + dy).coerceIn(0, h - 1)
                    for (dx in -120..120 step 4) {
                        val distSq = dx * dx + dy * dy
                        if (distSq in checkRadiusMinSq..checkRadiusMaxSq) {
                            val px = (tipX.toInt() + dx).coerceIn(0, w - 1)
                            val p = bitmap.getPixel(px, py)
                            val r = Color.red(p)
                            val g = Color.green(p)
                            val b = Color.blue(p)
                            // 노란색/주황색 파동 원 테두리
                            if (r >= 210 && g >= 160 && b <= 120) {
                                yellowRingPixels++
                                ringXSum += px
                                ringYSum += py
                            }
                        }
                    }
                }

                // 노란색 링 픽셀이 최소 15개 이상 검출되어야 진짜 안내 손가락/동그라미로 최종 인정!
                if (yellowRingPixels >= 15) {
                    val ringCenterX = ringXSum.toFloat() / yellowRingPixels.toFloat()
                    val ringCenterY = ringYSum.toFloat() / yellowRingPixels.toFloat()
                    return Pair(ringCenterX, ringCenterY)
                }
            }
        }

        return null
    }

    private fun checkMovingInTownInBitmap(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        // 퀘스트 박스 하단 '이동 중' 텍스트 영역 (x: 82% ~ 94%, y: 19% ~ 25%)
        val startX = (w * 0.82f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.94f).toInt().coerceIn(0, w)
        val startY = (h * 0.19f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.25f).toInt().coerceIn(0, h)

        var total = 0
        var movingTextPixels = 0

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                total++
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                // 1) 흰색 텍스트 "이동 중" (R,G,B > 160)
                // 2) 노란색 텍스트 "이동 중" (R > 180, G > 160, B < 80)
                if ((r > 160 && g > 160 && b > 160) || (r > 180 && g > 160 && b < 80)) {
                    movingTextPixels++
                }
            }
        }

        if (total == 0) return false
        val ratio = movingTextPixels.toFloat() / total.toFloat()
        // 이동 중 텍스트가 표시되면 해당 영역에 3% 이상 및 150픽셀 이상 검출됨
        return ratio > 0.030f && movingTextPixels > 150
    }

    private fun checkSkipDialogInBitmap(bitmap: Bitmap): Boolean {
        if (checkCombatControlsInBitmap(bitmap)) return false

        val w = bitmap.width
        val h = bitmap.height

        // 1. 하단 대화창 또는 어두운 딤드(Dimmed) 영역 필수 확인 (x: 30% ~ 70%, y: 82% ~ 96%)
        val dStartX = (w * 0.30f).toInt().coerceIn(0, w - 1)
        val dEndX = (w * 0.70f).toInt().coerceIn(0, w)
        val dStartY = (h * 0.82f).toInt().coerceIn(0, h - 1)
        val dEndY = (h * 0.96f).toInt().coerceIn(0, h)

        var darkTotal = 0
        var darkPixels = 0
        for (y in dStartY until dEndY step 4) {
            for (x in dStartX until dEndX step 4) {
                darkTotal++
                val p = bitmap.getPixel(x, y)
                if (Color.red(p) < 65 && Color.green(p) < 65 && Color.blue(p) < 65) {
                    darkPixels++
                }
            }
        }
        if (darkTotal == 0 || darkPixels.toFloat() / darkTotal.toFloat() < 0.30f) {
            return false // 하단에 대화창 박스가 없으면 일반 화면이므로 스킵 버튼 오탐 차단!
        }

        // 2. 우상단 "건너뛰기 ✕" 흰색 글자 위치만 체크 (x: 86% ~ 97%, y: 3% ~ 9%)
        val startX = (w * 0.86f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.97f).toInt().coerceIn(0, w)
        val startY = (h * 0.03f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.09f).toInt().coerceIn(0, h)

        var total = 0
        var whitePixels = 0

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                total++
                val p = bitmap.getPixel(x, y)
                if (Color.red(p) > 210 && Color.green(p) > 210 && Color.blue(p) > 210) {
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

        // 1. 팝업창 본체의 짙은 흑갈색 모달 박스 존재 필수 확인 (x: 40% ~ 60%, y: 38% ~ 52%)
        // (웨스트코스트 선착장 등 나무 바닥 타일 오탐 100% 방지)
        val boxStartX = (w * 0.40f).toInt().coerceIn(0, w - 1)
        val boxEndX = (w * 0.60f).toInt().coerceIn(0, w)
        val boxStartY = (h * 0.38f).toInt().coerceIn(0, h - 1)
        val boxEndY = (h * 0.52f).toInt().coerceIn(0, h)

        var boxTotal = 0
        var darkBoxPixels = 0
        for (y in boxStartY until boxEndY step 3) {
            for (x in boxStartX until boxEndX step 3) {
                boxTotal++
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (r < 65 && g < 55 && b < 45) {
                    darkBoxPixels++
                }
            }
        }
        if (boxTotal == 0) return false
        val darkRatio = darkBoxPixels.toFloat() / boxTotal.toFloat()
        if (darkRatio < 0.40f) return false

        // 2. 중앙 확인 버튼 (x: 45% ~ 55%, y: 60% ~ 67%)
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
        return ratio > 0.15f
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

    private fun findBattleStartButtonInBitmap(bitmap: Bitmap): Pair<Float, Float>? {
        if (checkCombatControlsInBitmap(bitmap)) return null

        val w = bitmap.width
        val h = bitmap.height

        // 우하단 던전 선택창의 [입장] / [전투시작] 황금색 버튼 영역 (x: 82% ~ 98%, y: 80% ~ 98%)
        val startX = (w * 0.82f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.98f).toInt().coerceIn(0, w)
        val startY = (h * 0.80f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.98f).toInt().coerceIn(0, h)

        var total = 0
        var goldPixels = 0
        var sumX = 0L
        var sumY = 0L

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
                    sumX += x
                    sumY += y
                }
            }
        }

        if (total == 0) return null
        val ratio = goldPixels.toFloat() / total.toFloat()
        if (ratio > 0.08f && goldPixels > 250) {
            return Pair((sumX / goldPixels).toFloat(), (sumY / goldPixels).toFloat())
        }
        return null
    }

    private fun checkBattleStartInBitmap(bitmap: Bitmap): Boolean {
        return findBattleStartButtonInBitmap(bitmap) != null
    }

    private fun checkEquipPopupInBitmap(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        // 우하단 장비 [장착] 황금/주황 버튼 영역 (x: 89.0% ~ 92.0%, y: 66.5% ~ 69.5%)
        val ex = (w * 0.905f).toInt().coerceIn(0, w - 1)
        val ey = (h * 0.680f).toInt().coerceIn(0, h - 1)
        val p = bitmap.getPixel(ex, ey)
        val r = Color.red(p)
        val g = Color.green(p)
        val b = Color.blue(p)

        return (r in 150..230 && g in 90..160 && b < 60)
    }

    private fun checkDungeonMiniMapInBitmap(bitmap: Bitmap): Boolean {
        // 던전 클리어 메뉴가 떠 있는 경우 전투 상태 아님
        if (checkRetryButtonInBitmap(bitmap)) return false

        val w = bitmap.width
        val h = bitmap.height

        // 우상단 거의 정사각형 모양의 던전 미니맵 영역 (x: 84% ~ 97%, y: 3% ~ 22%)
        val startX = (w * 0.84f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.97f).toInt().coerceIn(0, w)
        val startY = (h * 0.03f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.22f).toInt().coerceIn(0, h)

        var darkPts = 0
        var total = 0

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                total++
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                // 던전 미니맵의 반투명 검은 배경 (R,G,B < 65)
                if (r < 65 && g < 65 && b < 65) {
                    darkPts++
                }
            }
        }

        // 던전 안에서는 우상단 정사각형 박스가 최소 30% 이상 어두운 반투명 검은색! (마을은 11% 미만)
        return total > 0 && (darkPts.toFloat() / total > 0.30f)
    }

    private fun checkCombatControlsInBitmap(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        // 1. 공격 버튼 영역 (x: 75% ~ 82%, y: 80% ~ 90%) - 황금색 검 아이콘
        val startX = (w * 0.75f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.82f).toInt().coerceIn(0, w)
        val startY = (h * 0.80f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.90f).toInt().coerceIn(0, h)
        var atkPts = 0
        for (y in startY until endY step 4) {
            for (x in startX until endX step 4) {
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (r > 120 && g > 90 && b < 80 && (r - b) > 40) {
                    atkPts++
                }
            }
        }
        if (atkPts > 80) return true

        // 2. 조이스틱 영역 (x: 14% ~ 20%, y: 66% ~ 77%) - 조이스틱 링
        val jStartX = (w * 0.14f).toInt().coerceIn(0, w - 1)
        val jEndX = (w * 0.20f).toInt().coerceIn(0, w)
        val jStartY = (h * 0.66f).toInt().coerceIn(0, h - 1)
        val jEndY = (h * 0.77f).toInt().coerceIn(0, h)
        var joyPts = 0
        for (y in jStartY until jEndY step 4) {
            for (x in jStartX until jEndX step 4) {
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (r in 60..150 && g in 50..130 && b in 40..110) {
                    joyPts++
                }
            }
        }
        return joyPts > 60
    }

    private fun checkDungeonSelectScreenInBitmap(bitmap: Bitmap): Boolean {
        // 1. 전투 컨트롤(공격 버튼 or 조이스틱) 감지 시 절대 던전 선택창 아님!
        if (checkCombatControlsInBitmap(bitmap)) return false

        // 2. 마을 레이더/미니맵 감지 시 던전 선택창 아님!
        if (checkTownScreenInBitmap(bitmap)) return false

        // 3. 우하단 [입장]/[전투시작] 버튼이 보이면 던전 선택창 판정
        if (checkBattleStartInBitmap(bitmap)) return true

        val w = bitmap.width
        val h = bitmap.height

        // 4. 좌상단 뒤로가기 화살표 '<' 및 던전선택 타이틀 (x: 4% ~ 8%, y: 2% ~ 6%)
        var backArrowPts = 0
        val bStartX = (w * 0.04f).toInt().coerceIn(0, w - 1)
        val bEndX = (w * 0.08f).toInt().coerceIn(0, w)
        val bStartY = (h * 0.02f).toInt().coerceIn(0, h - 1)
        val bEndY = (h * 0.06f).toInt().coerceIn(0, h)

        for (y in bStartY until bEndY step 2) {
            for (x in bStartX until bEndX step 2) {
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (r > 180 && g > 170 && b > 120) {
                    backArrowPts++
                }
            }
        }
        return backArrowPts > 20
    }

    private fun findQuestMapCardInBitmap(bitmap: Bitmap): Pair<Float, Float>? {
        if (!checkDungeonSelectScreenInBitmap(bitmap)) return null

        val w = bitmap.width
        val h = bitmap.height

        // 1. 깜박임(플래시) 애니메이션 감지: 카드가 밝은 노란색으로 점멸할 때
        val step = 6
        val gw = w / step
        val gh = h / step
        val startGY = (h * 0.15f / step).toInt().coerceIn(0, gh - 1)
        val endGY = (h * 0.85f / step).toInt().coerceIn(0, gh)
        val startGX = (w * 0.15f / step).toInt().coerceIn(0, gw - 1)
        val endGX = (w * 0.85f / step).toInt().coerceIn(0, gw)

        val cardBW = 55
        val cardBH = 23
        var maxScore = 0
        var bestFlashX = 0f
        var bestFlashY = 0f

        val grid = Array(gh) { IntArray(gw) }
        for (gy in startGY until endGY) {
            val py = gy * step
            for (gx in startGX until endGX) {
                val px = gx * step
                val p = bitmap.getPixel(px, py)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (r >= 200 && g >= 160 && b <= 110) {
                    grid[gy][gx] = 1
                }
            }
        }

        val searchLimitGY = (h * 0.80f / step).toInt() - cardBH
        val searchLimitGX = (w * 0.80f / step).toInt() - cardBW
        for (gy in startGY until searchLimitGY step 3) {
            for (gx in startGX until searchLimitGX step 4) {
                var s = 0
                for (dy in 0 until cardBH) {
                    val row = grid[gy + dy]
                    for (dx in 0 until cardBW) {
                        s += row[gx + dx]
                    }
                }
                if (s > maxScore) {
                    maxScore = s
                    bestFlashX = (gx * step + (cardBW * step) / 2).toFloat()
                    bestFlashY = (gy * step + (cardBH * step) / 2).toFloat()
                }
            }
        }

        if (maxScore >= 80) {
            Log.d(TAG, "🗺️ [던전 선택] 깜박이는 퀘스트 맵 카드 감지됨! 점수: $maxScore, 좌표: ($bestFlashX, $bestFlashY)")
            return Pair(bestFlashX, bestFlashY)
        }

        // 2. 황금 두루마리 엠블럼 뱃지 감지 (비점멸 정적 상태)
        val startY = (h * 0.15f).toInt()
        val endY = (h * 0.85f).toInt()
        val startX = (w * 0.20f).toInt()
        val endX = (w * 0.85f).toInt()

        val goldPoints = java.util.ArrayList<Pair<Int, Int>>()
        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (r >= 200 && g >= 170 && b <= 75) {
                    goldPoints.add(Pair(x, y))
                }
            }
        }

        if (goldPoints.isNotEmpty()) {
            val visited = java.util.HashSet<Pair<Int, Int>>()
            val goldSet = goldPoints.toHashSet()

            for (pt in goldPoints) {
                if (visited.contains(pt)) continue
                val cluster = java.util.ArrayList<Pair<Int, Int>>()
                val queue = java.util.ArrayDeque<Pair<Int, Int>>()
                queue.add(pt)
                visited.add(pt)

                while (queue.isNotEmpty()) {
                    val curr = queue.removeFirst()
                    cluster.add(curr)
                    for (dy in -2..2 step 2) {
                        for (dx in -2..2 step 2) {
                            val nxt = Pair(curr.first + dx, curr.second + dy)
                            if (goldSet.contains(nxt) && !visited.contains(nxt)) {
                                visited.add(nxt)
                                queue.add(nxt)
                            }
                        }
                    }
                }

                if (cluster.size in 60..200) {
                    var minX = Int.MAX_VALUE
                    var maxX = Int.MIN_VALUE
                    var minY = Int.MAX_VALUE
                    var maxY = Int.MIN_VALUE
                    var sumX = 0L
                    var sumY = 0L

                    for (p in cluster) {
                        if (p.first < minX) minX = p.first
                        if (p.first > maxX) maxX = p.first
                        if (p.second < minY) minY = p.second
                        if (p.second > maxY) maxY = p.second
                        sumX += p.first
                        sumY += p.second
                    }

                    val bwPx = maxX - minX + 1
                    val bhPx = maxY - minY + 1
                    val aspect = bwPx.toFloat() / Math.max(1, bhPx).toFloat()

                    if (aspect in 0.70f..2.20f) {
                        val avgX = (sumX / cluster.size).toFloat()
                        val avgY = (sumY / cluster.size).toFloat()
                        val cardX = avgX - w * 0.045f
                        val cardY = avgY + h * 0.038f
                        Log.d(TAG, "🗺️ [던전 선택] 퀘스트 엠블럼 뱃지 감지됨! 크기: ${cluster.size}, 뱃지: ($avgX, $avgY) -> 카드중심: ($cardX, $cardY)")
                        return Pair(cardX, cardY)
                    }
                }
            }
        }

        return null
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
        val t0 = System.currentTimeMillis()
        val result = withTimeoutOrNull(600L) {
            suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    try {
                        takeScreenshot(
                            Display.DEFAULT_DISPLAY,
                            mainHandler::post,
                            object : AccessibilityService.TakeScreenshotCallback {
                                override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                                    try {
                                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                            screenshotResult.hardwareBuffer,
                                            screenshotResult.colorSpace
                                        )
                                        val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                        hardwareBitmap?.recycle()
                                        screenshotResult.hardwareBuffer.close()
                                        if (cont.isActive) cont.resume(softwareBitmap)
                                    } catch (e: Exception) {
                                        if (cont.isActive) cont.resume(null)
                                    }
                                }

                                override fun onFailure(errorCode: Int) {
                                    if (cont.isActive) cont.resume(null)
                                }
                            }
                        )
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
        val elapsed = System.currentTimeMillis() - t0
        if (elapsed > 200L) Log.d(TAG, "📷 captureScreenshot took ${elapsed}ms")
        return result
    }

    enum class GestureResult { COMPLETED, CANCELLED, FAILED }

    private suspend fun dispatchGestureSuspendResult(gesture: GestureDescription): GestureResult {
        var maxDuration = 500L
        for (i in 0 until gesture.strokeCount) {
            val s = gesture.getStroke(i)
            if (s.duration > maxDuration) {
                maxDuration = s.duration
            }
        }
        return withTimeoutOrNull(maxDuration + 600L) {
            suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    try {
                        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(GestureResult.COMPLETED)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(GestureResult.CANCELLED)
                            }
                        }, null)

                        if (!dispatched) {
                            if (cont.isActive) cont.resume(GestureResult.FAILED)
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(GestureResult.FAILED)
                    }
                }
            }
        } ?: GestureResult.FAILED
    }


    fun stopClicking(onStatusChange: ((running: Boolean, slotId: Int?) -> Unit)? = null) {
        val wasRunning = isRunning.getAndSet(false)
        runningSlotId = null
        currentActiveSlot = null
        currentActivePoint = null
        hasDoneDungeonInitialClicks.set(false)
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



