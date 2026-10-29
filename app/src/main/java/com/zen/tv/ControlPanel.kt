package com.zen.tv

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

private val DIM_STEPS = listOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f)

@Composable
fun ControlPanel(
    prefs: ZenPrefs,
    apps: List<TvApp>,
    wallpapers: List<ZenWallpaper>,
    uploadUrl: String?,
    onClose: () -> Unit,
    onDeleteWallpaper: (ZenWallpaper.Uploaded) -> Unit,
) {
    val first = remember { FocusRequester() }
    val wallpaperRow = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val currentIndex = wallpapers.indexOfFirst { it.id == prefs.wallpaper }
    val current = wallpapers.getOrNull(currentIndex)
    val uploads = wallpapers.filterIsInstance<ZenWallpaper.Uploaded>()
    val wallpaperLabel = when (current) {
        null -> "OLED black"
        is ZenWallpaper.Builtin -> current.label
        is ZenWallpaper.Uploaded -> "Photo ${uploads.indexOf(current) + 1} of ${uploads.size}"
    }

    Column(
        Modifier
            .width(420.dp)
            .fillMaxHeight()
            .background(Color(0xFF0D0D12))
            .onPreviewKeyEvent { e ->
                if (e.key == Key.DirectionLeft && e.type == KeyEventType.KeyDown) { onClose(); true } else false
            }
            .padding(start = 32.dp, end = 32.dp, top = 36.dp),
    ) {
        // Header stays pinned so the QR code is always visible; only the settings scroll.
        Text("Zen", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Control panel  ·  ← to close", color = Faint, fontSize = 13.sp)
        Section("Your wallpaper")
        UploadCard(uploadUrl)

        val settingsScroll = rememberScrollState()
        Column(Modifier.fadeEdges(settingsScroll, edge = 32.dp).verticalScroll(settingsScroll).padding(bottom = 36.dp)) {
            Section("Display")
            PanelRow("Clock", if (prefs.use24h) "24-hour" else "12-hour", Modifier.focusRequester(first)) { prefs.use24h = !prefs.use24h }
            PanelRow("Weather", if (prefs.showWeather) "On" else "Off") { prefs.showWeather = !prefs.showWeather }
            PanelRow("Units", if (prefs.fahrenheit) "°F" else "°C") { prefs.fahrenheit = !prefs.fahrenheit }
            val pet = PetKind.from(prefs.pet)
            PanelRow("Pet", pet.label) { prefs.pet = pet.next().name.lowercase() }
            if (pet != PetKind.Off) {
                val spot = PetSpot.from(prefs.petSpot)
                PanelRow("Pet spot", spot.label) { prefs.petSpot = spot.next().name.lowercase() }
            }

            Section("Background")
            PanelRow("Wallpaper", wallpaperLabel, Modifier.focusRequester(wallpaperRow)) {
                // Cycle: black -> presets -> uploaded photos -> black
                prefs.wallpaper = wallpapers.getOrNull(currentIndex + 1)?.id ?: ""
            }
            if (current != null) {
                PanelRow("Dim", "${(prefs.dim * 100).toInt()}%") {
                    prefs.dim = DIM_STEPS.firstOrNull { it > prefs.dim + 0.01f } ?: DIM_STEPS.first()
                }
            }
            if (current is ZenWallpaper.Uploaded) {
                PanelRow("Delete this photo", "") {
                    onDeleteWallpaper(current)
                    wallpaperRow.requestFocus() // this row disappears; don't leave focus nowhere
                }
            }

            Section("Apps")
            Text("Tip: hold OK on an app at home to hide it.", color = Faint, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
            apps.forEach { app ->
                val hidden = app.pkg in prefs.hidden
                PanelRow(app.label, if (hidden) "Hidden" else "Shown", dimLabel = hidden) { prefs.toggleHidden(app.pkg) }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(28.dp))
    Text(title.uppercase(), color = Muted, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun UploadCard(url: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (url != null) {
            val qr = remember(url) { qrBitmap(url) }
            Image(
                qr, "QR code to upload a wallpaper",
                Modifier.size(112.dp).background(Color.White, RoundedCornerShape(10.dp)).padding(6.dp),
            )
            Column(Modifier.padding(start = 16.dp)) {
                Text("Scan with your phone", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Pick a photo and it appears here. Same Wi-Fi only.", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(url.substringBefore("/?"), color = Accent, fontSize = 12.sp)
            }
        } else {
            Text("Connect the TV to Wi-Fi to upload wallpapers.", color = Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun PanelRow(label: String, value: String, modifier: Modifier = Modifier, dimLabel: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .dpadKeys(onClick = onClick)
            .focusable()
            .background(if (focused) Accent.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = if (focused) Color.White else if (dimLabel) Faint else Color(0xFFD6D6DE), fontSize = 16.sp)
        Text(value, color = if (focused) Accent else Muted, fontSize = 16.sp)
    }
}

private fun qrBitmap(text: String, size: Int = 400): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
