package org.walnut.playground.lc

import org.junit.jupiter.api.Assertions.*
import kotlin.test.Test

class CanPlaceFlowersTest {
    @Test
    fun test() {
        assertTrue(canPlaceFlowers(intArrayOf(1, 0, 0, 0, 1), 1))
        assertFalse(canPlaceFlowers(intArrayOf(1, 0, 0, 0, 1), 2))
    }

}