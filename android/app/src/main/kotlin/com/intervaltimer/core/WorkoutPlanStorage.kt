package com.intervaltimer.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Storage and synchronization helper for Workout Plans in Android.
 * Compatible with Web-exported JSON and compact QR-code strings.
 */
class WorkoutPlanStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("interval_timer_plans_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_CUSTOM_PLANS = "custom_workout_plans_json"
        private const val KEY_ACTIVE_PLAN_ID = "active_plan_id"
    }

    /**
     * Returns built-in factory plans matching the Web suite.
     */
    fun getBuiltinPlans(): List<WorkoutPlan> {
        return listOf(
            IntervalTimerNative.getDefaultTabataPlan(),
            IntervalTimerNative.getDefaultBoxingPlan(),
            IntervalTimerNative.getDefaultHIITPlan(),
            IntervalTimerNative.getDefaultStretchPlan()
        )
    }

    /**
     * Returns all available plans (built-in + saved custom/imported).
     */
    fun getAllPlans(): List<WorkoutPlan> {
        val builtins = getBuiltinPlans()
        val custom = getCustomPlans()
        return builtins + custom
    }

    /**
     * Retrieves saved custom plans from SharedPreferences.
     */
    fun getCustomPlans(): List<WorkoutPlan> {
        val rawJson = prefs.getString(KEY_CUSTOM_PLANS, null) ?: return emptyList()
        val list = mutableListOf<WorkoutPlan>()
        try {
            val jsonArray = JSONArray(rawJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                parsePlanFromJsonObject(obj)?.let { list.add(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /**
     * Saves a new or modified plan.
     */
    fun savePlan(plan: WorkoutPlan) {
        val existing = getCustomPlans().toMutableList()
        val idx = existing.indexOfFirst { it.id == plan.id }
        val toSave = plan.copy(isCustom = true)
        if (idx >= 0) {
            existing[idx] = toSave
        } else {
            existing.add(0, toSave)
        }
        saveCustomPlansList(existing)
    }

    /**
     * Deletes a custom plan by ID.
     */
    fun deletePlan(planId: String) {
        val filtered = getCustomPlans().filter { it.id != planId }
        saveCustomPlansList(filtered)
    }

    private fun saveCustomPlansList(list: List<WorkoutPlan>) {
        val jsonArray = JSONArray()
        for (p in list) {
            jsonArray.put(serializePlanToJsonObject(p))
        }
        prefs.edit().putString(KEY_CUSTOM_PLANS, jsonArray.toString()).apply()
    }

    /**
     * Imports a workout plan from:
     * 1. A compact URI (itimer://plan?d=...)
     * 2. Full SyncPackage JSON { format: "itimer-profile", plan: { ... } }
     * 3. Direct WorkoutPlan JSON { name: "...", sets: [...] }
     */
    fun importPlanFromString(input: String): WorkoutPlan? {
        val trimmed = input.trim()

        // 1. Compact itimer URI
        if (trimmed.startsWith("itimer://plan?d=")) {
            try {
                val b64 = trimmed.substring("itimer://plan?d=".length)
                val decodedBytes = Base64.decode(b64, Base64.DEFAULT)
                val jsonStr = String(decodedBytes, StandardCharsets.UTF_8)
                val minObj = JSONObject(jsonStr)

                val name = minObj.optString("n", "Импортированная тренировка")
                val prep = minObj.optInt("p", 5)
                val cycles = minObj.optInt("c", 1)
                val cycleRest = minObj.optInt("cr", 30)

                val setsList = mutableListOf<IntervalSet>()
                val sArray = minObj.optJSONArray("s")
                if (sArray != null) {
                    for (i in 0 until sArray.length()) {
                        val arr = sArray.getJSONArray(i)
                        val setName = arr.optString(0, "Интервал ${i + 1}")
                        val workSec = arr.optInt(1, 30)
                        val restSec = arr.optInt(2, 15)
                        setsList.add(IntervalSet(name = setName, workSeconds = workSec, restSeconds = restSec))
                    }
                }
                if (setsList.isNotEmpty()) {
                    val plan = WorkoutPlan(
                        id = "imported_${System.currentTimeMillis()}",
                        name = name,
                        prepSeconds = prep,
                        cycles = cycles,
                        cycleRestSeconds = cycleRest,
                        sets = setsList,
                        isCustom = true
                    )
                    savePlan(plan)
                    return plan
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Full JSON package or direct JSON
        try {
            val root = JSONObject(trimmed)
            val planObj = if (root.has("plan")) root.getJSONObject("plan") else root
            val parsed = parsePlanFromJsonObject(planObj)
            if (parsed != null) {
                val toSave = parsed.copy(
                    id = if (parsed.id.isNotBlank()) parsed.id else "imported_${System.currentTimeMillis()}",
                    isCustom = true
                )
                savePlan(toSave)
                return toSave
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    /**
     * Serializes a plan to a JSON string compatible with the Web app.
     */
    fun exportPlanToJson(plan: WorkoutPlan): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("format", "itimer-profile")
        root.put("source", "android")
        root.put("exportedAt", System.currentTimeMillis().toString())
        root.put("plan", serializePlanToJsonObject(plan))
        return root.toString(2)
    }

    private fun serializePlanToJsonObject(plan: WorkoutPlan): JSONObject {
        val obj = JSONObject()
        obj.put("id", plan.id)
        obj.put("name", plan.name)
        obj.put("description", plan.description)
        obj.put("prepSeconds", plan.prepSeconds)
        obj.put("cycles", plan.cycles)
        obj.put("cycleRestSeconds", plan.cycleRestSeconds)
        obj.put("isCustom", plan.isCustom)

        val setsArray = JSONArray()
        for (s in plan.sets) {
            val sObj = JSONObject()
            sObj.put("id", s.id)
            sObj.put("name", s.name)
            sObj.put("workSeconds", s.workSeconds)
            sObj.put("restSeconds", s.restSeconds)
            sObj.put("workSoundFreq", s.soundWorkFreq)
            sObj.put("restSoundFreq", s.soundRestFreq)
            setsArray.put(sObj)
        }
        obj.put("sets", setsArray)
        return obj
    }

    private fun parsePlanFromJsonObject(obj: JSONObject): WorkoutPlan? {
        val name = obj.optString("name", "")
        if (name.isBlank()) return null

        val id = obj.optString("id", "plan_${System.currentTimeMillis()}")
        val description = obj.optString("description", "")
        val prep = obj.optInt("prepSeconds", 5)
        val cycles = obj.optInt("cycles", 1)
        val cycleRest = obj.optInt("cycleRestSeconds", 30)
        val isCustom = obj.optBoolean("isCustom", true)

        val setsArray = obj.optJSONArray("sets") ?: return null
        val setsList = mutableListOf<IntervalSet>()

        for (i in 0 until setsArray.length()) {
            val sObj = setsArray.getJSONObject(i)
            val setName = sObj.optString("name", "Сет ${i + 1}")
            val work = sObj.optInt("workSeconds", 30)
            val rest = sObj.optInt("restSeconds", 15)
            val workFreq = sObj.optInt("workSoundFreq", 880)
            val restFreq = sObj.optInt("restSoundFreq", 440)
            val setId = sObj.optString("id", "set_$i")

            setsList.add(
                IntervalSet(
                    name = setName,
                    workSeconds = work,
                    restSeconds = rest,
                    soundWorkFreq = workFreq,
                    soundRestFreq = restFreq,
                    id = setId
                )
            )
        }

        if (setsList.isEmpty()) return null

        return WorkoutPlan(
            name = name,
            prepSeconds = prep,
            cycles = cycles,
            cycleRestSeconds = cycleRest,
            sets = setsList,
            id = id,
            description = description,
            isCustom = isCustom
        )
    }
}
