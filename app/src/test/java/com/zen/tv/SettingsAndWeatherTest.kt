package com.zen.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsAndWeatherTest {
    @Test
    fun petKindReadsStoredValuesAndCycles() {
        assertEquals(PetKind.Dog, PetKind.from("dog"))
        assertEquals(PetKind.Off, PetKind.from("OFF"))
        assertEquals(PetKind.Cat, PetKind.from("something old"))  // unknown → default
        assertEquals(listOf(PetKind.Dog, PetKind.Off, PetKind.Cat), PetKind.entries.map { it.next() })
    }

    @Test
    fun petSpotReadsStoredValuesAndCycles() {
        assertEquals(PetSpot.Bottom, PetSpot.from("bottom"))
        assertEquals(PetSpot.Weather, PetSpot.from(""))
        PetSpot.entries.forEach { assertEquals(it, PetSpot.from(it.name.lowercase())) }   // what ControlPanel stores
        assertEquals(PetSpot.Weather, PetSpot.Bottom.next())
    }

    @Test
    fun temperatureRoundsInEitherUnit() {
        val w = Weather(tempC = 21.6, code = 0, place = "")
        assertEquals("22°C", w.temperature(fahrenheit = false))
        assertEquals("71°F", w.temperature(fahrenheit = true))
        assertEquals("-3°C", Weather(-2.6, 0, "").temperature(false))
    }

    @Test
    fun weatherCodesMapToWords() {
        assertEquals("Clear", Weather(0.0, 0, "").summary)
        assertEquals("Overcast", Weather(0.0, 3, "").summary)
        assertEquals("Rain", Weather(0.0, 63, "").summary)
        assertEquals("Rain", Weather(0.0, 81, "").summary)
        assertEquals("Snow", Weather(0.0, 75, "").summary)
        assertEquals("Thunderstorm", Weather(0.0, 96, "").summary)
        assertEquals("", Weather(0.0, 42, "").summary)                // unknown code: show just the temperature
    }

    @Test
    fun wallpaperIdsAreStableAndPresetsShip() {
        assertEquals("builtin:02-mist", ZenWallpaper.Builtin("02-mist", "Mist").id)
        assertEquals("wp_1.jpg", ZenWallpaper.Uploaded(File("/x/wp_1.jpg")).id)    // older settings stored the file name
        val ids = WallpaperLibrary.builtins.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (w in WallpaperLibrary.builtins) {
            assertTrue("missing asset for ${w.name}", File("src/main/assets/wallpapers/${w.name}.jpg").isFile)
        }
    }
}
