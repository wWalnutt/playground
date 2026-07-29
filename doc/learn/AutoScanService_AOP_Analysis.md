# AutoScanService AOP 分析文档

## 1. 问题背景

当前项目中有两个 Service：`AutoScanService` 和 `AutoScanRunner`。问题是：**如果把这两个 Service 合并成一个，@Async 异步逻辑会失效吗？**

答案：**是的，会失效。**

原因：Spring AOP 代理的工作原理导致同类内部方法调用无法被代理拦截。

---

## 2. 原始设计（✅ 正确）

### 2.1 代码结构

#### AutoScanService（异步协调层）

```kotlin
@Service
class AutoScanService(
    private val autoScanRunner: AutoScanRunner,
) {
    private val logger = KotlinLogging.logger {}

    @Async  // ← 标记为异步方法
    fun triggerAutoScan(
        startDate: LocalDate?,
        endDate: LocalDate?,
    ) {
        try {
            autoScanRunner.run(startDate, endDate)  // ← 调用另一个 Service
        } catch (exception: Exception) {
            logger.error(exception) {
                "[AutoScan Job] Unexpected error during batch execution"
            }
        }
    }
}
```

#### AutoScanRunner（核心逻辑层）

```kotlin
@Service
class AutoScanRunner(
    private val dealClient: DealClient,
    private val dealRepository: DealRepository,
    private val eventRouterKafkaPublisher: CustomerOrderEventRouterKafkaPublisher,
    @param:Value("\${otr.customerorder.autoScan.processing.freezeApproachingDays:14}")
    private val freezeApproachingDays: Int,
) {
    private val logger = KotlinLogging.logger {}

    fun run(startDate: LocalDate?, endDate: LocalDate?) {
        val startDate = startDate ?: LocalDate.now()
        val endDate = endDate ?: startDate.plusDays(freezeApproachingDays.toLong())

        logger.info {
            "[AutoScan Job] started startDate=$startDate, endDate=$endDate"
        }

        val candidates = fetchNormalizedCandidates(startDate, endDate) ?: return
        if (candidates.isEmpty()) {
            logger.info {
                "[AutoScan Job] No valid candidates found startDate=$startDate, endDate=$endDate"
            }
            return
        }

        val dealsById = dealRepository.findAllById(candidates.map { it.dealId }).associateBy { it.id }
        val eligibleCandidates =
            candidates.filter { candidate ->
                dealsById[candidate.dealId]?.isAutoScanTriggered?.technicalValidationTriggered != true
            }
        val skippedAlreadyScanned = candidates.size - eligibleCandidates.size

        val results =
            eligibleCandidates.map { candidate ->
                processCandidate(candidate, dealsById[candidate.dealId])
            }

        logger.info {
            "[AutoScan Job] completed " +
                "startDate=$startDate, endDate=$endDate, " +
                "total=${candidates.size}, eligible=${eligibleCandidates.size}, " +
                "skippedAlreadyScanned=$skippedAlreadyScanned, " +
                "success=${results.count { it }}, failed=${results.count { !it }}"
        }
    }

    private fun processCandidate(
        candidate: AutoScanCandidate,
        deal: Deal?,
    ): Boolean =
        runCatching {
            requireNotNull(deal) { "Deal ${candidate.dealId} not found in customer-order database" }
            eventRouterKafkaPublisher.publishAutoScanEvent(candidate.dealId, candidate.amendableUntil)
            dealRepository.save(
                deal.copy(
                    isAutoScanTriggered = deal.isAutoScanTriggered.copy(technicalValidationTriggered = true),
                ),
            )
            logger.info {
                "[AutoScan Job] Published AUTO_SCAN event for dealId=${candidate.dealId}"
            }
            true
        }.getOrElse { exception ->
            logger.error(exception) {
                "[AutoScan Job] Failed to process auto scan for dealId=${candidate.dealId}"
            }
            false
        }

    private fun fetchNormalizedCandidates(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<AutoScanCandidate>? =
        try {
            normalizeCandidates(dealClient.getDealsApproachingFreeze(startDate, endDate))
        } catch (exception: Exception) {
            logger.error(exception) {
                "[AutoScan Job] Abort batch because fetching deal candidates failed"
            }
            null
        }

    private fun normalizeCandidates(deals: List<Deal>): List<AutoScanCandidate> = 
        // 去重逻辑
}
```

### 2.2 设计要点

| 特性 | AutoScanService | AutoScanRunner |
|------|-----------------|----------------|
| 职责 | 异步协调 + 异常处理 | 核心业务逻辑 |
| @Async | ✅ 有（triggerAutoScan） | ✗ 无 |
| 调用关系 | 调用 autoScanRunner.run() | 被 Service 调用 |
| 依赖 | 依赖 AutoScanRunner | 依赖 DealClient、Repository、Publisher |
| 可重用性 | 只能异步调用 | 可被任何地方同步或异步调用 |

---

## 3. 合并后的设计（❌ 错误）

如果把两个 Service 合并成一个：

```kotlin
@Service
class AutoScanService(
    private val dealClient: DealClient,
    private val dealRepository: DealRepository,
    private val eventRouterKafkaPublisher: CustomerOrderEventRouterKafkaPublisher,
    @param:Value("\${otr.customerorder.autoScan.processing.freezeApproachingDays:14}")
    private val freezeApproachingDays: Int,
) {
    private val logger = KotlinLogging.logger {}

    // 入口方法：API 调用这个
    fun triggerAutoScan(
        startDate: LocalDate?,
        endDate: LocalDate?,
    ) {
        try {
            run(startDate, endDate)  // ← 内部调用，注意：这里是 this.run()
        } catch (exception: Exception) {
            logger.error(exception) {
                "[AutoScan Job] Unexpected error during batch execution"
            }
        }
    }

    // 核心方法：标记异步
    @Async  // ← @Async 标记在这里
    private fun run(startDate: LocalDate?, endDate: LocalDate?) {
        val startDate = startDate ?: LocalDate.now()
        val endDate = endDate ?: startDate.plusDays(freezeApproachingDays.toLong())

        logger.info {
            "[AutoScan Job] started startDate=$startDate, endDate=$endDate"
        }

        val candidates = fetchNormalizedCandidates(startDate, endDate) ?: return
        if (candidates.isEmpty()) {
            logger.info {
                "[AutoScan Job] No valid candidates found startDate=$startDate, endDate=$endDate"
            }
            return
        }

        val dealsById = dealRepository.findAllById(candidates.map { it.dealId }).associateBy { it.id }
        val eligibleCandidates =
            candidates.filter { candidate ->
                dealsById[candidate.dealId]?.isAutoScanTriggered?.technicalValidationTriggered != true
            }
        val skippedAlreadyScanned = candidates.size - eligibleCandidates.size

        val results =
            eligibleCandidates.map { candidate ->
                processCandidate(candidate, dealsById[candidate.dealId])
            }

        logger.info {
            "[AutoScan Job] completed " +
                "startDate=$startDate, endDate=$endDate, " +
                "total=${candidates.size}, eligible=${eligibleCandidates.size}, " +
                "skippedAlreadyScanned=$skippedAlreadyScanned, " +
                "success=${results.count { it }}, failed=${results.count { !it }}"
        }
    }

    private fun processCandidate(candidate: AutoScanCandidate, deal: Deal?): Boolean =
        runCatching {
            requireNotNull(deal) { "Deal ${candidate.dealId} not found" }
            eventRouterKafkaPublisher.publishAutoScanEvent(candidate.dealId, candidate.amendableUntil)
            dealRepository.save(
                deal.copy(
                    isAutoScanTriggered = deal.isAutoScanTriggered.copy(technicalValidationTriggered = true),
                ),
            )
            logger.info {
                "[AutoScan Job] Published AUTO_SCAN event for dealId=${candidate.dealId}"
            }
            true
        }.getOrElse { exception ->
            logger.error(exception) { "[AutoScan Job] Failed to process auto scan" }
            false
        }

    private fun fetchNormalizedCandidates(startDate: LocalDate, endDate: LocalDate): List<AutoScanCandidate>? =
        try {
            normalizeCandidates(dealClient.getDealsApproachingFreeze(startDate, endDate))
        } catch (exception: Exception) {
            logger.error(exception) {
                "[AutoScan Job] Abort batch because fetching deal candidates failed"
            }
            null
        }

    private fun normalizeCandidates(deals: List<Deal>): List<AutoScanCandidate> = 
        // 去重逻辑
}
```

---

## 4. AOP 原理分析

### 4.1 什么是 Spring AOP？

Spring AOP（面向切面编程）通过**动态代理**在运行时为 Bean 创建一个代理对象。

**核心概念：**
- **原始类**：开发者编写的真实类
- **代理类**：Spring 动态生成的包装类（CGLIB）
- **拦截**：代理类在调用方法前后进行拦截和处理

### 4.2 @EnableAsync 的工作流程

#### 第 1 步：启动扫描

```
Spring 应用启动
  ↓
扫描 @EnableAsync 注解
  ↓
AsyncAnnotationBeanPostProcessor 激活
  ↓
遍历所有 @Service 类
  ├─ 检查每个方法是否有 @Async
  └─ 如果有，标记该 Bean 需要代理
```

#### 第 2 步：CGLIB 代理生成

对于**原始设计**：

```kotlin
// 原始类
@Service
class AutoScanService(private val autoScanRunner: AutoScanRunner) {
    @Async
    fun triggerAutoScan() { }
}

// Spring 生成的代理类
class AutoScanService$$EnhancerBySpringCGLIB_0 extends AutoScanService {
    private val executor: Executor = // 线程池
    
    override fun triggerAutoScan() {
        // 代理方法：检查 @Async 并异步处理
        val task = Runnable {
            super.triggerAutoScan()  // 调用原始方法
        }
        executor.execute(task)  // 提交到线程池
        return void  // 立即返回
    }
}
```

对于**合并后的设计**：

```kotlin
// 原始类
@Service
class AutoScanService {
    fun triggerAutoScan() { }    // 无 @Async
    @Async
    private fun run() { }        // 有 @Async
}

// Spring 生成的代理类
class AutoScanService$$EnhancerBySpringCGLIB_0 extends AutoScanService {
    private val executor: Executor = // 线程池
    
    // 方法 1：无 @Async，代理不处理
    override fun triggerAutoScan() {
        super.triggerAutoScan()  // ← 直接转发到原始方法（无异步处理）
    }
    
    // 方法 2：有 @Async，代理处理
    override fun run() {
        val task = Runnable {
            super.run()  // 调用原始方法
        }
        executor.execute(task)  // 异步处理
        return void  // 立即返回
    }
}
```

#### 第 3 步：依赖注入

```kotlin
@RestController
class AutoScanController(
    private val autoScanService: AutoScanService  // ← 注入代理对象
) { }

// Spring 容器内：
// autoScanService = AutoScanService$$EnhancerBySpringCGLIB_0()
//                  （不是 AutoScanService）
```

### 4.3 关键差异：this 指向

#### 原始设计（✅ 正确）

```
调用流程：
[代理对象]
proxy.triggerAutoScan()
  ↓
[代理拦截]
代理检查：@Async? YES ✓
  ↓
[异步处理]
executor.execute(Runnable {
    super.triggerAutoScan()
        ↓
    [进入原始对象]
    autoScanRunner.run()
        ↓
    [调用另一个 Service 的代理]
    runner 是注入的 Bean
    runner.run() 会走 AutoScanRunner 的代理
})
  ↓
[立即返回]
return void

[后台线程]
真正执行核心逻辑...
```

**关键点：** `autoScanRunner.run()` 是对**另一个 Service 的代理对象**的调用，所以会被代理拦截。

#### 合并后的设计（❌ 错误）

```
调用流程：
[代理对象]
proxy.triggerAutoScan()
  ↓
[代理处理]
代理检查：@Async? NO ✗
  ↓
[直接转发]
super.triggerAutoScan()
  ↓
  [进入原始对象]
  原始对象的 triggerAutoScan() 方法
  {
      this.run()
      ↓
      this 指向原始对象（不是代理！）
      ↓
      直接查找原始对象的方法表
      ↓
      调用 AutoScanService.run()（原始方法）
  }
  
[问题！@Async 失效]
原始方法直接执行，@Async 被忽略
  ↓
[同步阻塞]
核心逻辑在当前线程执行，20 分钟阻塞
  ↓
[延迟返回]
等待 20 分钟后才返回 202 ACCEPTED
```

**关键点：** `this.run()` 是对**同一对象的原始方法**的调用，绕过了代理的 @Async 拦截器。

### 4.4 虚方法表（Virtual Method Table）

JVM 中的方法查询机制：

```
Java/Kotlin 对象内存结构：
┌─────────────────────────────────────────┐
│ 对象头（Object Header）                  │
├─────────────────────────────────────────┤
│ 类型指针（指向 Class 对象）              │
├─────────────────────────────────────────┤
│ 虚方法表（Virtual Method Table）        │
├─────────────────────────────────────────┤
│ triggerAutoScan → 方法指针               │
│ run → 方法指针                          │
│ ...其他方法                             │
└─────────────────────────────────────────┘

当执行 this.run() 时：
1. JVM 查看 this 对象的类型
2. 在虚方法表中查找 run 方法
3. 对于原始对象：AutoScanService.run() → 原始实现
4. 对于代理对象：AutoScanService$$EnhancerBySpringCGLIB_0.run() → 代理实现
   ↑
   但进入原始方法后，this 被绑定为原始对象
   所以虚方法表查询返回原始实现，不是代理实现
```

---

## 5. 执行流程对比

### 5.1 原始设计执行流程

```
时间线：t=0s
┌─────────────────────────────────────────────┐
│ [API 请求]                                   │
│ POST /api/v1/auto-scan                       │
│ startDate=2026-07-22, endDate=2026-08-05    │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│ [Controller]                                 │
│ autoScanService.triggerAutoScan(...)        │
│ autoScanService = proxy 对象                │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│ [代理拦截]                                   │
│ AutoScanService$$EnhancerBySpringCGLIB_0    │
│ .triggerAutoScan()                          │
│ ├─ 检查 @Async? YES ✓                      │
│ ├─ 创建 Runnable                            │
│ ├─ executor.execute(runnable)  [提交到线程池] │
│ └─ return void  ← 立即返回                  │
└─────────────────────────────────────────────┘
           ↓ (t ≈ 0.01s)                   ↓
    [立即返回]               [后台线程开始执行]
    ├─ 返回 202                  ├─ runnable.run()
    ├─ Controller 返回            ├─ super.triggerAutoScan()
    ├─ 客户端收到 202             ├─ autoScanRunner.run()
    └─ 通知用户异步处理中          ├─ [数据库查询]
                                  ├─ [处理 deals]
                                  ├─ [发布 Kafka]
                                  ├─ [20 分钟处理...]
                                  └─ 完成

结果：✅ 异步执行正确
- 客户端在 t≈0.01s 收到 202 ACCEPTED
- 后台继续处理 20+ 分钟
```

### 5.2 合并后的设计执行流程

```
时间线：t=0s
┌─────────────────────────────────────────────┐
│ [API 请求]                                   │
│ POST /api/v1/auto-scan                       │
│ startDate=2026-07-22, endDate=2026-08-05    │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│ [Controller]                                 │
│ autoScanService.triggerAutoScan(...)        │
│ autoScanService = proxy 对象                │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│ [代理处理]                                   │
│ AutoScanService$$EnhancerBySpringCGLIB_0    │
│ .triggerAutoScan()                          │
│ ├─ 检查 @Async? NO ✗ (triggerAutoScan 无) │
│ └─ super.triggerAutoScan()  [转发到原始对象] │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│ [原始对象执行]                               │
│ try {                                        │
│     this.run()  ← this 是原始对象            │
│     ├─ 不走代理的 run()                     │
│     ├─ [数据库查询]  t≈0.5s                 │
│     ├─ [处理 deals]  t≈2s                   │
│     ├─ [发布 Kafka]  t≈4s                   │
│     ├─ [20+ 分钟阻塞...]                    │
│     └─ [核心逻辑完成]  t≈1200s              │
│ } catch (exception) { }                      │
│ return void  ← 延迟 20 分钟返回              │
└─────────────────────────────────────────────┘
        ↓ (t ≈ 1200s = 20 分钟)
    [返回 202 ACCEPTED]
    ├─ 客户端等待了 20 分钟
    ├─ 超时风险很高（通常 HTTP timeout = 30s）
    └─ 容器可能 kill 该请求

结果：❌ 异步执行失效
- 当前线程被阻塞 20 分钟
- 客户端要么超时，要么等很久才收到 202
- 无法并发处理其他请求
```

---

## 6. 关键问题剖析

### 问题 1：为什么 this.run() 不走代理？

**答：** 因为 `this` 被绑定为原始对象实例。

```kotlin
// 当代理类执行 super.triggerAutoScan() 时
class AutoScanService$$EnhancerBySpringCGLIB_0 extends AutoScanService {
    override fun triggerAutoScan() {
        super.triggerAutoScan()  // ← 调用父类方法
        // 此时 this 的值被设置为...什么？
    }
}

// super.triggerAutoScan() 进入了父类的方法体
// 而此时 this 指向的是原始对象的实例
// 不是代理对象的实例

// 所以当原始方法中执行 this.run() 时
// JVM 会在原始对象的虚方法表中查找 run()
// 找到的是原始实现，不是代理实现
```

### 问题 2：为什么 autoScanRunner.run() 可以走代理？

**答：** 因为 `autoScanRunner` 是注入的另一个 Bean，它本身就是代理对象。

```kotlin
// AutoScanController 中
@RestController
class AutoScanController(
    private val autoScanService: AutoScanService  // 代理 1
) {
    fun api() {
        autoScanService.triggerAutoScan()
        // ↓
        // Spring 注入的是 AutoScanService 的代理对象
    }
}

// AutoScanService 中
@Service
class AutoScanService(
    private val autoScanRunner: AutoScanRunner  // 代理 2
) {
    fun triggerAutoScan() {
        autoScanRunner.run()
        // ↓
        // Spring 注入的是 AutoScanRunner 的代理对象
        // 所以这个调用会被代理拦截
    }
}
```

### 问题 3：能否用自注入的方式解决？

**理论上可以，但是反模式。**

```kotlin
@Service
class AutoScanService(
    private val self: AutoScanService  // 自注入代理
) {
    fun triggerAutoScan(startDate: LocalDate?, endDate: LocalDate?) {
        self.run(startDate, endDate)  // ✅ 通过代理调用
    }
    
    @Async
    private fun run(startDate: LocalDate?, endDate: LocalDate?) { }
}
```

**问题：**
1. 循环依赖风险（需要小心配置）
2. 代码难以理解和维护
3. 违反常规做法
4. 容易在重构时被误删

**结论：** 不推荐。还是应该保持两个 Service 的分离设计。

---

## 7. 总结

### 7.1 核心对比表

| 方面 | 原始设计（分离） | 合并设计 |
|------|-----------------|---------|
| **Service 数量** | 2 个 | 1 个 |
| **AutoScanService 的 @Async** | ✅ 有 | ✗ 无 |
| **AutoScanRunner 的 @Async** | ✗ 无 | ✅ 有（如果合并） |
| **调用关系** | runner.run()（不同对象） | this.run()（同对象） |
| **代理拦截** | 走代理（runner 是 Bean） | 不走代理（this 是原始对象） |
| **异步执行** | ✅ 立即返回，后台处理 | ❌ 同步阻塞，延迟返回 |
| **客户端体验** | ✅ 立即得到 202 | ❌ 等待 20 分钟才得到 202 |
| **超时风险** | ✗ 无 | ⚠️ 很高（HTTP timeout 通常 30s） |
| **可重用性** | ✅ runner 可被任何地方调用 | ✗ 无法重用 |
| **代码清晰度** | ✅ 职责分离，易维护 | ❌ 混杂，难以理解 |

### 7.2 三个层级的差异

```
┌─────────────────────────────────────────────────────────┐
│ [编译层] - 代码编写阶段                                  │
├─────────────────────────────────────────────────────────┤
│ 原始设计：trigger() 调用 runner.run()（不同类）         │
│ 合并设计：trigger() 调用 this.run()（同一类）           │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ [代理层] - Spring AOP 生成代理阶段                       │
├─────────────────────────────────────────────────────────┤
│ 原始设计：runner 是 Bean，所以 run() 被代理包装          │
│ 合并设计：this 是原始对象，所以 run() 不被代理包装       │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ [运行层] - 实际执行阶段                                  │
├─────────────────────────────────────────────────────────┤
│ 原始设计：runner.run() → 代理拦截 → 异步执行            │
│ 合并设计：this.run() → 无法拦截 → 同步执行（错误）      │
└─────────────────────────────────────────────────────────┘
```

### 7.3 最佳实践

1. **保持分离设计**
   - `AutoScanService`：异步协调 + 异常处理
   - `AutoScanRunner`：纯业务逻辑
   - 职责单一，易于理解和维护

2. **理解 AOP 限制**
   - AOP 只能拦截通过代理对象的方法调用
   - 同类内部 `this.method()` 调用无法被拦截
   - 这是 Spring AOP 的基本限制，不能绕过

3. **异步方法命名规范**
   - 入口方法（被 Controller 或 Scheduler 调用）：triggerXxx()、startXxx()
   - 核心方法（标记 @Async）：runXxx()、executeXxx()
   - 分离这两个方法，明确意图

4. **测试策略**
   - 异步协调层（Service）：可以直接调用 Runner，单元测试简单
   - 核心逻辑（Runner）：完全同步，易于测试和调试
   - 集成测试：验证异步行为正确

---

## 8. 相关资源

- [Spring AOP 官方文档](https://docs.spring.io/spring-framework/docs/current/reference/html/core.html#aop)
- [Spring @Async 官方文档](https://docs.spring.io/spring-framework/docs/current/reference/html/integration.html#scheduling-annotation-driven)
- [CGLIB 代理原理](https://github.com/cglib/cglib)

---

**作者：Copilot**  
**日期：2026-07-23**  
**版本：1.0**
