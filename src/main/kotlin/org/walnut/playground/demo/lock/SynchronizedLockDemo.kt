package org.walnut.playground.demo.lock

import kotlin.concurrent.thread

class SynchronizedLockDemo {

    class Counter {
        private var count = 0
        private val startMs = System.currentTimeMillis()

        private fun ts(): String = "${System.currentTimeMillis() - startMs}ms"

        fun increment() {
            println("[${ts()}][${Thread.currentThread().name}] increment: trying lock")
            synchronized(this) {
                println("[${ts()}][${Thread.currentThread().name}] increment: got lock")
                Thread.sleep(300)
                count++
                println("[${ts()}][${Thread.currentThread().name}] increment: release lock, count=$count")
            }
        }

        fun get(): Int {
            println("[${ts()}][${Thread.currentThread().name}] get: trying lock")
            return synchronized(this) {
                println("[${ts()}][${Thread.currentThread().name}] get: got lock")
                Thread.sleep(300)
                println("[${ts()}][${Thread.currentThread().name}] get: release lock, count=$count")
                count
            }
        }
    }
}

fun runSynchronizedLockDemo() {
    val counter = SynchronizedLockDemo.Counter()

    val t1 = thread(name = "T-increment") {
        repeat(5) {
            counter.increment()
            Thread.sleep(120)
        }
    }
    val t2 = thread(name = "T-get") {
        repeat(5) {
            counter.get()
            Thread.sleep(80)
        }
    }

    t1.join()
    t2.join()

    println("final count = ${counter.get()}")
}