package com.kwandsoft.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.kwandsoft.autoclicker.model.ActionTrigger
import com.kwandsoft.autoclicker.model.ActionType
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

    fun startSlotExecution(
        slot: ButtonSlot,
        onStatusChange: ((running: Boolean, slotId: Int?) -> Unit)? = null
    ) {
        if (slot.points.isEmpty()) return

        stopClicking()

        val pointsSnapshot = slot.points.map { it.copy() }
        val loopDelayMs = slot.loopDelayMs
        val slotId = slot.slotId

        isRunning.set(true)
        runningSlotId = slotId
        onStatusChange?.invoke(true, slotId)

        executionJob = serviceScope.launch {
            try {
                // 무조건 무한 반복 (중지 버튼 누를 때까지 멈추지 않고 계속 실행)
                while (isActive && isRunning.get()) {
                    for (point in pointsSnapshot) {
                        if (!isActive || !isRunning.get()) break

                        val displayMetrics = resources.displayMetrics
                        val screenW = displayMetrics.widthPixels
                        val screenH = displayMetrics.heightPixels
                        val targetX = (point.xRatio * screenW).coerceIn(10f, screenW - 10f)
                        val targetY = (point.yRatio * screenH).coerceIn(10f, screenH - 10f)

                        when (point.actionType) {
                            ActionType.HOLD -> {
                                val totalDuration = point.holdDurationMs.coerceAtLeast(50L)
                                executeHoldGesture(targetX, targetY, totalDuration)
                            }
                            ActionType.TAP -> {
                                executeHoldGesture(targetX, targetY, 50L)
                            }
                            ActionType.MULTI_TAP -> {
                                val repeat = point.repeatCount.coerceAtLeast(1)
                                for (r in 0 until repeat) {
                                    if (!isActive || !isRunning.get()) break
                                    executeHoldGesture(targetX, targetY, 50L)
                                    if (r < repeat - 1 && point.delayAfterMs > 0) {
                                        delay(point.delayAfterMs)
                                    }
                                }
                            }
                        }

                        if (!isActive || !isRunning.get()) break

                        if (point.delayAfterMs > 0 && point.actionType != ActionType.MULTI_TAP) {
                            delay(point.delayAfterMs)
                        }
                    }
                    if (loopDelayMs > 0 && isActive && isRunning.get()) {
                        delay(loopDelayMs)
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Slot $slotId sequence cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error during slot execution", e)
            } finally {
                isRunning.set(false)
                runningSlotId = null
                withContext(Dispatchers.Main) {
                    onStatusChange?.invoke(false, null)
                }
            }
        }
    }

    fun stopClicking(onStatusChange: ((running: Boolean, slotId: Int?) -> Unit)? = null) {
        isRunning.set(false)
        runningSlotId = null
        executionJob?.cancel()
        executionJob = null
        onStatusChange?.invoke(false, null)
    }

    private var realtimePressJob: Job? = null
    private val isRealtimePressing = AtomicBoolean(false)

    fun startRealtimePress(xRatio: Float, yRatio: Float) {
        stopRealtimePress()
        isRealtimePressing.set(true)

        realtimePressJob = serviceScope.launch {
            while (isActive && isRealtimePressing.get()) {
                val displayMetrics = resources.displayMetrics
                val screenW = displayMetrics.widthPixels
                val screenH = displayMetrics.heightPixels
                val targetX = (xRatio * screenW).coerceIn(10f, screenW - 10f)
                val targetY = (yRatio * screenH).coerceIn(10f, screenH - 10f)

                // 250ms 단위 연속 스트로크로 끊김 없는 실시간 홀드 유지
                executeHoldGesture(targetX, targetY, 250L)
            }
        }
    }

    fun stopRealtimePress() {
        isRealtimePressing.set(false)
        realtimePressJob?.cancel()
        realtimePressJob = null
    }

    fun instantTap(xRatio: Float, yRatio: Float) {
        serviceScope.launch {
            val displayMetrics = resources.displayMetrics
            val screenW = displayMetrics.widthPixels
            val screenH = displayMetrics.heightPixels
            val targetX = (xRatio * screenW).coerceIn(10f, screenW - 10f)
            val targetY = (yRatio * screenH).coerceIn(10f, screenH - 10f)
            executeHoldGesture(targetX, targetY, 50L)
        }
    }

    private suspend fun executeHoldGesture(x: Float, y: Float, durationMs: Long) {
        val safeDuration = durationMs.coerceAtLeast(50L)
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        suspendCoroutine<Boolean> { cont ->
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
