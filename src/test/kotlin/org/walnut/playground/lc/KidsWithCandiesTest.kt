package org.walnut.playground.lc

import org.junit.jupiter.api.Assertions.*
import kotlin.test.Test

class KidsWithCandiesTest {
    @Test
    fun test() {
        assertEquals(listOf(true, true, true, false, true), kidsWithCandies(intArrayOf(2, 3, 5, 1, 3), 3))
        assertEquals(listOf(true, false, false, false, false), kidsWithCandies(intArrayOf(4, 2, 1, 1, 2), 1))
        assertEquals(listOf(true, false, true), kidsWithCandies(intArrayOf(12, 1, 12), 10))
    }

}