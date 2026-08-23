package org.walnut.playground.lc.practice

fun reverseList(head: ListNode?): ListNode? {
    if (head == null) return null

    var prev: ListNode? = null
    var curr = head

    while (curr != null) {
        val nextTemp = curr.next
        curr.next = prev
        prev = curr
        curr = nextTemp
    }
    return prev

}

class ListNode(
    var value: Int,
    var next: ListNode? = null
)