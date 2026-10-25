package com.englishai.app

import android.content.Context
import org.json.JSONObject
import java.time.LocalTime

/** Small, versioned native mirror of the user's assistant settings. */
class UserSettings(context: Context) {
    private val prefs = context.getSharedPreferences("english_ai_user_settings", Context.MODE_PRIVATE)

    fun save(json: String) {
        val root = JSONObject(json)
        val routines = root.optJSONObject("routines") ?: JSONObject()
        prefs.edit()
            .putString("userName", root.optString("userName", "").trim().take(24))
            .putString("routines", routines.toString())
            .putString("interactionMode", root.optString("interactionMode", "continuous"))
            .apply()
    }

    fun userName(): String = prefs.getString("userName", "").orEmpty()

    fun isWithinActiveRoutine(now: LocalTime = LocalTime.now()): Boolean {
        val r = try { JSONObject(prefs.getString("routines", "{}")) } catch (_: Exception) { JSONObject() }
        return inWindow(now, r.optString("morningStart", "04:30"), r.optString("morningEnd", "07:30")) ||
            inWindow(now, r.optString("eveningStart", "18:30"), r.optString("eveningEnd", "22:00"))
    }

    fun interactionMode(): String = prefs.getString("interactionMode", "continuous") ?: "continuous"

    private fun inWindow(now: LocalTime, startText: String, endText: String): Boolean {
        val start = try { LocalTime.parse(startText) } catch (_: Exception) { return false }
        val end = try { LocalTime.parse(endText) } catch (_: Exception) { return false }
        return if (start == end) true else if (start.isBefore(end)) !now.isBefore(start) && now.isBefore(end)
        else !now.isBefore(start) || now.isBefore(end)
    }
}
