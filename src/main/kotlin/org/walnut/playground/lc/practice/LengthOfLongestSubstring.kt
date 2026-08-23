package org.walnut.playground.lc.practice

fun lengthOfLongestSubstring(string: String): Int {
    if (string.isEmpty()) return 0
    var max = 0
    var left = 0
    val lastMap = mutableMapOf<Char, Int>()
    for (right in string.indices) {
        val current = string[right]
        val lastIndex = lastMap[current]
        if (lastIndex != null && lastIndex >= left) {
            left = lastIndex + 1
        }
        lastMap[current] = right
        max = maxOf(max, right - left + 1)
    }
    return max
}
