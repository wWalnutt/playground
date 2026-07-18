package org.walnut.playground.lc

fun kidsWithCandies(candies: IntArray, extraCandies: Int): List<Boolean> {
    val maxCandies = candies.max()
    return candies.map { candy ->candy + extraCandies >= maxCandies }
}