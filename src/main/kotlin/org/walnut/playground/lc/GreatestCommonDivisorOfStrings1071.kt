package org.walnut.playground.lc

import kotlin.math.min

fun gcdOfStrings(str1: String, str2: String): String {
    if(str1 + str2 != str2 + str1){
        return ""
    }
    val length = min(str1.length, str2.length)
    for (i in length downTo 1) {
        if(str1.length % i == 0 && str2.length % i == 0){
            val result = str1.substring(0,i)
            if(isDivides(result, str1) && isDivides(result, str2)){
                return result
            }
        }
    }
    return ""
}

private fun isDivides(t: String, s: String): Boolean {
    val n = s.length / t.length
    val result = StringBuilder()
    for (i in 0 until n) {
        result.append(t)
    }
    return result.toString() == s
}