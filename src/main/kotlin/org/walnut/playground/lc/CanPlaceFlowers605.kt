package org.walnut.playground.lc

fun canPlaceFlowers(flowerbed: IntArray, n: Int): Boolean {
    val ableToFlowerbed = IntArray(n)
    var last = 0
    for (i in 0 until flowerbed.size - n) {
        if(flowerbed[i] == 0 && flowerbed[i + 1] == 0 && last == 0){
            last++
            last = 1
        }
    }
    return last >= n
}