package com.example.hdrcamera.data

import android.content.Context
import android.content.SharedPreferences
import com.example.hdrcamera.model.FilterParameters
import com.example.hdrcamera.model.FilterProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ProfileManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hdr_camera_profiles", Context.MODE_PRIVATE)

    fun getAllProfiles(): List<FilterProfile> {
        val customProfiles = loadCustomProfiles()
        return FilterProfile.DEFAULT_PROFILES + customProfiles
    }

    fun saveCustomProfile(name: String, params: FilterParameters): FilterProfile {
        val id = "custom_" + UUID.randomUUID().toString().take(8)
        val newProfile = FilterProfile(
            id = id,
            name = name,
            description = "Custom profile with personalized HDR tuning",
            isCustom = true,
            params = params
        )

        val currentList = loadCustomProfiles().toMutableList()
        currentList.add(newProfile)
        saveCustomProfilesList(currentList)
        return newProfile
    }

    fun updateProfile(id: String, newName: String, params: FilterParameters) {
        val currentList = loadCustomProfiles().toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            currentList[index] = currentList[index].copy(
                name = newName,
                params = params
            )
            saveCustomProfilesList(currentList)
        }
    }

    fun deleteProfile(id: String) {
        val currentList = loadCustomProfiles().toMutableList()
        currentList.removeAll { it.id == id }
        saveCustomProfilesList(currentList)
    }

    private fun loadCustomProfiles(): List<FilterProfile> {
        val jsonString = prefs.getString("custom_profiles_json", null) ?: return emptyList()
        val list = mutableListOf<FilterProfile>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val paramsObj = obj.getJSONObject("params")
                val params = FilterParameters(
                    exposure = paramsObj.optDouble("exposure", 0.0).toFloat(),
                    contrast = paramsObj.optDouble("contrast", 1.0).toFloat(),
                    saturation = paramsObj.optDouble("saturation", 1.0).toFloat(),
                    temperature = paramsObj.optDouble("temperature", 0.0).toFloat(),
                    tint = paramsObj.optDouble("tint", 0.0).toFloat(),
                    sharpness = paramsObj.optDouble("sharpness", 0.5).toFloat(),
                    highlightRecovery = paramsObj.optDouble("highlightRecovery", 1.25).toFloat(),
                    shadowBoost = paramsObj.optDouble("shadowBoost", 0.25).toFloat(),
                    lutId = paramsObj.optString("lutId", "natural"),
                    lutIntensity = paramsObj.optDouble("lutIntensity", 0.75).toFloat(),
                    fastPreview = paramsObj.optBoolean("fastPreview", false)
                )
                list.add(
                    FilterProfile(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", "Custom user profile"),
                        isCustom = true,
                        params = params
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun saveCustomProfilesList(list: List<FilterProfile>) {
        val jsonArray = JSONArray()
        for (profile in list) {
            val obj = JSONObject()
            obj.put("id", profile.id)
            obj.put("name", profile.name)
            obj.put("description", profile.description)
            val paramsObj = JSONObject()
            paramsObj.put("exposure", profile.params.exposure.toDouble())
            paramsObj.put("contrast", profile.params.contrast.toDouble())
            paramsObj.put("saturation", profile.params.saturation.toDouble())
            paramsObj.put("temperature", profile.params.temperature.toDouble())
            paramsObj.put("tint", profile.params.tint.toDouble())
            paramsObj.put("sharpness", profile.params.sharpness.toDouble())
            paramsObj.put("highlightRecovery", profile.params.highlightRecovery.toDouble())
            paramsObj.put("shadowBoost", profile.params.shadowBoost.toDouble())
            paramsObj.put("lutId", profile.params.lutId)
            paramsObj.put("lutIntensity", profile.params.lutIntensity.toDouble())
            paramsObj.put("fastPreview", profile.params.fastPreview)
            obj.put("params", paramsObj)
            jsonArray.put(obj)
        }
        prefs.edit().putString("custom_profiles_json", jsonArray.toString()).apply()
    }
}
