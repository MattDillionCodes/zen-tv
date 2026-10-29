package com.zen.tv

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Settings backed by SharedPreferences, exposed as Compose state so the UI redraws on change. */
class ZenPrefs(context: Context) {
    private val sp = context.getSharedPreferences("zen", Context.MODE_PRIVATE)
    private val launches = context.getSharedPreferences("zen_launches", Context.MODE_PRIVATE)

    var use24h by Pref(sp, "use24h", false, SharedPreferences::getBoolean, SharedPreferences.Editor::putBoolean)
    var showWeather by Pref(sp, "showWeather", true, SharedPreferences::getBoolean, SharedPreferences.Editor::putBoolean)
    var fahrenheit by Pref(sp, "fahrenheit", false, SharedPreferences::getBoolean, SharedPreferences.Editor::putBoolean)
    /** File name inside the wallpapers dir, or "" for plain OLED black. */
    var wallpaper by Pref(sp, "wallpaper", "", { k, d -> getString(k, d) ?: d }, SharedPreferences.Editor::putString)
    /** "cat", "dog" or "off" — see [PetKind]. */
    var pet by Pref(sp, "pet", "cat", { k, d -> getString(k, d) ?: d }, SharedPreferences.Editor::putString)
    /** "weather", "clock" or "bottom" — see [PetSpot]. */
    var petSpot by Pref(sp, "petSpot", "weather", { k, d -> getString(k, d) ?: d }, SharedPreferences.Editor::putString)
    var dim by Pref(sp, "dim", 0.5f, SharedPreferences::getFloat, SharedPreferences.Editor::putFloat)
    var hidden by Pref(sp, "hidden", emptySet<String>(), { k, d -> getStringSet(k, d)?.toSet() ?: d }, SharedPreferences.Editor::putStringSet)

    fun toggleHidden(pkg: String) {
        hidden = if (pkg in hidden) hidden - pkg else hidden + pkg
    }

    fun recordLaunch(pkg: String) = launches.edit { putInt(pkg, launches.getInt(pkg, 0) + 1) }

    fun launchCounts(): Map<String, Int> = launches.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
}

private class Pref<T>(
    private val sp: SharedPreferences,
    private val key: String,
    default: T,
    read: SharedPreferences.(String, T) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> SharedPreferences.Editor,
) : ReadWriteProperty<Any?, T> {
    private val state = mutableStateOf(sp.read(key, default))

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        state.value = value
        sp.edit { write(key, value) }
    }
}
