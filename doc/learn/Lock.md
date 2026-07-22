# 后端并发与数据库锁全景速查笔记

---

## 🧠 一、锁的思想分类（并发控制哲学）

| 锁类型 | 核心思想 | 实现方式 | 适用场景 |
| :--- | :--- | :--- | :--- |
| **悲观锁 (Pessimistic)** | 假定**一定会发生冲突**，操作前先占住锁并阻塞其他线程/事务。 | MySQL `FOR UPDATE` / Java `synchronized` / `ReentrantLock` | **写多读少、高并发强冲突**，以及资损敏感的强一致场景（如扣款）。 |
| **乐观锁 (Optimistic)** | 假定**大概率不会冲突**，读写阶段不加锁，提交时校验是否被并发修改。 | 数据库 `version` 字段 / Java `CAS` / Redis `WATCH` | **读多写少、冲突率低**场景（如用户资料更新）。 |

---

## ☕ 二、JVM 应用层锁（Java 线程并发锁）

### 1. `synchronized`（JVM 原生锁）

- **特性**：隐式加锁与释放、可重入。
- **锁升级（膨胀）过程**：
  - `无锁` → `偏向锁`（单线程偏向）→ `轻量级锁`（CAS 自旋）→ `重量级锁`（Mutex，线程挂起排队）。
  - *注：偏向锁在 JDK 15 后已废弃。*

### 2. `ReentrantLock`（显式 API 锁，基于 AQS）

- **特性**：需手动 `lock()` / `unlock()`，`unlock()` 必须放在 `finally` 中。
- **高级能力**：
  1. **公平/非公平可选**：`new ReentrantLock(true)` 为公平锁（吞吐通常更低）。
  2. **可中断等待**：`lockInterruptibly()` 可响应中断。
  3. **条件队列**：配合 `Condition` 做多条件等待与定向唤醒（`signal()`）。

### 3. `ReadWriteLock`（如 `ReentrantReadWriteLock`）

- **规则**：读读共享、读写互斥、写写互斥。
- **价值**：适合“读多写少”，可显著提升并发读取吞吐。

---

## 🛢️ 三、MySQL 数据库锁（InnoDB）

### 1. 按独占性划分

- **共享锁 (S 锁 / 读锁)**：`SELECT ... LOCK IN SHARE MODE`，可并发读，阻塞写。
- **排他锁 (X 锁 / 写锁)**：`SELECT ... FOR UPDATE`、`UPDATE`、`DELETE`、`INSERT`，独占资源。

### 2. InnoDB 行锁算法（RR 隔离级别）

```text
                     ┌── Record Lock (记录锁) ─── 锁单条索引记录
                     │
InnoDB 行锁分类 ──────┼── Gap Lock (间隙锁) ─────── 锁索引开区间 (a, b)，不含记录本身
                     │
                     └── Next-Key Lock (临键锁) ── Record Lock + Gap Lock，左开右闭 (a, b]
```

- **Record Lock（记录锁）**：仅锁住命中的单条索引记录。
- **Gap Lock（间隙锁）**：锁住索引间隙，阻止其他事务插入，避免幻读。
- **Next-Key Lock（临键锁）**：记录锁 + 间隙锁，是 InnoDB 默认行锁算法。等值命中且记录存在时，可能退化为记录锁。

---

## 🌐 四、分布式锁（跨服务/跨节点）

### 1. Redis 分布式锁（SETNX / Redisson）

- **原子命令**：`SET key value NX EX 30`（不存在才设值 + 过期时间一次完成）。
- **三大防坑点**：
  1. **唯一标识 (UUID)**：`value` 存线程唯一 ID，释放锁时用 Lua 先比对再 `DEL`，防误删。
  2. **看门狗续期 (Watchdog)**：如 Redisson，在业务未完成时自动续 TTL，避免锁提前失效。
  3. **Redlock**：多主 Redis 下，超过半数节点加锁成功才算成功（`N/2 + 1`）。

### 2. ZooKeeper 分布式锁

- **原理**：临时顺序节点（Ephemeral Sequential）+ Watcher。
- **特点**：
  - 最小序号节点获得锁。
  - 未获得锁的节点只监听前一个节点，避免羊群效应。
  - 节点异常断开后临时节点自动删除，天然降低死锁风险。

---

## ⚠️ 五、锁的高频坑点与解法

### 1. ABA 问题（乐观锁 / CAS）

- **现象**：值从 A 改到 B 又改回 A，CAS 误判“未变化”。
- **解法**：引入版本号（如 `AtomicStampedReference` 或数据库 `version` 字段）。

### 2. 死锁（Deadlock）

- **产生条件**：互斥、占有且等待、不可抢占、循环等待。
- **解法**：
  - 统一加锁顺序（破坏循环等待）。
  - 配置超时（如 MySQL `innodb_lock_wait_timeout`）。