package com.zen.tv

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.IdentityHashMap
import kotlin.random.Random

enum class PetKind(val label: String) {
    Cat("Cat"), Dog("Dog"), Off("Off");

    fun next(): PetKind = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(value: String): PetKind = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Cat
    }
}

/** Where the pet lives on the home screen (control panel → Pet spot). */
enum class PetSpot(val label: String) {
    Weather("Beside the weather"), Clock("By the clock"), Bottom("Along the bottom");

    fun next(): PetSpot = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(value: String): PetSpot = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Weather
    }
}

/** Sprite colours; must match PALETTE in tools/pet_sprites.py. */
internal val PetPalette = mapOf(
    'O' to 0xFF3A3A44, 'K' to 0xFF16161C, 'H' to 0xFFFFFFFF,
    'W' to 0xFFDCDCE2, 'L' to 0xFFF2F2F6, 'G' to 0xFFB0B0BA, 'S' to 0xFF8E8E9A,
    'C' to 0xFFE8DFD0, 'D' to 0xFFC9BCA8, 'B' to 0xFF9C7F63, 'E' to 0xFF7A6250,
    'P' to 0xFFE8909E, 'A' to 0xFFEFAAB4,
    'R' to 0xFFC2453A, 'U' to 0xFF4F7FC9, 'Y' to 0xFFE8C15A,
).mapValues { Color(it.value) }

// 3x3 eyes drawn over the sprite, so one frame can blink, squint or sleep.
private val EYE_OPEN = listOf("HKK", "KKK", "KKK")
private val EYE_BLINK = listOf("...", "KKK", "...")
private val EYE_HAPPY = listOf(".K.", "K.K", "...")  // ^
private val EYE_SHUT = listOf("...", "K.K", ".K.")   // ‿ sleeping, grooming, stretching, yawning
private val TONGUE = listOf("P", "P")
private val Z = listOf("HHHH", "..H.", ".H..", "HHHH")

/** What the pet is doing between the big states (asleep, greeting you, watching you browse). */
private enum class Act { Stand, Walk, Sit, Groom, Stretch, Yawn, Pounce, Hop }

/** A toy in canvas sprite pixels (not mirrored with the pet). */
private class Toy(val art: List<List<String>>, val x: Float, val y: Float, val frame: Int, val alpha: Float = 1f)

/**
 * A small 8-bit companion, seen side-on, that lives on the home screen.
 *
 * Every few seconds it picks something to do: stroll, sit, groom (cat licks a paw, dog scratches),
 * stretch and yawn, or play (cat pounces at a butterfly, dog boops a ball). When it finishes, it
 * turns its head to look at you. Using the remote interrupts it to watch the app list; after
 * 5 idle minutes (30 seconds late at night) it sleeps, and it wakes with a stretch and a happy hop.
 *
 * [wander] is how far, in sprite pixels, it may roam to the right of its spot.
 */
@Composable
fun Pet(kind: PetKind, lastInteraction: Long, greet: Int, wander: Int, modifier: Modifier = Modifier) {
    // Stepped animation only needs ~15 fps, which keeps the 8-bit feel and costs little on a TV.
    val t by produceState(0f) {
        val start = System.nanoTime()
        // Seconds, wrapped hourly: a Float counting up for weeks would lose the precision short animations need.
        while (true) { value = ((System.nanoTime() - start) / 1_000_000 % 3_600_000) / 1000f; tick(66) }
    }
    var happyUntil by remember { mutableLongStateOf(0L) }
    var lookUntil by remember { mutableLongStateOf(0L) }
    var wakeStart by remember { mutableLongStateOf(0L) }
    var wasAsleep by remember { mutableStateOf(false) }
    var act by remember { mutableStateOf(Act.Stand) }
    var actStart by remember { mutableLongStateOf(0L) }
    var posX by remember { mutableIntStateOf(0) }
    var facingLeft by remember { mutableStateOf(true) }
    var toy by remember { mutableStateOf<Toy?>(null) }

    // `t` is read below, so this body recomposes every frame and `now` stays fresh.
    val now = System.currentTimeMillis()
    val idle = now - lastInteraction
    val hour = LocalTime.now().hour
    val asleep = idle > if (hour >= 23 || hour < 6) 30_000 else 5 * 60_000
    val waking = !asleep && now - wakeStart < WAKE_MS
    val happy = !asleep && !waking && now < happyUntil
    val browsing = !asleep && !waking && !happy && idle < 2_000   // you're using the remote: watch the app list
    val looking = !asleep && !browsing && (happy || now < lookUntil)
    // `free`: nothing more important is going on. `ready`: free, and not busy looking at you.
    val free by rememberUpdatedState(!asleep && !waking && !happy && !browsing)
    val ready by rememberUpdatedState(!asleep && !waking && !happy && !browsing && !looking && idle > 4_000)

    fun greetNow() {
        happyUntil = System.currentTimeMillis() + 2_600
        lookUntil = happyUntil + 2_500                     // keep looking at you after the hop
    }
    LaunchedEffect(greet) { greetNow() }
    LaunchedEffect(asleep) {
        if (BuildConfig.DEBUG) Log.d("ZenPet", "${if (asleep) "fell asleep" else "awake"} after ${idle / 1000}s idle")
        if (!asleep && wasAsleep) {                        // wake up: stretch, yawn, then say hello
            wakeStart = System.currentTimeMillis()
            tick(WAKE_MS)
            greetNow()
        }
        wasAsleep = asleep
    }

    LaunchedEffect(kind, wander) {
        posX = posX.coerceIn(0, wander)
        toy = null                                         // a restart (new pet or spot) may cut an action short
        act = Act.Stand
        fun begin(a: Act) { act = a; actStart = System.currentTimeMillis() }

        /** Waits [ms], but gives up (false) as soon as something more important happens. */
        suspend fun hold(ms: Long): Boolean {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { if (!free) return false; tick(100) }
            return true
        }

        suspend fun walkTo(target: Int): Boolean {
            facingLeft = target < posX
            begin(Act.Walk)
            while (posX != target) {
                if (!free) return false
                posX += if (target > posX) 1 else -1
                tick(90)
            }
            return true
        }

        suspend fun stroll(): Boolean {
            val reach = min(wander, 40)
            val target = (posX + Random.nextInt(-reach, reach + 1)).coerceIn(0, wander)
            return if (abs(target - posX) < 5) hold(1_500) else walkTo(target)
        }

        suspend fun sit(): Boolean {
            begin(Act.Sit)
            if (!hold(Random.nextLong(6_000, 11_000))) return false
            lookUntil = System.currentTimeMillis() + 3_000  // look at you before getting up
            return hold(3_000)
        }

        suspend fun stretch(): Boolean {
            begin(Act.Stretch)
            if (!hold(1_400)) return false
            begin(Act.Yawn)
            return hold(1_200)
        }

        // Toys arrive from the right, so the pet turns to face them. Canvas x of its nose when facing right:
        fun nose() = (posX + PetSprites.WIDTH - 3).toFloat()

        suspend fun chaseButterfly(): Boolean {
            facingLeft = false
            begin(Act.Stand)
            val from = Offset(nose() + 24, 0f)
            val to = Offset(nose() - 1, 6f)
            val start = System.currentTimeMillis()
            while (true) {                                 // flutter in towards the cat's face
                val f = ((System.currentTimeMillis() - start) / 3_200f).coerceAtMost(1f)
                toy = Toy(PetSprites.BUTTERFLY, from.x + (to.x - from.x) * f, from.y + (to.y - from.y) * f + 2.5f * sin(f * 14), (t * 8).toInt() % 2)
                if (!free) return false
                if (f >= 1f) break
                tick(50)
            }
            begin(Act.Pounce)                              // leap...
            repeat(6) {
                if (!free) return false
                if (posX < wander) posX++
                tick(100)
            }
            begin(Act.Stand)
            val escape = toy ?: return true
            for (i in 1..24) {                             // ...and it gets away
                if (!free) return false
                toy = Toy(PetSprites.BUTTERFLY, escape.x + i * 1.3f, escape.y - i, i % 2, alpha = 1f - i / 24f)
                tick(50)
            }
            return true
        }

        suspend fun fetch(): Boolean {
            facingLeft = false
            begin(Act.Stand)
            val paws = nose() - 12                         // just in front of the front paws
            var bx = paws + 34
            while (bx > paws) {                            // ball rolls up to the dog
                if (!free) return false
                bx -= 1f
                toy = Toy(PetSprites.BALL, bx, PetSprites.HEIGHT - 8f, (bx / 3).toInt() % 2)
                tick(45)
            }
            begin(Act.Hop)                                 // boop!
            if (!hold(700)) return false
            begin(Act.Stand)
            while (bx < paws + 60) {                       // and off it goes
                if (!free) return false
                bx += 2f
                toy = Toy(PetSprites.BALL, bx, PetSprites.HEIGHT - 8f, (bx / 3).toInt() % 2)
                tick(40)
            }
            return true
        }

        while (true) {
            tick(Random.nextLong(2_500, 6_000))
            if (!ready) continue
            val roll = Random.nextInt(100)
            val done = when {
                roll < 30 -> stroll()
                roll < 55 -> sit()
                roll < 70 -> run { begin(Act.Groom); hold(3_600) }
                roll < 80 -> stretch()
                kind == PetKind.Cat -> chaseButterfly()
                else -> fetch()
            }
            toy = null
            begin(Act.Stand)
            if (done) lookUntil = System.currentTimeMillis() + Random.nextLong(2_500, 4_000) // done: look at you
        }
    }

    // ---------- pick this frame's pose ----------
    val cat = kind == PetKind.Cat
    val step = { period: Float -> floor(t / period).toInt() % 2 } // 0/1 frame toggle
    val elapsed = now - actStart
    val blink = t % 3.7f < 0.15f
    val shown = when {
        asleep -> Act.Stand
        waking -> if (now - wakeStart < 1_400) Act.Stretch else Act.Yawn
        happy -> Act.Stand
        browsing -> if (act == Act.Sit) Act.Sit else Act.Stand
        else -> act
    }
    val frontFace = !asleep && !waking && !browsing && looking && (shown == Act.Stand || shown == Act.Sit)
    val sprites = if (cat) CatPoses else DogPoses
    val pose: Pose = when {
        asleep -> Pose(sprites.sleep[step(1.8f)], PetSprites.EYES_SLEEP, EYE_SHUT, PetSprites.SHADOW_SLEEP)
        shown == Act.Walk -> Pose(sprites.walk[floor(t / 0.13f).toInt() % 4], PetSprites.EYES_SIDE, EYE_OPEN, PetSprites.SHADOW_STAND)
        shown == Act.Sit && frontFace -> Pose(sprites.sitLook[step(1.2f)], sprites.sitLookEyes, if (blink) EYE_BLINK else EYE_OPEN, PetSprites.SHADOW_SIT)
        shown == Act.Sit -> Pose(sprites.sit[step(1.2f)], PetSprites.EYES_SIT, if (blink) EYE_BLINK else EYE_OPEN, PetSprites.SHADOW_SIT)
        shown == Act.Groom -> Pose(sprites.groom[step(0.22f)], PetSprites.EYES_SIT, EYE_SHUT, PetSprites.SHADOW_SIT)
        shown == Act.Stretch -> Pose(sprites.stretch, PetSprites.EYES_STRETCH, EYE_SHUT, PetSprites.SHADOW_STRETCH)
        shown == Act.Yawn -> Pose(sprites.yawn, PetSprites.EYES_SIDE, EYE_SHUT, PetSprites.SHADOW_STAND)
        shown == Act.Pounce && cat -> Pose(PetSprites.CAT_POUNCE[0], PetSprites.EYES_POUNCE, EYE_OPEN, PetSprites.SHADOW_STAND)
        frontFace -> Pose(sprites.look[step(if (happy) 0.15f else 0.9f)], sprites.lookEyes, if (happy) EYE_HAPPY else if (blink) EYE_BLINK else EYE_OPEN, PetSprites.SHADOW_STAND)
        else -> Pose(sprites.stand[step(if (toy != null) 0.2f else 0.9f)], PetSprites.EYES_SIDE, if (blink) EYE_BLINK else EYE_OPEN, PetSprites.SHADOW_STAND)
    }
    val hop = when {
        happy -> if (step(0.18f) == 1) -3 else 0
        shown == Act.Hop -> if (step(0.15f) == 1) -3 else 0
        shown == Act.Pounce -> -(5 * sin(PI * (elapsed / 600.0).coerceIn(0.0, 1.0))).roundToInt()
        else -> 0
    }
    val faceLeft = if (browsing) true else facingLeft
    val currentToy = toy

    Canvas(modifier) {
        // Whole screen pixels per sprite pixel, snapped so edges stay crisp.
        val cell = floor(size.height / PetSprites.HEIGHT).coerceAtLeast(1f)
        val oy = floor(size.height - PetSprites.HEIGHT * cell)
        val pen = PixelPen(this, floor(posX * cell), oy, cell, flip = !faceLeft)

        val (from, until) = pose.shadow
        val inset = if (hop < -2) 4 else 1
        pen.rect(from + inset, PetSprites.HEIGHT - 1, until - from - 2 * inset, 1, Color.Black.copy(alpha = 0.35f))
        pen.frame(pose.frame, hop)
        for ((x, y) in pose.eyes) pen.sprite(pose.eyeArt, x, y + hop)
        if (happy && !cat && frontFace) pen.sprite(TONGUE, PetSprites.DOG_TONGUE.first, PetSprites.DOG_TONGUE.second + hop)

        if (asleep) for (i in 0..2) {
            val phase = (t / 2.4f + i / 3f) % 1f
            if (phase > 0.85f) continue
            val rise = floor(phase * 8).toInt()
            pen.sprite(Z, 18 + rise, 10 - rise * 2, alpha = 1f - phase)
        }
        currentToy?.let {
            PixelPen(this, 0f, oy, cell, flip = false).sprite(it.art[it.frame], it.x.roundToInt(), it.y.roundToInt(), it.alpha)
        }
    }
}

private const val WAKE_MS = 2_600L

private class Pose(val frame: List<String>, val eyes: List<Pair<Int, Int>>, val eyeArt: List<String>, val shadow: Pair<Int, Int>)

private class Poses(
    val stand: List<List<String>>, val walk: List<List<String>>, val look: List<List<String>>, val lookEyes: List<Pair<Int, Int>>,
    val sit: List<List<String>>, val sitLook: List<List<String>>, val sitLookEyes: List<Pair<Int, Int>>,
    val groom: List<List<String>>, val stretch: List<String>, val yawn: List<String>, val sleep: List<List<String>>,
)

private val CatPoses = with(PetSprites) {
    Poses(CAT_STAND, CAT_WALK, CAT_LOOK, EYES_CAT_LOOK, CAT_SIT, CAT_SIT_LOOK, EYES_CAT_SIT_LOOK, CAT_GROOM, CAT_STRETCH[0], CAT_YAWN[0], CAT_SLEEP)
}
private val DogPoses = with(PetSprites) {
    Poses(DOG_STAND, DOG_WALK, DOG_LOOK, EYES_DOG_LOOK, DOG_SIT, DOG_SIT_LOOK, EYES_DOG_SIT_LOOK, DOG_GROOM, DOG_STRETCH[0], DOG_YAWN[0], DOG_SLEEP)
}

/**
 * delay(), then wait for the next frame. Compose stops producing frames while Zen is off screen,
 * so every pet loop built on this simply pauses behind other apps instead of waking the CPU.
 */
internal suspend fun tick(ms: Long) {
    delay(ms)
    withFrameNanos { }
}

/** Each sprite frame rasterised once (keyed by identity: frames are the constant lists in PetSprites). */
private val frameBitmaps = IdentityHashMap<List<String>, ImageBitmap>()

private fun bitmapOf(rows: List<String>): ImageBitmap = frameBitmaps.getOrPut(rows) {
    val bitmap = Bitmap.createBitmap(PetSprites.WIDTH, PetSprites.HEIGHT, Bitmap.Config.ARGB_8888)
    rows.forEachIndexed { y, line -> line.forEachIndexed { x, ch -> PetPalette[ch]?.let { bitmap.setPixel(x, y, it.toArgb()) } } }
    bitmap.asImageBitmap()
}

/** Draws sprite cells as solid squares on an integer pixel grid, optionally mirrored to face right. */
private class PixelPen(
    private val scope: DrawScope,
    private val ox: Float,
    private val oy: Float,
    private val cell: Float,
    private val flip: Boolean,
) {
    fun rect(col: Int, row: Int, w: Int, h: Int, color: Color) {
        val x = if (flip) PetSprites.WIDTH - col - w else col
        scope.drawRect(color, Offset(ox + x * cell, oy + row * cell), Size(w * cell, h * cell))
    }

    /** A whole pose: one scaled bitmap (nearest-neighbour, so pixels stay crisp) instead of ~1,600 rects. */
    fun frame(rows: List<String>, row: Int) {
        val w = PetSprites.WIDTH * cell
        scope.scale(if (flip) -1f else 1f, 1f, pivot = Offset(ox + w / 2, 0f)) {
            drawImage(
                bitmapOf(rows),
                dstOffset = IntOffset(ox.toInt(), (oy + row * cell).toInt()),
                dstSize = IntSize(w.toInt(), (PetSprites.HEIGHT * cell).toInt()),
                filterQuality = FilterQuality.None,
            )
        }
    }

    fun sprite(lines: List<String>, col: Int, row: Int, alpha: Float = 1f) {
        lines.forEachIndexed { r, line ->
            line.forEachIndexed { c, ch ->
                PetPalette[ch]?.let { rect(col + c, row + r, 1, 1, if (alpha < 1f) it.copy(alpha = alpha) else it) }
            }
        }
    }
}
