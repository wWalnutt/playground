package org.walnut.playground.lc

import java.util.HashMap

fun twoSums(nums: IntArray?, target: Int): IntArray {
    if (nums == null || nums.size < 2) {
        return intArrayOf()
    }
    val pairHashMap = HashMap<Int, Int>()

    for (i in nums.indices) {
        val expectedNum = target - nums[i]
        val partnerIndex = pairHashMap[expectedNum]
        if (partnerIndex != null) {
            return intArrayOf(partnerIndex, i)
        }
        pairHashMap[nums[i]] = i
    }

    return intArrayOf()
}