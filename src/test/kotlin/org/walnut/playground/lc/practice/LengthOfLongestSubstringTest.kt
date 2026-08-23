package org.walnut.playground.lc.practice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LengthOfLongestSubstringTest {
    @Test
    fun test() {
        assertEquals(3,lengthOfLongestSubstring("abcabcbb"))
        assertEquals(1,lengthOfLongestSubstring("bbbbb"))
        assertEquals(3,lengthOfLongestSubstring("pwwkew"))
    }
}