package org.walnut.playground.lc

fun mergeAlternately(word1: String, word2: String): String {
    val result = StringBuilder()
    val lengthOfWord1 = word1.length
    val lengthOfWord2 = word2.length
    val minLength = minOf(lengthOfWord1, lengthOfWord2)
    for (i in 0 until minLength) {
        result.append(word1[i])
        result.append(word2[i])
    }
    if(lengthOfWord1 > minLength) {
        result.append(word1.substring(minLength))
    }
    if(lengthOfWord2 > minLength) {
        result.append(word2.substring(minLength))
    }
    return result.toString()
}
