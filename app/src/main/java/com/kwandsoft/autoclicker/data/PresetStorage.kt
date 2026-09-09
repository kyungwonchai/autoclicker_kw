package com.kwandsoft.autoclicker.data

import android.content.Context
import com.kwandsoft.autoclicker.model.ButtonSlot
import com.kwandsoft.autoclicker.model.ClickPoint
import org.json.JSONObject

object PresetStorage {
    private const val PREFS_NAME = "autoclicker_presets_v2"
    private const val KEY_TARGET_POINT = "saved_target_point_json"
    private const val KEY_MENU_X = "saved_menu_x"
    private const val KEY_MENU_Y = "saved_menu_y"

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

    fun saveTargetPoint(context: Context, point: ClickPoint) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val obj = JSONObject().apply {
            put("id", point.id)
            put("xRatio", point.xRatio.toDouble())
            put("yRatio", point.yRatio.toDouble())
        }
        prefs.edit().putString(KEY_TARGET_POINT, obj.toString()).apply()
    }

    fun loadTargetPoint(context: Context): ClickPoint {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_TARGET_POINT, null)
        if (jsonStr != null) {
            try {
                val obj = JSONObject(jsonStr)
                return ClickPoint(
                    id = obj.optInt("id", 1),
                    xRatio = obj.optDouble("xRatio", 0.5).toFloat(),
                    yRatio = obj.optDouble("yRatio", 0.5).toFloat()
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return ClickPoint(id = 1, xRatio = 0.5f, yRatio = 0.5f)
    }

    fun getSlots(): List<ButtonSlot> {
        return listOf(
            ButtonSlot(slotId = 1, name = "15", holdDurationMs = 15000L, delayAfterMs = 150L, colorHex = "#E91E63"),
            ButtonSlot(slotId = 2, name = "10", holdDurationMs = 10000L, delayAfterMs = 150L, colorHex = "#2196F3"),
            ButtonSlot(slotId = 3, name = "5", holdDurationMs = 5000L, delayAfterMs = 150L, colorHex = "#4CAF50")
        )
    }

}


