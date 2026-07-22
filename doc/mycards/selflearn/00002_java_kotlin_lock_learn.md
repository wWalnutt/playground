# 学习 Java/Kotlin 锁并产出演示代码

本卡目标是系统学习 Java/Kotlin 并发锁概念，并在代码库中产出可运行 demo 与学习笔记，形成“概念 + 实操 + 文档”闭环。

---

## 一、Context（背景）

### 1. Feature 背景

并发控制是后端基础能力。仅理解概念不足以支撑实际开发，需要通过可运行示例理解锁的互斥、可重入、等待/唤醒、公平性与中断响应等行为差异。

### 2. 本卡背景

本卡聚焦个人自学任务：围绕 `synchronized` 与 `ReentrantLock` 做对照学习，输出两类代码 demo，并沉淀一份锁知识笔记，供后续复习和扩展（读写锁、分布式锁）使用。

---

## 二、In Scope / Out of Scope（边界）

### In Scope（做什么）

- 学习 Java/Kotlin 常见锁概念（悲观/乐观、JVM 锁、可重入锁核心能力）
- 编写并整理 Kotlin demo：
  - `SynchronizedLockDemo.kt`
  - `ReentrantLockDemo.kt`
- 在 demo 中展示关键并发行为（互斥、tryLock、可中断、公平锁、Condition）
- 输出锁学习笔记 Markdown 文件：`doc/learn/Lock.md`

### Out of Scope（不做什么）

- 不覆盖生产级压测和性能对比基准
- 不实现完整分布式锁工程（Redis/ZK 实战）
- 不引入额外并发框架（如协程 Mutex 深入专题）
- 不在本卡内完成并发问题全量单元测试体系

---

## 三、Acceptance Criteria（验收标准）

**AC1 - 概念学习可追踪**
- **Given** 学习任务开始
- **When** 检查学习产物
- **Then** 存在结构化锁笔记，覆盖锁分类、JVM 锁、数据库锁、分布式锁和常见坑点

**AC2 - synchronized demo 可运行**
- **Given** `SynchronizedLockDemo.kt` 已实现
- **When** 运行 demo
- **Then** 可观察到线程并发启动，但同一把锁临界区串行进入

**AC3 - ReentrantLock demo 可运行**
- **Given** `ReentrantLockDemo.kt` 已实现
- **When** 运行 demo
- **Then** 能展示 `tryLock`、`lockInterruptibly`、公平锁、`Condition` 四项能力

**AC4 - 文档与代码一致**
- **Given** demo 与笔记均已存在
- **When** 对照检查
- **Then** 笔记中的锁概念能在 demo 中找到对应行为样例

---

## 四、Tech 实现参考

### 代码位置

```text
src/main/kotlin/org/walnut/playground/demo/lock/SynchronizedLockDemo.kt
src/main/kotlin/org/walnut/playground/demo/lock/ReentrantLockDemo.kt
doc/learn/Lock.md
```

### 推荐实现要点

- `synchronized` demo：
  - 统一锁对象（`this` 或私有锁对象）
  - 在锁外打印 `trying lock`，锁内打印 `got/release lock`，便于观察并发与互斥
- `ReentrantLock` demo：
  - `tryLock()`：竞争失败立即返回
  - `lockInterruptibly()`：等待锁时可中断
  - `ReentrantLock(true)`：公平锁排队获取
  - `Condition.await()/signal()`：细粒度等待/唤醒

### 学习输出建议

- 每个 demo 保持“最小可运行 + 输出可解释”
- 笔记以“概念定义 + 场景 + 注意点”组织，避免只贴代码

---

## 五、Open Questions（待澄清问题）

---

## 六、Self Check（自检清单）

### 学习内容
- [ ] 锁核心概念已完成结构化整理
- [ ] 能口头解释 `synchronized` 与 `ReentrantLock` 的关键差异

### 代码产出
- [ ] `SynchronizedLockDemo.kt` 可运行且输出可读
- [ ] `ReentrantLockDemo.kt` 四项能力均可观察
- [ ] demo 代码结构清晰，无无效分支

### 文档产出
- [ ] `doc/learn/Lock.md` 已完成并可独立阅读
- [ ] 文档内容与当前 demo 行为一致
- [ ] 后续扩展点（如读写锁）已在卡中留痕