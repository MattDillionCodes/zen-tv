package com.zen.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var prefs: ZenPrefs
    private lateinit var server: UploadServer
    private val handler = Handler(Looper.getMainLooper())
    private val stopServer = Runnable { server.stop(); uploadUrl.value = null }

    private val apps = mutableStateOf<List<TvApp>>(emptyList())
    private val wallpapers = mutableStateOf<List<ZenWallpaper>>(emptyList())
    private val uploadUrl = mutableStateOf<String?>(null)
    private val panelOpen = mutableStateOf(false)
    /** Bumped each time Zen comes back on screen, so the pet wakes up and greets you. */
    private val greet = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ZenPrefs(this)
        server = UploadServer(this) { file ->
            runOnUiThread {
                refreshWallpapers()
                prefs.wallpaper = file.name
                Toast.makeText(this, "New wallpaper", Toast.LENGTH_SHORT).show()
            }
        }
        setContent {
            ZenHome(
                prefs = prefs,
                apps = apps.value,
                wallpapers = wallpapers.value,
                uploadUrl = uploadUrl.value,
                panelOpen = panelOpen.value,
                greet = greet.intValue,
                onPanelOpenChange = ::setPanelOpen,
                onLaunch = ::launch,
                onDeleteWallpaper = ::deleteWallpaper,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        greet.intValue++
        refreshApps()
        refreshWallpapers()
    }

    override fun onStop() {
        setPanelOpen(false)
        handler.removeCallbacks(stopServer)
        stopServer.run()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home while Zen is already showing closes the control panel.
        setPanelOpen(false)
    }

    /**
     * The upload server only runs while the panel (and its QR code) is open, plus a short grace period
     * so a photo that's already uploading can finish. Each time the server starts it gets a new code.
     */
    private fun setPanelOpen(open: Boolean) {
        if (panelOpen.value == open) return
        panelOpen.value = open
        handler.removeCallbacks(stopServer)
        if (open) {
            server.start()
            uploadUrl.value = server.url()
            if (BuildConfig.DEBUG) Log.i("Zen", "Wallpaper upload URL: ${uploadUrl.value}")
        } else if (server.running) {
            handler.postDelayed(stopServer, UPLOAD_GRACE_MS)
        }
    }

    private fun refreshApps() {
        lifecycleScope.launch {
            // Querying every launcher entry and its label is slow on low-end TVs: keep it off the main thread.
            val loaded = withContext(Dispatchers.IO) { AppRepository.load(this@MainActivity) }
            apps.value = AppRepository.order(loaded, prefs.launchCounts())
        }
    }

    private fun refreshWallpapers() {
        wallpapers.value = WallpaperLibrary.all(this)
    }

    private fun launch(app: TvApp) {
        prefs.recordLaunch(app.pkg)
        runCatching { startActivity(app.launch) }
            .onFailure { Toast.makeText(this, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show() }
    }

    private fun deleteWallpaper(wallpaper: ZenWallpaper.Uploaded) {
        wallpaper.file.delete()
        if (prefs.wallpaper == wallpaper.id) prefs.wallpaper = ""
        refreshWallpapers()
    }

    private companion object {
        const val UPLOAD_GRACE_MS = 2 * 60_000L
    }
}
