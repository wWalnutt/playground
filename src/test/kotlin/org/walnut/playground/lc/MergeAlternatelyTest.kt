package org.walnut.playground.lc

import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.Test

class MergeAlternatelyTest {
    @Test
    fun test() {
        assertEquals("apbqcr", mergeAlternately("abc", "pqr"))
        assertEquals("apbqrs", mergeAlternately("ab", "pqrs"))
        assertEquals("apbqcd", mergeAlternately("abcd", "pq"))
    }

}