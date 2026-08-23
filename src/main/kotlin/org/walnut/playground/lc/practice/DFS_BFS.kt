package org.walnut.playground.lc.practice

fun dfs(nodeTree: Node): List<Int> {
    val root = nodeTree
    var result = mutableListOf<Int>()

    result.add(root.value)
    if(root.left != null) result = (result + dfs(root.left)) as MutableList<Int>
    if(root.right != null) result = (result + dfs(root.right)) as MutableList<Int>
    return result
}

fun bfs(nodeTree: Node): List<Int> {
    val root = nodeTree
    val result = mutableListOf<Int>()
    val queue = ArrayDeque<Node>()
    queue.add(root)
    while (!queue.isEmpty()) {
        val node = queue.removeFirst()
        result.add(node.value)
        if(node.left != null) queue.add(node.left)
        if(node.right != null) queue.add(node.right)
    }
    return result
}

fun bfsByDfs(nodeTree: Node): List<Int> {
    val result = mutableListOf<MutableList<Int>>()
    bfsByDfsFun(nodeTree, 0, result)
    return result.toList().flatten()
}

private fun bfsByDfsFun(
    node: Node?,
    depth: Int,
    result: MutableList<MutableList<Int>>
) {
    if (node == null) return

    if (depth == result.size) {
        result.add(mutableListOf())
    }

    result[depth].add(node.value)

    bfsByDfsFun(node.left, depth + 1, result)
    bfsByDfsFun(node.right, depth + 1, result)
}

class Node(
    val value: Int,
    val left: Node? = null,
    val right: Node? = null
)