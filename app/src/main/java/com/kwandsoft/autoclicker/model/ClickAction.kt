package com.kwandsoft.autoclicker.model

enum class ActionType {
    HOLD,       // 꾹 누르기
    TAP,
    MULTI_TAP
}

data class ClickPoint(
    var id: Int = 1,
    var xRatio: Float = 0.5f,
    var yRatio: Float = 0.5f,
    var rawX: Float = 0f,
    var rawY: Float = 0f
)


data class ButtonSlot(
    val slotId: Int,                   // 1: 15s, 2: 10s, 3: 5s
    var name: String = when (slotId) {
        1 -> "15초 홀드"
        2 -> "10초 홀드"
        3 -> "5초 홀드"
        else -> "홀드"
    },
    var holdDurationMs: Long = when (slotId) {
        1 -> 15000L
        2 -> 10000L
        3 -> 5000L
        else -> 5000L
    },
    var delayAfterMs: Long = 150L,
    var colorHex: String = when (slotId) {

        1 -> "#E91E63" // 핑크/레드
        2 -> "#2196F3" // 파랑
        3 -> "#4CAF50" // 녹색
        else -> "#9C27B0"
    }
)


