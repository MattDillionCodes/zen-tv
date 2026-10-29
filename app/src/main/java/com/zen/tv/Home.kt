package com.zen.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val Accent = Color.White
val Muted = Color(0xFF9C9CA8)
val Faint = Color(0xFF55555F)

@Composable
fun ZenHome(
    prefs: ZenPrefs,
    apps: List<TvApp>,
    wallpapers: List<ZenWallpaper>,
    uploadUrl: String?,
    panelOpen: Boolean,
    greet: Int,
    onPanelOpenChange: (Boolean) -> Unit,
    onLaunch: (TvApp) -> Unit,
    onDeleteWallpaper: (ZenWallpaper.Uploaded) -> Unit,
) {
    val visible = apps.filter { it.pkg !in prefs.hidden }
    val firstApp = remember { FocusRequester() }
    val context = LocalContext.current
    val currentWallpaper = wallpapers.firstOrNull { it.id == prefs.wallpaper }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var infoLineRight by remember { mutableFloatStateOf(0f) }
    var clockBounds by remember { mutableStateOf(Rect.Zero) }
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    // Coming back to Zen counts as attention, so a pet that dozed off while you were away wakes up and greets you.
    LaunchedEffect(greet) { lastInteraction = System.currentTimeMillis() }

    // A launcher must never exit on Back; it only closes the panel.
    BackHandler { onPanelOpenChange(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Any remote press counts as attention for the pet; the event still reaches the focused row.
            .onPreviewKeyEvent { lastInteraction = System.currentTimeMillis(); false },
    ) {
        currentWallpaper?.let { wallpaper ->
            val bitmap by produceState<ImageBitmap?>(null, wallpaper) { value = WallpaperLibrary.load(context, wallpaper) }
            bitmap?.let {
                Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = prefs.dim)))
            }
        }

        Column(Modifier.fillMaxHeight().padding(start = 180.dp, top = 110.dp, bottom = 40.dp)) {
            Clock(prefs.use24h, Modifier.onGloballyPositioned { clockBounds = it.boundsInRoot() })
            Spacer(Modifier.height(4.dp))
            InfoLine(prefs, Modifier.onGloballyPositioned { infoLineRight = it.boundsInRoot().right })
            Spacer(Modifier.height(44.dp))
            // Takes whatever height is left under the clock (TVs with many apps scroll), fading at the edges.
            val listScroll = rememberScrollState()
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .onGloballyPositioned { listBounds = it.boundsInRoot() }
                    .fadeEdges(listScroll)
                    .verticalScroll(listScroll),
            ) {
                visible.forEachIndexed { i, app ->
                    AppRow(
                        label = app.label,
                        modifier = if (i == 0) Modifier.focusRequester(firstApp) else Modifier,
                        onClick = { onLaunch(app) },
                        onLongClick = { prefs.toggleHidden(app.pkg) },
                        onRight = { onPanelOpenChange(true) },
                    )
                }
                if (visible.isEmpty()) {
                    Text(
                        "All apps hidden",
                        color = Muted, fontSize = 18.sp,
                        modifier = Modifier.focusRequester(firstApp).dpadKeys(onClick = { onPanelOpenChange(true) }).focusable(),
                    )
                }
            }
        }

        val pet = PetKind.from(prefs.pet)
        if (pet != PetKind.Off) BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val px = 1.5.dp                                // per sprite pixel
            val spriteWidth = px * PetSprites.WIDTH
            val petHeight = px * PetSprites.HEIGHT
            // Each spot gives the pet a top-left corner and how far (sprite pixels) it may roam to the right.
            val (left, top, wander) = when (PetSpot.from(prefs.petSpot)) {
                PetSpot.Weather -> {
                    // Open space right of the info line (~74% across, ~47% down); slides right if the text runs long.
                    val roam = 24
                    val width = spriteWidth + px * roam
                    val clearOfText = with(density) { infoLineRight.toDp() } + 16.dp
                    val x = maxOf(maxWidth * 0.745f - width / 2, clearOfText).coerceAtMost(maxWidth - width - 24.dp)
                    Triple(x, maxHeight * 0.466f - petHeight / 2, roam)
                }
                PetSpot.Clock -> {
                    // Standing on the clock's baseline, just past the last digit.
                    val (right, bottom) = with(density) { clockBounds.right.toDp() to clockBounds.bottom.toDp() }
                    Triple(right + 20.dp, bottom - petHeight - 21.dp, 20)
                }
                PetSpot.Bottom -> {
                    // A long strip along the bottom, stopping short of the "→ control panel" hint. If the app list
                    // reaches down that far (TVs with many apps), the strip starts to the right of the list instead.
                    val top = maxHeight - petHeight - 30.dp
                    val list = with(density) { listBounds.right.toDp() to listBounds.bottom.toDp() }
                    val start = if (list.second > top) maxOf(180.dp, list.first + 32.dp) else 180.dp
                    val room = maxWidth - start - 280.dp - spriteWidth
                    Triple(start, top, (room / px).toInt().coerceAtLeast(0))
                }
            }
            Pet(
                pet, lastInteraction, greet, wander,
                Modifier.offset(x = left, y = top).size(spriteWidth + px * wander, petHeight),
            )
        }

        if (!panelOpen) Text(
            "→  control panel",
            color = Faint, fontSize = 13.sp, letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 32.dp),
        )

        AnimatedVisibility(
            visible = panelOpen,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            ControlPanel(
                prefs = prefs,
                apps = apps,
                wallpapers = wallpapers,
                uploadUrl = uploadUrl,
                onClose = { onPanelOpenChange(false) },
                onDeleteWallpaper = onDeleteWallpaper,
            )
        }
    }

    // Re-focus when the list changes too: hiding the focused app would otherwise leave focus nowhere.
    LaunchedEffect(visible.size, panelOpen) {
        if (!panelOpen) runCatching { firstApp.requestFocus() }
    }
}

@Composable
private fun Clock(use24h: Boolean, modifier: Modifier = Modifier) {
    val now = rememberNow()
    val text = now.format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm"))
    Text(text, color = Color.White, fontSize = 104.sp, fontWeight = FontWeight.Bold, letterSpacing = (-3).sp, modifier = modifier)
}

@Composable
private fun InfoLine(prefs: ZenPrefs, modifier: Modifier = Modifier) {
    val now = rememberNow()
    var weather by remember { mutableStateOf<Weather?>(null) }
    LaunchedEffect(prefs.showWeather) {
        while (prefs.showWeather) {
            weather = WeatherClient.fetch() ?: weather
            tick(if (weather == null) 60_000L else 30 * 60_000L)   // waits until Zen is on screen again
        }
    }
    val date = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM"))
    val parts = buildList {
        add(date)
        weather?.takeIf { prefs.showWeather }?.let { w ->
            add(listOf(w.temperature(prefs.fahrenheit), w.summary).filter { it.isNotEmpty() }.joinToString(" "))
            if (w.place.isNotEmpty()) add(w.place)
        }
    }
    Text(parts.joinToString("   ·   "), color = Muted, fontSize = 18.sp, modifier = modifier.padding(start = 6.dp))
}

@Composable
private fun AppRow(label: String, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit, onRight: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        color = if (focused) Accent else Muted,
        fontSize = 19.sp,
        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .dpadKeys(onClick = onClick, onLongClick = onLongClick, onRight = onRight)
            .focusable()
            .padding(start = 6.dp, top = 4.dp, bottom = 4.dp),
    )
}

/**
 * Remote handling: OK = click, holding OK = long click, → = onRight.
 * Long press is detected from key repeats, because remotes send no separate event for it.
 */
fun Modifier.dpadKeys(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, onRight: (() -> Unit)? = null): Modifier = composed {
    var longFired by remember { mutableStateOf(false) }
    onPreviewKeyEvent { e ->
        when (e.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (e.type == KeyEventType.KeyDown) {
                    val repeat = e.nativeKeyEvent.repeatCount
                    if (repeat == 0) longFired = false
                    else if (!longFired && onLongClick != null && (e.nativeKeyEvent.isLongPress || repeat >= 10)) {
                        longFired = true
                        onLongClick()
                    }
                } else if (e.type == KeyEventType.KeyUp) {
                    if (!longFired) onClick()
                    longFired = false
                }
                true
            }
            Key.DirectionRight -> if (onRight != null && e.type == KeyEventType.KeyDown) { onRight(); true } else false
            else -> false
        }
    }
}

@Composable
private fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            tick(1_000 - System.currentTimeMillis() % 1_000)
        }
    }
    return now
}

/** Fades the top/bottom edge of a scrolling list when there is more to see in that direction. */
internal fun Modifier.fadeEdges(scroll: ScrollState, edge: androidx.compose.ui.unit.Dp = 48.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = edge.toPx().coerceAtMost(size.height / 2)
        if (scroll.canScrollBackward) {
            drawRect(Brush.verticalGradient(0f to Color.Transparent, fade / size.height to Color.Black), blendMode = BlendMode.DstIn)
        }
        if (scroll.canScrollForward) {
            drawRect(Brush.verticalGradient(1f - fade / size.height to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }
    }
