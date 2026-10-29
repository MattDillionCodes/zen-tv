package com.zen.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class Weather(val tempC: Double, val code: Int, val place: String) {
    fun temperature(fahrenheit: Boolean): String =
        if (fahrenheit) "${(tempC * 9 / 5 + 32).roundToInt()}°F" else "${tempC.roundToInt()}°C"

    /** WMO weather interpretation codes, as returned by Open-Meteo. */
    val summary: String
        get() = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            in 51..57 -> "Drizzle"
            in 61..67, in 80..82 -> "Rain"
            in 71..77, 85, 86 -> "Snow"
            in 95..99 -> "Thunderstorm"
            else -> ""
        }
}

/** Keyless weather: approximate location from the TV's IP, then current conditions from Open-Meteo. */
object WeatherClient {
    suspend fun fetch(): Weather? = withContext(Dispatchers.IO) {
        runCatching {
            val loc = JSONObject(get("https://ipwho.is/"))
            val lat = loc.getDouble("latitude")
            val lon = loc.getDouble("longitude")
            val current = JSONObject(
                get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code")
            ).getJSONObject("current")
            Weather(current.getDouble("temperature_2m"), current.getInt("weather_code"), loc.optString("city"))
        }.getOrNull()
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "ZenTV/0.1")
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
