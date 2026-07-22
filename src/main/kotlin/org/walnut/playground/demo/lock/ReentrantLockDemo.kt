package org.walnut.playground.demo.lock

import java.util.concurrent.locks.ReentrantLock

class ReentrantLockDemo {
    private var balance = 0
    private val lock = ReentrantLock()

    fun deposit(amount: Int) {
        lock.lock()
        try {
            balance += amount
        } finally {
            lock.unlock()
        }
    }

    fun withdraw(amount: Int): Boolean {
        // 尝试获取锁，失败立即返回 false
        return lock.tryLock().let { acquired ->
            if (acquired) {
                try {
                    if (balance >= amount) {
                        balance -= amount
                        true
                    } else false
                } finally {
                    lock.unlock()
                }
            } else false
        }
    }
}