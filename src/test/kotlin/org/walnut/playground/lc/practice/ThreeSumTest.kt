package org.walnut.playground.lc.practice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.collections.emptyList

class ThreeSumTest {
    @Test
    fun threeSumTest() {
        assertEquals(listOf(listOf(-1, -1, 2), listOf(-1, 0, 1)),ThreeSum(listOf(-1, 0, 1, 2, -1, -4)))
        assertEquals(emptyList<List<Int>>(), ThreeSum(listOf(0, 1, 1)))
        assertEquals(listOf(listOf(0, 0, 0)),ThreeSum(listOf(0, 0, 0)))
    }
}