package com.zen.tv

import android.content.Context
import android.content.Intent
import android.provider.Settings

data class TvApp(val pkg: String, val label: String, val launch: Intent)

object AppRepository {
    private const val SETTINGS_PKG = "zen.settings"

    /** Every launchable app, TV (leanback) entries preferred over phone entries. */
    fun load(context: Context): List<TvApp> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, TvApp>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val activity = info.activityInfo
                if (activity.packageName == context.packageName || activity.packageName in found) continue
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(activity.packageName, activity.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                found[activity.packageName] = TvApp(activity.packageName, info.loadLabel(pm).toString(), launch)
            }
        }
        if (found.values.none { it.label.equals("Settings", ignoreCase = true) }) {
            val settings = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            found[SETTINGS_PKG] = TvApp(SETTINGS_PKG, "Settings", settings)
        }
        return found.values.toList()
    }

    /**
     * Decides the order of the text-only app list on the home screen.
     * [launchCounts] maps package name -> how many times Zen has launched it.
     */
    fun order(apps: List<TvApp>, launchCounts: Map<String, Int>): List<TvApp> {
        // TODO(human): choose how Zen orders the app list.
        return apps.sortedBy { it.label.lowercase() }
    }
}
