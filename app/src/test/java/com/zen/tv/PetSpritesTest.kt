package com.zen.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the generated sprite data (tools/pet_sprites.py) against shape and palette mistakes. */
class PetSpritesTest {
    private val poses = mapOf(
        "CAT_STAND" to PetSprites.CAT_STAND, "CAT_WALK" to PetSprites.CAT_WALK, "CAT_LOOK" to PetSprites.CAT_LOOK,
        "CAT_YAWN" to PetSprites.CAT_YAWN, "CAT_SIT" to PetSprites.CAT_SIT, "CAT_SIT_LOOK" to PetSprites.CAT_SIT_LOOK,
        "CAT_GROOM" to PetSprites.CAT_GROOM, "CAT_STRETCH" to PetSprites.CAT_STRETCH, "CAT_SLEEP" to PetSprites.CAT_SLEEP,
        "CAT_POUNCE" to PetSprites.CAT_POUNCE,
        "DOG_STAND" to PetSprites.DOG_STAND, "DOG_WALK" to PetSprites.DOG_WALK, "DOG_LOOK" to PetSprites.DOG_LOOK,
        "DOG_YAWN" to PetSprites.DOG_YAWN, "DOG_SIT" to PetSprites.DOG_SIT, "DOG_SIT_LOOK" to PetSprites.DOG_SIT_LOOK,
        "DOG_GROOM" to PetSprites.DOG_GROOM, "DOG_STRETCH" to PetSprites.DOG_STRETCH, "DOG_SLEEP" to PetSprites.DOG_SLEEP,
    )

    @Test
    fun everyFrameIsTheDeclaredSize() {
        for ((name, frames) in poses) {
            assertTrue("$name has no frames", frames.isNotEmpty())
            frames.forEachIndexed { i, rows ->
                assertEquals("$name[$i] row count", PetSprites.HEIGHT, rows.size)
                rows.forEachIndexed { r, row -> assertEquals("$name[$i] row $r width", PetSprites.WIDTH, row.length) }
            }
        }
    }

    @Test
    fun everyPixelUsesAKnownColour() {
        val allowed = PetPalette.keys + '.'
        val frames = poses.values.flatten() + PetSprites.BUTTERFLY + PetSprites.BALL
        for (rows in frames) for (row in rows) for (ch in row) {
            assertTrue("unknown sprite colour '$ch'", ch in allowed)
        }
    }

    @Test
    fun animationsHaveTheFramesPetKtCyclesThrough() {
        assertEquals(4, PetSprites.CAT_WALK.size)
        assertEquals(4, PetSprites.DOG_WALK.size)
        for (two in listOf(PetSprites.CAT_STAND, PetSprites.CAT_SIT, PetSprites.CAT_SLEEP, PetSprites.DOG_GROOM, PetSprites.DOG_LOOK)) {
            assertEquals(2, two.size)
        }
        assertEquals(2, PetSprites.BUTTERFLY.size)
        assertEquals(2, PetSprites.BALL.size)
    }

    @Test
    fun eyesAndTongueFitInsideTheSprite() {
        val anchors = listOf(
            PetSprites.EYES_SIDE, PetSprites.EYES_SIT, PetSprites.EYES_STRETCH, PetSprites.EYES_POUNCE, PetSprites.EYES_SLEEP,
            PetSprites.EYES_CAT_LOOK, PetSprites.EYES_DOG_LOOK, PetSprites.EYES_CAT_SIT_LOOK, PetSprites.EYES_DOG_SIT_LOOK,
        ).flatten() + PetSprites.DOG_TONGUE
        for ((x, y) in anchors) {
            assertTrue("($x,$y) x out of range", x >= 0 && x + 3 <= PetSprites.WIDTH)
            assertTrue("($x,$y) y out of range", y >= 0 && y + 3 <= PetSprites.HEIGHT)
        }
    }

    @Test
    fun shadowsSpanThePet() {
        for ((from, until) in listOf(PetSprites.SHADOW_STAND, PetSprites.SHADOW_SIT, PetSprites.SHADOW_STRETCH, PetSprites.SHADOW_SLEEP)) {
            assertTrue(from in 0 until until && until <= PetSprites.WIDTH)
            assertTrue("shadow too narrow for Pet.kt's inset", until - from > 8)
        }
    }
}
