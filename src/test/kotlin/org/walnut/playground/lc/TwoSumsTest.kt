package org.walnut.playground.lc

import org.junit.jupiter.api.Assertions
import kotlin.test.Test

class TwoSumsTest {
    @Test
    fun test() {
        Assertions.assertEquals(0, twoSums(intArrayOf(2, 7, 11, 15), 9)[0])
        Assertions.assertEquals(1, twoSums(intArrayOf(2, 7, 11, 15), 9)[1])
        Assertions.assertEquals(1, twoSums(intArrayOf(2, 7, 11, 15), 18)[0])
        Assertions.assertEquals(2, twoSums(intArrayOf(2, 7, 11, 15), 18)[1])
    }

}