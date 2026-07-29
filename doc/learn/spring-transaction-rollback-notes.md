# Spring 事务回滚问题笔记：try/catch 抓住了异常，事务却仍然回滚

> **一句话结论**：`try/catch` 拦得住 **异常**，拦不住 **事务的 rollback-only 标记**。
> 只要在同一事务里发生一次持久化失败，事务就已被底层标记为“只能回滚”，
> 即使异常被 catch，方法返回后提交阶段仍会把整个事务回滚。
>
> - 场景：`NotificationService.createErrorNotification()`（`@Transactional(REQUIRES_NEW)`）内部打点，
>   打点链路里 `dealRepository.findByIdOrThrow(dealId)` 读取 `Deal` 反序列化失败。
> - 版本：Hibernate ORM 6.6.53.Final，spring-tx / spring-aop / spring-orm 7.0.8，spring-data-jpa 3.5.12。
> - 本次最终采用 **方案 E**（Spring `ApplicationEvent` + `@TransactionalEventListener(AFTER_COMMIT)`），见第 4 章。

## 目录

- [1. 问题现象](#1-问题现象)
- [2. 根因分析](#2-根因分析)
  - [2.1 完整因果链](#21-完整因果链)
  - [2.2 为什么 try/catch 无效](#22-为什么-trycatch-无效)
  - [2.3 Spring 事务的整体实现逻辑](#23-spring-事务的整体实现逻辑)
  - [2.4 rollbackOnly 的两种来源](#24-rollbackonly-的两种来源)
- [3. 源码深挖（Hibernate 6.6.53 / Spring 7.0.8）](#3-源码深挖hibernate-6653--spring-708)
  - [3.1 反序列化失败抛什么异常](#31-反序列化失败抛什么异常)
  - [3.2 实体加载与水合：perform / doLoad + jsonb 反序列化路径](#32-实体加载与水合perform--doload--jsonb-反序列化路径)
  - [3.3 Hibernate 如何把事务标记为 rollback-only](#33-hibernate-如何把事务标记为-rollback-only)
  - [3.4 Spring 如何读到这个标志](#34-spring-如何读到这个标志)
  - [3.5 @Transactional 代理何时、如何读 isRollbackOnly](#35-transactional-代理何时如何读-isrollbackonly)
  - [3.6 NotificationService 的 CGLIB 代理类详解](#36-notificationservice-的-cglib-代理类详解)
- [4. 解决方案](#4-解决方案)
  - [4.1 核心思路与方案对比](#41-核心思路与方案对比)
  - [4.2 本次实际采用：方案 E（AFTER_COMMIT 事件）](#42-本次实际采用方案-eafter_commit-事件)
  - [4.3 关于 try/catch 的澄清](#43-关于-trycatch-的澄清)
- [5. 关键结论速查](#5-关键结论速查)

---

## 1. 问题现象

`NotificationService.createErrorNotification()` 标注了 `@Transactional(propagation = REQUIRES_NEW)`，
方法内保存 `Notification` 后，调用 `putMetrics(...)` 打点：

```kotlin
// backend/.../usecases/notification/NotificationService.kt
@Transactional(propagation = Propagation.REQUIRES_NEW)
override fun createErrorNotification(dealId: String, templateName: TemplateName, ceSources: List<CeSource>) {
    val notificationId = otrManagerClient.sendNotification(dealId, request)
    if (shouldPersistNotification(templateName)) {
        notificationsRepository.save(Notification(id = notificationId, dealId = dealId, templateName = templateName))
        notificationMetricsPublisher.putMetrics(dealId, templateName, ceSources) // ← 问题调用
        logger.info { "[SAVE NOTIFICATION] ..." }
    }
}
```

`putMetrics(...)` 最终走到打点服务，里面读了一次 `Deal`：

```kotlin
// backend/.../otr/config/metrics/fdoaretry/FdoaRetryMetricsService.kt
fun incrementEvent(dealId: String, event: FdoaRetryEvent, sources: List<CeSource> = ...) {
    runSafely(operation = "incrementEvent", dealId = dealId, additionalContext = ...) {
        val dealStatus = dealRepository.findByIdOrThrow(dealId).dealStatus // ← 问题点：读取时反序列化失败
        increment(/* tags... */)
    }
}
```

`runSafely` 只是把异常 `try/catch` 吞掉：

```kotlin
// backend/.../otr/config/metrics/AbstractBusinessMetricsService.kt
protected fun runSafely(operation: String, dealId: String, additionalContext: String, block: () -> Unit) {
    try {
        block()
    } catch (exception: Exception) {
        logger.warn(exception) { "Failed to publish $metricName metric ..." }
    }
}
```

**Debug 验证**：在 `putMetrics(...)` 前后分别 evaluate

```
org.springframework.transaction.interceptor.TransactionAspectSupport
    .currentTransactionStatus().isRollbackOnly()
```

结果由 `false` 变成 `true`——异常虽被 catch（Java 层不再抛出），但当前事务已被标记为
**rollback-only**，最终导致 `createErrorNotification` 这个内层新事务在提交时被强制回滚，
连同 `Notification` 的写入一起丢失（甚至可能抛 `UnexpectedRollbackException`）。

---

## 2. 根因分析

**根因**：`putMetrics()` 属于“可选、可失败的副作用”，却运行在**保存通知的业务事务内部**。
一旦它内部的持久化调用失败，Hibernate 就把这个事务标记为 rollback-only，
即使异常被 `try/catch` 也无法挽回。

### 2.1 完整因果链

```
createErrorNotification()  [@Transactional REQUIRES_NEW]  →  开启新事务, rollbackOnly = false
  │
  ├─ otrManagerClient.sendNotification(...)
  ├─ notificationsRepository.save(...)                     →  想要持久化 Notification
  │
  └─ notificationMetricsPublisher.putMetrics(...)
        └─ FdoaRetryMetricsService.incrementEvent(...)
              └─ runSafely { ... }                          [try/catch]
                    └─ dealRepository.findByIdOrThrow(...)   ← 读取 Deal
                          └─ Hibernate find → 水合 jsonb/enum 列
                               └─ Jackson 反序列化失败 → IllegalArgumentException
                                    └─ SessionImpl.find 的 catch → ExceptionConverterImpl.convert
                                         └─ rollbackIfNecessary → markForRollbackOnly
                                              └─ TransactionImpl → Coordinator.rollbackOnly = TRUE   ★ 标记落定
        ↑ 异常被 runSafely 的 catch 吞掉（Java 层无感，但标记还在）
  │
  └─ rollbackOnly 现在 = true  ← Debug 中观察到的 false → true
  │
提交阶段 commitTransactionAfterReturning()
  └─ AbstractPlatformTransactionManager.commit → isGlobalRollbackOnly()==true
       └─ processRollback → 整个事务回滚（Notification 的 save 一并回滚）
            └─（可能）抛 UnexpectedRollbackException
```

### 2.2 为什么 try/catch 无效

“catch 异常”和“取消事务回滚标记”是两件完全不同的事：

- `try/catch`（`runSafely`）只阻止异常**继续向上抛**，让程序流程不崩、能往下走。
- 但在异常被 catch **之前**，Hibernate 处理 `find` 失败时**已经**把事务在底层标记成
  `rollbackOnly = true`。这个标记记在**事务对象**上，**不会**因为上层把异常 catch 掉而清除。
- 方法正常返回后，Spring 事务切面提交事务时发现 `rollbackOnly == true`，于是**改为回滚**。

> 一句话：try/catch 能救回“程序流程”，救不回“事务”。

### 2.3 Spring 事务的整体实现逻辑

`@Transactional` 的本质是一个**环绕通知（Around Advice）**。Spring 启动时为被标注的 Bean 生成代理，
方法调用先进入事务拦截器：

```
调用方
  └─> TransactionInterceptor.invoke()
        └─> TransactionAspectSupport.invokeWithinTransaction()
              ├─ createTransactionIfNecessary()      // 开启/加入事务
              ├─ invocation.proceedWithInvocation()  // 执行真正的业务方法
              ├─ (异常) completeTransactionAfterThrowing()
              └─ (正常) commitTransactionAfterReturning()
```

| 类 | 职责 |
|----|------|
| `TransactionInterceptor` | AOP 拦截入口 |
| `TransactionAspectSupport` | 事务模板逻辑（开启 / 提交 / 回滚决策） |
| `AbstractPlatformTransactionManager` | 事务管理器抽象，真正执行 begin/commit/rollback |
| `JpaTransactionManager` | JPA 实现，绑定 `EntityManager` 到当前线程 |
| `DefaultTransactionStatus` | 保存当前事务状态，含 `rollbackOnly` 标志 |
| `TransactionSynchronizationManager` | 线程绑定的事务资源与状态管理（ThreadLocal） |

核心模板方法：

```java
// org.springframework.transaction.interceptor.TransactionAspectSupport
protected Object invokeWithinTransaction(Method method, Class<?> targetClass, InvocationCallback invocation) {
    TransactionInfo txInfo = createTransactionIfNecessary(tm, txAttr, joinpointIdentification);
    Object retVal;
    try {
        retVal = invocation.proceedWithInvocation();          // 执行业务方法
    }
    catch (Throwable ex) {
        completeTransactionAfterThrowing(txInfo, ex);         // 出异常时按规则回滚/提交
        throw ex;
    }
    finally {
        cleanupTransactionInfo(txInfo);
    }
    commitTransactionAfterReturning(txInfo);                  // 正常返回：提交（若已标记回滚则回滚）
    return retVal;
}
```

**`REQUIRES_NEW`**：若当前已有事务，Spring 会**挂起外层事务**、开启一个**全新物理事务**；
内层独立提交/回滚。因此本次被回滚的是 `createErrorNotification` 这个内层新事务。

### 2.4 rollbackOnly 的两种来源

`isRollbackOnly()` 的返回值 = **Spring 本地标记 OR 底层资源(JPA/Hibernate)标记**：

```java
// AbstractTransactionStatus
public boolean isRollbackOnly() {
    return (isLocalRollbackOnly() || isGlobalRollbackOnly());
}
```

- **来源一（本次命中）**：JPA / Hibernate 底层将 `EntityTransaction` 置为 rollback-only。
  即便 `runSafely` 在 Java 层 catch 了异常，事务系统层的标志已经置上，无法撤销。
- **来源二**：异常真的冒泡到某个 `@Transactional` 边界，Spring 依据 `rollbackOn(ex)` 主动
  `setRollbackOnly()` 或物理回滚。

本次是**来源一**：`isGlobalRollbackOnly()` 读到了 JPA 底层状态。详见第 3 章。

---

## 3. 源码深挖（Hibernate 6.6.53 / Spring 7.0.8）

> 以下类名、方法、行为均来自本项目 gradle 缓存中的
> `hibernate-core-6.6.53.Final-sources.jar`、`spring-orm/tx/aop-7.0.8-sources.jar`。

### 3.1 反序列化失败抛什么异常

`Deal` 实体里有两类“读取时需要反序列化”的列：

```kotlin
// backend/.../core/models/Deal.kt
@Enumerated(EnumType.STRING) var dealStatus: DealStatus                 // 枚举列
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "is_auto_scan_triggered", columnDefinition = "jsonb")
var isAutoScanTriggered: AutoScanTriggered = AutoScanTriggered()        // JSON 列
```

**关键结论：反序列化失败抛出的原始异常是 `IllegalArgumentException`，不是 `HibernateException`。**

- **JSON 列**：Hibernate 用 Jackson，失败时抛：

```java
// org/hibernate/type/format/jackson/JacksonJsonFormatMapper.java
throw new IllegalArgumentException( "Could not deserialize string to java type: " + type, e );
```

- **枚举列**（DB 里存了枚举中不存在的字符串）：`Enum.valueOf` 失败，同样抛 `IllegalArgumentException`。

因为它不是 `HibernateException`，`ExceptionConverterImpl.convert(RuntimeException)` 会走 else 分支
**原样返回**（不包装成 JPA `PersistenceException`）；但在抛出前已触发 `markForRollbackOnly()`。
这也解释了为什么 `runSafely` 的 `catch (Exception)` 能抓住它（它是 `RuntimeException`），但事务已坏。

### 3.2 实体加载与水合：perform / doLoad + jsonb 反序列化路径

**水合（hydration）** = 把数据库一行结果（`ResultSet`）里的各列值，逐列取出、类型转换/反序列化，
再“注水”填充成一个 Java 实体对象的过程。`Deal` 的 `is_auto_scan_triggered` 就是在水合阶段被
Jackson 反序列化成 `AutoScanTriggered` 的——失败正发生在这一步。

`IdentifierLoadAccessImpl` 是 `EntityManager.find` 底层 `byId(...).load(id)` 的真正执行者：

```java
// org/hibernate/loader/internal/IdentifierLoadAccessImpl.java
public final T load(Object id) {
    return perform( () -> doLoad( id ) );   // perform 包一层，doLoad 干活
}

protected T perform(Supplier<T> executor) {
    // perform 只负责“环境准备与还原”，不碰 ResultSet：
    //  1. 临时切换 CacheMode  2. 应用 fetch profiles / entity graph  3. 执行 executor.get()  4. finally 还原
    try {
        ...applyEntityGraph / fetchProfiles...
        try { return executor.get(); }      // ← 调 doLoad
        finally { ...clear/restore... }
    } finally { ...restore cacheMode... }
}

protected final T doLoad(Object id) {
    final SessionImplementor session = context.getSession();
    final Object result = load(                       // 触发 LoadEvent
        coerceId( id, session.getFactory() ),         // 先把 id 强转成主键类型
        session.asEventSource(), entityPersister.getEntityName(), isReadOnly( session )
    );
    initializeIfNecessary( result );                  // 若是代理则强制初始化
    return (T) result;
}
```

- **perform**：加载的“外壳”，只做 CacheMode / EntityGraph / FetchProfile 的临时设置与还原。
- **doLoad**：加载的“核心”，构造 `LoadEvent` 并 `fireLoad(...)` → `DefaultLoadEventListener`
  → 查一/二级缓存，未命中则发 SQL、拿到 `ResultSet` → 进入**水合**。

水合到 jsonb 反序列化的精确路径：

```
doLoad → fireLoad → DefaultLoadEventListener → 执行 SELECT，得到 ResultSet
  → 按列水合：BasicResultAssembler.assemble()
       └─ BasicExtractor.extract(rs, paramIndex)          [每列一次]
            └─ doExtract(rs, paramIndex)                   ← jsonb 列走 JsonJdbcType
```

```java
// org/hibernate/type/descriptor/jdbc/JsonJdbcType.java
protected X doExtract(ResultSet rs, int paramIndex, WrapperOptions options) throws SQLException {
    return fromString( rs.getString( paramIndex ), getJavaType(), options );  // 读出 JSON 文本
}
// JsonJdbcType.fromString(...) → getJsonFormatMapper()（= JacksonJsonFormatMapper）.fromString(...)
//   → objectMapper.readValue(...) 失败 → throw IllegalArgumentException("Could not deserialize ...")
```

**一句话**：`load → perform（准备抓取环境）→ doLoad（发 LoadEvent、执行 SQL）→ 逐列水合 →
jsonb 列 JsonJdbcType.doExtract → JacksonJsonFormatMapper.fromString → Jackson readValue`，
DB 里的 JSON 与实体结构不匹配时在此抛 `IllegalArgumentException`，一路冒泡到
`SessionImpl.find` 的 `catch` → `convert` → 标记事务回滚。

### 3.3 Hibernate 如何把事务标记为 rollback-only

**(a) convert 的触发点**——是 Hibernate 自己的 `SessionImpl.find()` 兜住异常，不是 Spring Data：

```
SimpleJpaRepository.findById(id)          [spring-data-jpa，仅透明转调]
  └─ EntityManager.find(...)              [JPA API]
       └─ SessionImpl.find(...)           [hibernate]
```

```java
// org/hibernate/internal/SessionImpl.java find(...) 尾部
catch ( RuntimeException e ) {                            // IllegalArgumentException 命中这里
    throw getExceptionConverter().convert( e, lockOptions );  // ★ 触发 convert
}
```

**(b) convert → rollbackIfNecessary → markForRollbackOnly**：除
`NoResult / NonUniqueResult / LockTimeout / QueryTimeout` 四类“查询语义异常”外，一律标记回滚：

```java
// org/hibernate/internal/ExceptionConverterImpl.java
private void rollbackIfNecessary(PersistenceException pe) {
    if ( !(pe instanceof NoResultException || pe instanceof NonUniqueResultException
            || pe instanceof LockTimeoutException || pe instanceof QueryTimeoutException) ) {
        markForRollbackOnly();   // ★ 打标记
    }
}
```

**(c) markRollbackOnly 下沉，带 `isActive()` 守卫**：

```java
// org/hibernate/engine/transaction/internal/TransactionImpl.java
public void markRollbackOnly() {
    if ( isActive() ) {                                     // 只有活跃事务才动作，否则 no-op
        internalGetTransactionDriverControl().markRollbackOnly();
    }
}
```

**(d) 标志真正落在协调器的布尔字段上**：

```java
// org/hibernate/resource/transaction/backend/jdbc/internal/JdbcResourceLocalTransactionCoordinatorImpl.java
public void markRollbackOnly() {
    if ( getStatus() != TransactionStatus.ROLLED_BACK ) {
        rollbackOnly = true;                               // ★ 标志位就在这里
    }
}
public TransactionStatus getStatus() {
    return rollbackOnly ? TransactionStatus.MARKED_ROLLBACK : jdbcResourceTransaction.getStatus();
}
```

**几个概念澄清：**

- **Session**：`TransactionImpl` 持有的 `session` 就是 Hibernate 的 `SessionImpl`
  （= JPA `EntityManager` = 持久化上下文 = 一级缓存 + 待 flush 状态 + 绑定的 JDBC 连接/事务）。
  Spring 通过 `EntityManagerHolder` 把它绑定到当前线程。“事务活跃”里 `session.isOpen()`
  就是问“承载事务的持久化上下文还没关闭吗”。
- **“事务活跃（isActive）”** = Session 打开且底层存在一个已 begin、未 commit/rollback 的物理事务，
  状态为 `ACTIVE` 或 `MARKED_ROLLBACK`。不活跃时 `markRollbackOnly()` 是安全的 no-op；
  但 `getRollbackOnly()` 在 JPA compliance 下对非活跃事务会抛 `IllegalStateException`。
- **transactionDriverControl** = `TransactionCoordinator.TransactionDriver` 接口，实现为
  `JdbcResourceLocalTransactionCoordinatorImpl` 的内部驱动类——**真正持有 `rollbackOnly`、驱动 JDBC 事务**的对象。
  `TransactionImpl` 只是面向 JPA 的薄门面，委托给它。

### 3.4 Spring 如何读到这个标志

分三段，每段都是真实源码：

**第 1 段（spring-tx）**：

```java
// AbstractTransactionStatus / DefaultTransactionStatus
public boolean isRollbackOnly()       { return (isLocalRollbackOnly() || isGlobalRollbackOnly()); }
public boolean isLocalRollbackOnly()  { return this.rollbackOnly; }  // 手动 setRollbackOnly，本次为 false
public boolean isGlobalRollbackOnly() {
    return (this.transaction instanceof SmartTransactionObject sto && sto.isRollbackOnly()); // 问底层资源
}
```

**第 2 段（spring-orm）**：底层资源对象 `JpaTransactionObject` 实现 `SmartTransactionObject`，
直接问 JPA 的 `EntityTransaction`：

```java
// org/springframework/orm/jpa/JpaTransactionManager.java（JpaTransactionObject 内部类）
public boolean isRollbackOnly() {
    return getEntityManagerHolder().getEntityManager().getTransaction().getRollbackOnly();
}
```

**第 3 段（hibernate）**：JPA `EntityTransaction` 实现就是 `TransactionImpl`：

```java
public boolean getRollbackOnly() { return getStatus() == TransactionStatus.MARKED_ROLLBACK; }
// getStatus() → 协调器的 rollbackOnly 布尔（3.3-d）
```

完整下钻（debug 里那行 evaluate 的真实路径）：

```
TransactionAspectSupport.currentTransactionStatus().isRollbackOnly()
  └─ AbstractTransactionStatus.isRollbackOnly()
       ├─ isLocalRollbackOnly()  → false
       └─ isGlobalRollbackOnly()
            └─ JpaTransactionObject.isRollbackOnly()          [spring-orm]
                 └─ EntityTransaction.getRollbackOnly()        [JPA API]
                      └─ TransactionImpl.getStatus() == MARKED_ROLLBACK   [hibernate]
                           └─ JdbcResourceLocalTransactionCoordinatorImpl.rollbackOnly == true  ★
```

### 3.5 @Transactional 代理何时、如何读 isRollbackOnly

`isRollbackOnly()` **不是在业务方法执行期间被主动轮询**，而是在**方法返回、提交事务时**被读取：

```
[代理] NotificationService$$SpringCGLIB.createErrorNotification(...)
  └─ TransactionInterceptor → TransactionAspectSupport.invokeWithinTransaction()
       ├─ 业务方法正常返回（异常已被 runSafely 吞掉）
       └─ commitTransactionAfterReturning(txInfo)
            └─ AbstractPlatformTransactionManager.commit(status)
                 ├─ defStatus.isLocalRollbackOnly()   → false
                 └─ defStatus.isGlobalRollbackOnly()  → true  ← ★ 在这里读标志
                      └─ processRollback(defStatus, true);   // 改为回滚！
```

```java
// AbstractPlatformTransactionManager.commit(...)
DefaultTransactionStatus defStatus = (DefaultTransactionStatus) status;
if (defStatus.isLocalRollbackOnly()) { processRollback(defStatus, false); return; }
if (!shouldCommitOnGlobalRollbackOnly() && defStatus.isGlobalRollbackOnly()) {
    processRollback(defStatus, true);   // 业务想 commit，却被强制回滚
    return;
}
processCommit(defStatus);
```

第二个参数 `true` 表示“期望提交却发现全局回滚”，`processRollback` 里可能抛
`UnexpectedRollbackException("Transaction rolled back because it has been marked as rollback-only")`。
Debug 里手动 evaluate `currentTransactionStatus().isRollbackOnly()` 走的是同一套读取逻辑。

### 3.6 NotificationService 的 CGLIB 代理类详解

**(1) 为什么是 CGLIB 子类**：`NotificationService` 虽实现了 `NotificationInterface`，但 Spring Boot
默认 `proxy-target-class=true`，一律走 CGLIB——用 `Enhancer` **生成一个继承真实类的子类**
（类名形如 `NotificationService$$SpringCGLIB$$<hash>`）：

```java
// org/springframework/aop/framework/CglibAopProxy.buildProxy(...)
enhancer.setSuperclass(proxySuperClass);                                   // 父类 = NotificationService
enhancer.setInterfaces(AopProxyUtils.completeProxiedInterfaces(advised));  // 附带 NotificationInterface 等
enhancer.setCallbackFilter(new ProxyCallbackFilter(...));                  // 方法 → callback 路由
Callback[] callbacks = getCallbacks(rootClass);
return createProxyClassAndInstance(enhancer, callbacks);
```

**(2) 代理不是 super 调用，而是委托一个独立的 target 实例**：

- 容器先创建**真实 bean 实例**（真正注入了 `otrManagerClient` 等依赖的那个）。
- 再用 **Objenesis** 实例化代理子类（**不走构造器**，代理自身字段全 null），装上回调。
- 真实 bean 被包进 `TargetSource`，代理通过它拿到 target，用**反射**调真实方法：

```java
// CglibAopProxy.DynamicAdvisedInterceptor.intercept(...)
target = targetSource.getTarget();     // ← 独立的真实 NotificationService 实例
List<Object> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass);
if (chain.isEmpty()) {
    retVal = AopUtils.invokeJoinpointUsingReflection(target, method, argsToUse);   // 无通知 → 反射直调
} else {
    retVal = new ReflectiveMethodInvocation(proxy, target, method, args, targetClass, chain).proceed(); // 有 @Transactional → 走链
}
```

> 结论：代理子类字段是空的，业务状态全在 target 上；代理只负责“拦截 + 转发”，
> 所以它**不能**用 `super`——要打到那个装好依赖的独立实例。

**(3) 多 Callback + CallbackFilter**：CGLIB 允许一个代理挂多个回调，`ProxyCallbackFilter`
为每个方法决定索引。带 `@Transactional` 的 `createErrorNotification / createWarnNotification /
deleteNotifications` → callback 0（`DynamicAdvisedInterceptor`）→ 链里含 `TransactionInterceptor`；
无 `@Transactional` 的 `createInfoNotification` → 链为空，等价直调 target（无事务）。

**(4) 自调用陷阱**：因为业务逻辑跑在 **target 实例**上、事务逻辑只存在于**代理子类**的拦截器里，
一旦在 `NotificationService` 内部写 `this.createWarnNotification(...)`，`this` 是 target 自己，
**根本不经过代理**，`@Transactional(REQUIRES_NEW)` 失效（不会开新事务）。
要生效必须让调用穿过代理：注入自身代理 bean、或把该方法拆到另一个 Spring bean。

---

## 4. 解决方案

### 4.1 核心思路与方案对比

**核心思路：不要让 metrics 这种“可失败、不重要”的副作用，与主业务共用同一个数据库事务。**

| 方案 | 做法 | 评价 |
|------|------|------|
| **A** 事务隔离（声明式） | 给 metrics 方法加 `@Transactional(NOT_SUPPORTED)` 挂起当前事务，或 `REQUIRES_NEW` 独立事务 | 治本；需确保调用穿过代理 |
| **B** 不为打点查库 | 由调用方把 `dealStatus` 传进来，或缺失时打 `UNKNOWN` tag，去掉这次 DB 读取 | 最彻底；改动调用方 |
| **C** 修脏数据 / 宽容反序列化 | 迁移清洗数据，或自定义 converter 遇未知值降级而非抛异常 | 治“为何失败”，与 A/B 互补 |
| **D** 编程式独立事务 | 用 `TransactionTemplate(REQUIRES_NEW)` 或独立只读 `EntityManager` 包住读取 | A 的编程式变体，边界更可控 |
| **E** 提交后事件（本次采用） | 主业务发 Spring `ApplicationEvent`，`@TransactionalEventListener(AFTER_COMMIT)` 在事务提交后再打点 | 解耦彻底、时序正确；语义变为“成功才打点” |

> 补充：只读事务（`readOnly = true`）**不能**阻止 rollback-only 标记，单独用无效。
> 若要跨服务/异步持久化才考虑 Kafka；对“本进程内打点”而言 Kafka 属过度设计。

### 4.2 方案 E（AFTER_COMMIT 事件）的实现

**为什么选 E**：`putMetrics` 已标注 `@TransactionalEventListener`，但之前是被**直接方法调用**，
注解完全失效（仍同步跑在当前事务里）。`@TransactionalEventListener` 只有当方法**作为事件监听器
被 Spring 触发**时才生效，触发方式唯一：`ApplicationEventPublisher.publishEvent(...)`。

**这里的 event 是 Spring `ApplicationEvent`**（进程内、内存、同步、事务感知），一个 `data class` 即可，
不需要序列化，也不出网。`AFTER_COMMIT` 表示“等当前事务成功提交后再执行监听器”。

**改动一：新增事件类**

```kotlin
// backend/.../otr/config/metrics/NotificationMetricEvent.kt
data class NotificationMetricEvent(
    val dealId: String,
    val templateName: TemplateName,
    val sources: List<CeSource>,
)
```

**改动二：`NotificationMetricsPublisher` 改为消费事件**

```kotlin
// backend/.../otr/config/metrics/NotificationMetricsPublisher.kt
@Component
class NotificationMetricsPublisher(
    private val metricsServices: List<NotificationEventMetricsInterface>,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun putMetrics(event: NotificationMetricEvent) {
        metricsServices.forEach { it.tryIncrementEvent(event.dealId, event.templateName, event.sources) }
    }
}
```

**改动三：`NotificationService` 改为发事件**（注入 `ApplicationEventPublisher`，两处直调改为 `publishEvent`）

```kotlin
class NotificationService(
    private val otrManagerClient: OtrManagerClient,
    private val notificationsRepository: NotificationsRepository,
    private val launchDarklyClient: LaunchDarklyClient,
    private val eventPublisher: ApplicationEventPublisher,          // ← 替换原 NotificationMetricsPublisher
) : NotificationInterface {
    // ...
    // 发布应用内事件；AFTER_COMMIT 监听器在本事务提交后才打点，使 DB 读取脱离当前事务
    eventPublisher.publishEvent(NotificationMetricEvent(dealId, templateName, ceSources))
}
```

**改动四：测试**（`NotificationServiceTest` 的 mock/verify 由
`notificationMetricsPublisher.putMetrics(...)` 改为 `eventPublisher.publishEvent(NotificationMetricEvent(...))`）。

**效果与语义变化：**

- ✅ 打点在**主事务提交之后**、无活跃事务上下文中执行，`findByIdOrThrow` 再失败也
  污染不到已提交的业务事务——从根上消除回滚。
- ⚠️ `AFTER_COMMIT` 阶段默认**无活跃事务**，监听器里的 DB 读取会用新的独立连接；
  若监听器需要写库，要额外给它加 `@Transactional(REQUIRES_NEW)`（纯只读打点无需）。
- ⚠️ 语义变化：事务**回滚时** `AFTER_COMMIT` 监听器**不触发**——即“业务成功才打点”，通常正是期望。


### 4.3 关于 try/catch 的澄清

`runSafely` 的 `try/catch` **保留是对的**（能防止 metrics 失败让业务方法崩溃），
但要清楚它**只保护程序流程，不保护事务**。真正解决回滚必须靠**事务边界隔离**（A/D/E），
而不是指望 catch。

---

## 5. 关键结论速查

1. `try/catch` 拦得住异常，拦不住事务的 **rollback-only 标记**。
2. 反序列化失败的原始异常是 **`IllegalArgumentException`**（“Could not deserialize string to java type” /
   枚举 `valueOf` 失败），不会被包装成 `PersistenceException`，但抛出前已 `markForRollbackOnly()`。
3. **水合（hydration）** 是把 `ResultSet` 逐列转换/反序列化填充成实体的过程；jsonb 列在
   `JsonJdbcType.doExtract → JacksonJsonFormatMapper.fromString → Jackson readValue` 处反序列化。
4. 标志下沉链：`ExceptionConverterImpl.convert → rollbackIfNecessary → markForRollbackOnly →
   TransactionImpl.markRollbackOnly（isActive 守卫）→ JdbcResourceLocalTransactionCoordinatorImpl.rollbackOnly = true`。
5. Spring 读标志链：`isRollbackOnly → isGlobalRollbackOnly → JpaTransactionObject（SmartTransactionObject）
   → EntityTransaction.getRollbackOnly → TransactionImpl 状态 → 协调器布尔`。
6. 代理**不在方法体内**读 isRollbackOnly，而在方法返回后 `commit` 决策点读；`true` 则 `processRollback`
   （可能抛 `UnexpectedRollbackException`）。
7. `NotificationService` 是 **CGLIB 子类代理**：Objenesis 无构造器实例化、经 `TargetSource` 反射委托给
   独立真实 bean（非 super）；存在**自调用失效**陷阱。
