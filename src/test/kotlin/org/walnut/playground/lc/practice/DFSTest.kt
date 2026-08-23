package org.walnut.playground.lc.practice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DFSTest {
    @Test
    @DisplayName("normal DFS test")
    fun normalDFSTest() {
        //       1
        //     2     5
        //   3   4  6   7

        val nodeTree = Node(
            1,
            Node(2, Node(3), Node(4)),
            Node(5, Node(6), Node(7))
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), dfs(nodeTree))
    }

    @Test
    @DisplayName("lack DFS test")
    fun lackDFSTest() {
        //        1
        //     2     4
        //   X   3  5  X

        val nodeTree = Node(
            1,
            Node(2, null, Node(3)),
            Node(4, Node(5), null)
        )
        assertEquals(listOf(1, 2, 3, 4, 5), dfs(nodeTree))
    }

}