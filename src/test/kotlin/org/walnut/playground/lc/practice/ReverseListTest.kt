package org.walnut.playground.lc.practice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ReverseListTest {
    @Test
    @DisplayName("should reverse muti node list")
    fun mutiNodeTest() {
        val head = listNodeOf(1,2,3,4,5)
        val resered = reverseList(head)
        assertEquals(listOf(5,4,3,2,1), toList(resered))
    }

    @Test
    @DisplayName("should reverse two node list")
    fun twoNodeTest() {
        val head = listNodeOf(1,2)
        val resered = reverseList(head)
        assertEquals(listOf(2,1), toList(resered))
    }

    @Test
    @DisplayName("should reverse single node list")
    fun singleNodeTest() {
        val head = listNodeOf(1)
        val resered = reverseList(head)
        assertEquals(listOf(1), toList(resered))
    }

    @Test
    @DisplayName("should reverse null node list")
    fun nullNodeTest() {
        val head = listNodeOf()
        val resered = reverseList(head)
        assertEquals(emptyList<Int>(), toList(resered))
    }
    @Test
    @DisplayName("should reverse duplicate node list")
    fun duplicateNodeTest() {
        val head = listNodeOf(1,2,2,3,3)
        val resered = reverseList(head)
        assertEquals(listOf(3,3,2,2,1), toList(resered))
    }

    fun listNodeOf(vararg values: Int): ListNode? {
        val dummy = ListNode(0)
        var curr = dummy
        for (value in values) {
            val new = ListNode(value)
            curr.next = new
            curr = new
        }
        return dummy.next
    }

    fun toList(head: ListNode?): List<Int> {
        var result = mutableListOf<Int>()
        var curr = head
        while(curr != null) {
            result.add(curr.value)
        }
        return result
    }

}