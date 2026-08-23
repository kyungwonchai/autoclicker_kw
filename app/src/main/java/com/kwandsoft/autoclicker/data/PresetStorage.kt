package com.kwandsoft.autoclicker.data

import android.content.Context
import com.kwandsoft.autoclicker.model.ActionTrigger
import com.kwandsoft.autoclicker.model.ActionType
import com.kwandsoft.autoclicker.model.ButtonSlot
import com.kwandsoft.autoclicker.model.ClickPoint
import org.json.JSONArray
import org.json.JSONObject

object PresetStorage {
    private const val PREFS_NAME = "autoclicker_presets"
    private const val KEY_SLOTS = "saved_button_slots_json"
    private const val KEY_PRESETS = "saved_presets_json"

    private const val KEY_ACTIVE_SLOT = "saved_active_slot_index"
    private const val KEY_MENU_X = "saved_menu_x"
    private const val KEY_MENU_Y = "saved_menu_y"
    private const val KEY_DOKKAEBI = "saved_dokkaebi_config_json"

    fun saveDokkaebiConfig(
        context: Context,
        enabled: Boolean,
        touchLX: Float, touchLY: Float,
        touchRX: Float, touchRY: Float,
        targetLX: Float, targetLY: Float,
        targetRX: Float, targetRY: Float
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val obj = JSONObject().apply {
            put("enabled", enabled)
            put("touchLX", touchLX.toDouble())
            put("touchLY", touchLY.toDouble())
            put("touchRX", touchRX.toDouble())
            put("touchRY", touchRY.toDouble())
            put("targetLX", targetLX.toDouble())
            put("targetLY", targetLY.toDouble())
            put("targetRX", targetRX.toDouble())
            put("targetRY", targetRY.toDouble())
        }
        prefs.edit().putString(KEY_DOKKAEBI, obj.toString()).apply()
    }

    fun loadDokkaebiConfig(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_DOKKAEBI, null)
        return if (jsonStr != null) {
            try { JSONObject(jsonStr) } catch (e: Exception) { JSONObject() }
        } else {
            JSONObject()
        }
    }

    fun saveActiveSlot(context: Context, slotIndex: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_ACTIVE_SLOT, slotIndex).apply()
    }

    fun getActiveSlot(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_ACTIVE_SLOT, 0)
    }

    fun saveMenuPosition(context: Context, x: Int, y: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_MENU_X, x).putInt(KEY_MENU_Y, y).apply()
    }

    fun getMenuPosition(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val x = prefs.getInt(KEY_MENU_X, 20)
        val y = prefs.getInt(KEY_MENU_Y, 120)
        return Pair(x, y)
    }

    fun saveSlots(context: Context, slots: List<ButtonSlot>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (slot in slots) {
            val slotObj = JSONObject().apply {
                put("slotId", slot.slotId)
                put("name", slot.name)
                put("colorHex", slot.colorHex)
                put("triggerMode", slot.triggerMode.name)
                put("loopRepeatCount", slot.loopRepeatCount)
                put("loopDelayMs", slot.loopDelayMs)

                val pointsArr = JSONArray()
                for (p in slot.points) {
                    val pObj = JSONObject().apply {
                        put("id", p.id)
                        put("xRatio", p.xRatio.toDouble())
                        put("yRatio", p.yRatio.toDouble())
                        put("actionType", p.actionType.name)
                        put("holdDurationMs", p.holdDurationMs)
                        put("delayAfterMs", p.delayAfterMs)
                        put("repeatCount", p.repeatCount)
                    }
                    pointsArr.put(pObj)
                }
                put("points", pointsArr)
            }
            arr.put(slotObj)
        }
        prefs.edit().putString(KEY_SLOTS, arr.toString()).apply()
    }

    fun loadSlots(context: Context): List<ButtonSlot> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_SLOTS, null)
        if (jsonStr == null) {
            return listOf(
                ButtonSlot(slotId = 1, name = "1번 (5초 홀드)").apply {
                    points.add(ClickPoint(id = 1, xRatio = 0.5f, yRatio = 0.45f, actionType = ActionType.HOLD, holdDurationMs = 5000L, delayAfterMs = 500L))
                },
                ButtonSlot(slotId = 2, name = "2번 (단발 탭 1초)").apply {
                    points.add(ClickPoint(id = 1, xRatio = 0.5f, yRatio = 0.55f, actionType = ActionType.TAP, holdDurationMs = 50L, delayAfterMs = 1000L))
                },
                ButtonSlot(slotId = 3, name = "3번 (연타 10회)").apply {
                    points.add(ClickPoint(id = 1, xRatio = 0.5f, yRatio = 0.65f, actionType = ActionType.MULTI_TAP, repeatCount = 10, delayAfterMs = 100L))
                }
            )
        }

        val list = mutableListOf<ButtonSlot>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val slotId = obj.optInt("slotId", i + 1)
                val name = obj.optString("name", "버튼 $slotId")
                val colorHex = obj.optString("colorHex", when (slotId) {
                    1 -> "#4CAF50"
                    2 -> "#2196F3"
                    else -> "#FF9800"
                })
                val triggerMode = try {
                    ActionTrigger.valueOf(obj.optString("triggerMode", "INFINITE"))
                } catch (e: Exception) {
                    ActionTrigger.INFINITE
                }
                val loopRepeatCount = obj.optInt("loopRepeatCount", 5)
                val loopDelayMs = obj.optLong("loopDelayMs", 0L)

                val slot = ButtonSlot(
                    slotId = slotId,
                    name = name,
                    colorHex = colorHex,
                    triggerMode = triggerMode,
                    loopRepeatCount = loopRepeatCount,
                    loopDelayMs = loopDelayMs
                )

                val pointsArr = obj.optJSONArray("points")
                if (pointsArr != null) {
                    for (j in 0 until pointsArr.length()) {
                        val pObj = pointsArr.getJSONObject(j)
                        val actionType = try {
                            ActionType.valueOf(pObj.optString("actionType", "HOLD"))
                        } catch (e: Exception) {
                            ActionType.HOLD
                        }
                        slot.points.add(
                            ClickPoint(
                                id = pObj.optInt("id", j + 1),
                                xRatio = pObj.optDouble("xRatio", 0.5).toFloat(),
                                yRatio = pObj.optDouble("yRatio", 0.5).toFloat(),
                                actionType = actionType,
                                holdDurationMs = pObj.optLong("holdDurationMs", 5000L),
                                delayAfterMs = pObj.optLong("delayAfterMs", 1000L),
                                repeatCount = pObj.optInt("repeatCount", 1)
                            )
                        )
                    }
                }
                list.add(slot)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (list.isEmpty()) {
            return listOf(
                ButtonSlot(1, name = "1번 (5초 홀드)").apply {
                    points.add(ClickPoint(1, 0.5f, 0.45f, ActionType.HOLD, 5000L, 500L))
                },
                ButtonSlot(2, name = "2번 (단발 탭 1초)").apply {
                    points.add(ClickPoint(1, 0.5f, 0.55f, ActionType.TAP, 50L, 1000L))
                },
                ButtonSlot(3, name = "3번 (연타 10회)").apply {
                    points.add(ClickPoint(1, 0.5f, 0.65f, ActionType.MULTI_TAP, 50L, 100L, repeatCount = 10))
                }
            )
        }
        return list
    }
}

