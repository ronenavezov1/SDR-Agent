package org.example.tools

import org.example.agent.Tool

/**
 * Tool שמחזיר נתוני מזג אוויר עבור מיקום נתון.
 *
 * מיישם את [Tool] ישירות — משמש כ-AgentCapability שה-LLM יכול להפעיל.
 * הארגומנט הנדרש: "location" (שם עיר / מיקום).
 *
 * TODO: החלף את המימוש המדומה בקריאת API אמיתית (OpenWeather, Tomorrow.io וכו').
 */
class WeatherCheckTool : Tool {

    override val name: String = "getWeatherData"
    override val description: String =
        "Retrieves current weather data for a given location. " +
        "Pass the location name in the 'location' argument."
    override val parameters: Map<String, String> = mapOf(
        "location" to "The city or location name to get the weather for"
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val location = args["location"]?.toString()?.trim()
            ?: return "Error: 'location' argument is required."

        // TODO: replace with real weather API call
        return "Weather in $location: Sunny, 25°C, humidity 60%, light breeze from the west."
    }
}
