package org.walnut.playground.lc

import org.junit.jupiter.api.Assertions.*
import kotlin.test.Test

class GcdOfStringsTest {
    @Test
    fun test() {
        assertEquals("ABC", gcdOfStrings("ABCABC", "ABC"))
        assertEquals("AB", gcdOfStrings("ABABAB", "ABAB"))
        assertEquals("", gcdOfStrings("LEET", "CODE"))
        assertEquals("", gcdOfStrings("AAAAAB", "AAA"))
    }

}