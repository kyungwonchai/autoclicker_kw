package com.kwandsoft.autoclicker.model

enum class ActionType {
    HOLD,       // 꾹 누르기 (초 단위 지정)
    TAP,        // 단발 탭 (빠른 클릭)
    MULTI_TAP   // 연타 (반복 횟수 + 간격)
}

enum class ActionTrigger {
    INFINITE,   // 무한 반복 (중지 버튼 누를 때까지)
    ONCE,       // 1회 실행 후 자동 종료
    COUNT       // 지정된 횟수만큼 반복
}

data class ClickPoint(
    var id: Int,
    var xRatio: Float = 0.5f,
    var yRatio: Float = 0.5f,
    var actionType: ActionType = ActionType.HOLD,
    var holdDurationMs: Long = 15000L, // 기본 15초 꾹 누르기
    var delayAfterMs: Long = 300L,     // 기본 0.3초 뗀 후 대기
    var repeatCount: Int = 1           // 연타 횟수
)

data class ButtonSlot(
    val slotId: Int,                   // 1, 2, 3
    var name: String = "동작 $slotId",
    var colorHex: String = when (slotId) {
        1 -> "#4CAF50" // 녹색
        2 -> "#2196F3" // 파랑
        3 -> "#FF9800" // 주황
        else -> "#9C27B0"
    },
    var triggerMode: ActionTrigger = ActionTrigger.INFINITE,
    var loopRepeatCount: Int = 5,
    var loopDelayMs: Long = 0L,
    val points: MutableList<ClickPoint> = mutableListOf()
)

