# 🏛️ 后端架构"黄金七剑"：核心设计模式实战笔记

> **学习心法：** 23 种设计模式不需要全背，掌握这 7 个高频核心模式，足以解决日常开发中 90% 的"代码臃肿、高耦合、难以扩展"问题。**无痛点，不模式，切忌过度设计。**

---

## 一、创建型：优雅地造对象

### 1. 单例模式 (Singleton)

* **本质：** 保证全局有且仅有一个实例。
* **解决痛点：** 避免频繁创建重量级对象（如连接池、全局配置），防止 OOM 和资源竞争。
* **框架应用：** Spring 容器默认 Bean 作用域 (`@Scope("singleton")`)。
* **Kotlin 落地：** 直接使用 `object` 关键字（天然线程安全）。

```kotlin
object AppConfig {
    val maxConnections = 100
}
// 使用: AppConfig.maxConnections
```

### 2. 工厂模式 (Factory)

* **本质：** 把对象的创建过程封装起来，调用方"只管用，不管怎么造"。
* **解决痛点：** 解耦对象的使用逻辑与繁琐的 new 初始化过程。
* **框架应用：** Spring 的 `BeanFactory`、`LoggerFactory.getLogger()`。

```kotlin
object PaymentFactory {
    fun getGateway(type: String): PaymentGateway = when (type) {
        "ALIPAY" -> AliPay()
        "WECHAT" -> WeChatPay()
        else -> throw IllegalArgumentException("Unknown type")
    }
}
```

---

## 二、结构型：优雅地拼装模块

### 3. 代理模式 (Proxy)

* **本质：** 找个"中介"，在不修改原业务代码的前提下，在方法执行前后偷偷加料。
* **解决痛点：** 剥离非核心业务逻辑（如打日志、开启数据库事务、限流），避免业务代码被严重污染。
* **框架应用：** Spring AOP 切面、`@Transactional` 事务注解底层的动态代理。

```kotlin
// 伪代码演示代理本质
class UserServiceProxy(private val target: UserService) : UserService {
    override fun createUser() {
        println("【前置增强】开启事务")
        target.createUser() // 真正执行原业务
        println("【后置增强】提交事务")
    }
}
```

---

## 三、行为型：优雅地调度交互

### 4. 策略模式 (Strategy)

* **本质：** 把不同的算法封装为独立的类，利用接口多态进行动态替换。
* **解决痛点：** 彻底消除代码中极度膨胀的 if-else / switch-case。
* **框架应用：** 集合排序 `Collections.sort(list, comparator)`（不同的 Comparator 就是不同的策略）。
* **实战套路：** 强烈建议配合 Spring 的 `List<Strategy>` 自动注入，结合工厂模式动态路由。

```kotlin
@Service
class PriceCalculator(private val strategies: List<DiscountStrategy>) {
    fun calculate(userType: String, price: Double): Double {
        // 根据策略自带的标识(supports)自动匹配，零 if-else
        val strategy = strategies.find { it.supports(userType) } 
        return strategy?.applyDiscount(price) ?: price
    }
}
```

### 5. 模板方法模式 (Template Method)

* **本质：** 在父类中定义好业务流程的固定骨架，把变化部分的具体实现留给子类。
* **解决痛点：** 流程是定死的，但每个步骤细节不一样，提取公共骨架避免重复造轮子。
* **框架应用：** Spring `JdbcTemplate`、`HttpServlet`（service 方法定死骨架，doGet/doPost 交给子类重写）。

```kotlin
abstract class AbstractOrderFlow {
    fun process() { // 骨架 (模板方法)
        validate()  // 1. 公用校验
        doPay()     // 2. 差异化扣款 (抽象方法，子类实现)
    }
    private fun validate() = println("校验数据")
    protected abstract fun doPay()
}
```

### 6. 责任链模式 (Chain of Responsibility)

* **本质：** 把多个拦截节点连成一条链，请求顺着链条逐级传递校验。
* **解决痛点：** 支持动态拼装过滤规则，避免在一个方法里写十几个前置校验逻辑。
* **框架应用：** Spring Security 安全拦截器、网关限流/鉴权 Gateway Filter、Servlet FilterChain。

```kotlin
class FilterChain {
    private val filters = mutableListOf<Filter>()
    fun add(f: Filter) = apply { filters.add(f) }
    
    fun doFilter(req: Request) {
        // 依次执行每个过滤节点，节点内部可决定是否中断当前链
        filters.forEach { it.filter(req) } 
    }
}
```

### 7. 观察者模式 (Observer / Pub-Sub)

* **本质：** 发布-订阅机制。核心状态一变，自动通知所有旁路订阅者。
* **解决痛点：** 解耦主干流程与旁路分支。比如"下单成功（主）"后需要"发短信、加积分、减库存（旁路）"，不该把这些代码硬塞在下单方法里。
* **框架应用：** Spring 事件机制 (`@EventListener` / `ApplicationEventPublisher`)、Kafka / RabbitMQ 消息队列。

```kotlin
@Service
class OrderService(private val publisher: ApplicationEventPublisher) {
    fun createOrder() {
        println("订单落盘成功！")
        // 主流程结束，一脚踢开事件，后续解耦
        publisher.publishEvent(OrderCreatedEvent(orderId = 101)) 
    }
}

@Component
class SmsListener {
    @EventListener // 独立观察者，监听到事件自动执行
    fun onOrderCreated(event: OrderCreatedEvent) = println("发短信...")
}
```

---

## 🎯 架构组合技 (高级面试加分项)

在实际生产级重构中，往往不是单一模式在使用，而是组合拳：

* **策略模式 + 工厂模式：** 最强除 if-else 利器。工厂负责根据 type 提取对应的策略，策略负责执行具体逻辑。
* **责任链模式 + 策略模式：** 比如风控引擎。先用责任链串起一系列黑名单/白名单拦截器，拦截器内部再用策略模式计算具体的风险得分。
* **观察者模式 + 模板方法：** 父类骨架定义好核心步骤，在核心步骤结束后发布事件，由观察者异步收尾。
