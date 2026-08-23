package org.walnut.playground.lc.practice

fun ThreeSum(input: List<Int>): List<List<Int>>{
    if (input.isEmpty() || input.size < 3) return listOf()
    val nums = input.sorted()
    val n = nums.size
    val result = mutableListOf<List<Int>>()

    for (i in nums.indices) {
        if(nums[i] > 0 ) break

        if (i > 0 && nums[i] == nums[i - 1]) continue

        var left = i + 1
        var right = n - 1

        while(left < right){
            val sum = nums[i] + nums[left] + nums[right]
            when{
                sum == 0 -> {
                    result.add(listOf(nums[i], nums[left] ,nums[right]))
                    while(left < right && nums[left] == nums[left+1])left++
                    while(left < right && nums[right] == nums[right-1])right--
                    left++
                    right--
                }
                sum < 0 -> left++
                else -> right--
            }
        }
    }
    return result
}
