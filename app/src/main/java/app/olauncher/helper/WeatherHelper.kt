package app.olauncher.helper

import android.content.Context
import android.util.Log
import app.olauncher.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class WeatherHelper(private val context: Context) {

    private val prefs = Prefs(context)

    companion object {
        private const val TAG = "WeatherHelper"
        private const val CACHE_DURATION_MS = 30 * 60 * 1000L // 30 minutes
    }

    /**
     * Fetch weather data from QWeather API.
     * Returns formatted string like "🌧 阵雨 27°C" or null on failure.
     * Uses GPS coordinates if available, falls back to city ID.
     */
    suspend fun getWeatherText(): String? = withContext(Dispatchers.IO) {
        if (!prefs.weatherEnabled) {
            Log.d(TAG, "Weather disabled")
            return@withContext null
        }

        val now = System.currentTimeMillis()
        val cacheAge = now - prefs.weatherCacheTime

        // Return cached data if still valid
        if (cacheAge < CACHE_DURATION_MS && prefs.weatherCacheData.isNotEmpty()) {
            Log.d(TAG, "Returning cached weather: ${prefs.weatherCacheData}")
            return@withContext prefs.weatherCacheData
        }

        try {
            val host = prefs.weatherApiHost
            val key = prefs.weatherApiKey

            // Determine location parameter: prefer GPS coordinates, fallback to city ID
            val location = getLocationString()
            Log.d(TAG, "Using weather location: $location")

            val urlString = "https://$host/v7/weather/now?location=$location&key=$key"
            Log.d(TAG, "Fetching weather: $urlString")

            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            Log.d(TAG, "Weather API response: $response")

            val json = JSONObject(response)
            val code = json.optString("code", "")

            if (code != "200") {
                Log.e(TAG, "Weather API error: $code")
                return@withContext null
            }

            val nowObj = json.getJSONObject("now")
            val temp = nowObj.optString("temp", "?")
            val text = nowObj.optString("text", "")
            val icon = nowObj.optString("icon", "")

            val emoji = getWeatherEmoji(icon, text)

            val result = "$emoji $text $temp°C"
            Log.d(TAG, "Parsed weather: $result")

            // Update cache
            prefs.weatherCacheData = result
            prefs.weatherCacheTime = System.currentTimeMillis()

            result
        } catch (e: Exception) {
            Log.e(TAG, "Weather API exception: ${e.message}")
            // Return stale cache on network error
            if (prefs.weatherCacheData.isNotEmpty()) {
                prefs.weatherCacheData
            } else {
                null
            }
        }
    }

    /**
     * Get location string for QWeather API.
     * Returns GPS coordinates "lng,lat" if recent GPS data exists,
     * otherwise falls back to city ID.
     */
    private fun getLocationString(): String {
        val now = System.currentTimeMillis()
        val gpsAge = now - prefs.weatherGpsTime
        val gpsValid = prefs.weatherGpsLat != 0.0 && prefs.weatherGpsLng != 0.0 && gpsAge < 6 * 60 * 60 * 1000L // 6 hours

        return if (gpsValid) {
            // QWeather uses "lng,lat" format
            "${prefs.weatherGpsLng},${prefs.weatherGpsLat}"
        } else {
            prefs.weatherLocation
        }
    }

    /**
     * Map QWeather icon codes to emojis.
     * See: https://dev.qweather.com/docs/resource/icons/
     */
    private fun getWeatherEmoji(icon: String, text: String): String {
        return when {
            // Clear / Sunny
            icon == "100" -> if (text.contains("晴") && !text.contains("多")) "☀️" else "🌙"
            // Cloudy
            icon in listOf("101", "102", "103", "104") -> "☁️"
            // Partly cloudy
            icon in listOf("150", "151", "152", "153") -> "⛅"
            // Rain
            icon == "300" -> "🌦"  // 阵雨
            icon == "301" -> "🌧"  // 强阵雨
            icon == "302" -> "⛈"  // 雷阵雨
            icon == "303" -> "⛈"  // 强雷阵雨
            icon == "304" -> "⛈"  // 雷阵雨伴有冰雹
            icon == "305" -> "🌧"  // 小雨
            icon == "306" -> "🌧"  // 中雨
            icon == "307" -> "🌧"  // 大雨
            icon == "308" -> "🌧"  // 极端降雨
            icon == "309" -> "🌧"  // 毛毛雨
            icon == "310" -> "⛈"  // 暴雨
            icon in listOf("311", "312", "313", "314", "315", "316", "317", "318", "399") -> "🌧"
            // Snow
            icon == "400" -> "🌨"  // 小雪
            icon == "401" -> "🌨"  // 中雪
            icon == "402" -> "🌨"  // 大雪
            icon == "403" -> "❄️"  // 暴雪
            icon == "404" -> "🌨"  // 雨夹雪
            icon == "405" -> "🌨"  // 雨雪天气
            icon == "406" -> "🌨"  // 阵雨雪
            icon == "407" -> "🌨"  // 雪后阵雨
            icon == "408" -> "🌨"  // 雪后冰雹
            icon == "409" -> "❄️"  // 雪
            icon == "410" -> "❄️"  // 阵雪
            icon in listOf("456", "457", "499") -> "❄️"
            // Fog
            icon in listOf("500", "501", "502", "503", "504", "507", "508", "509", "510", "511", "512", "513", "514", "515") -> "🌫"
            // Wind
            icon in listOf("805", "806") -> "💨"
            // Default
            else -> "🌤"
        }
    }
}
