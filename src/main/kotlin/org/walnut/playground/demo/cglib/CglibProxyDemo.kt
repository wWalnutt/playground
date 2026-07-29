package org.walnut.playground.demo.cglib

import org.springframework.cglib.proxy.Enhancer
import org.springframework.cglib.proxy.MethodInterceptor
import org.springframework.cglib.proxy.MethodProxy
import java.lang.reflect.Method

/**
 * 纯 CGLIB 演示（不依赖 Spring AOP），验证"代理类 = 目标类的子类"这一原理。
 *
 * 这里用的是 Spring 重打包进 spring-core 的 CGLIB（org.springframework.cglib.*），
 * 无需额外依赖，其 API 与原生 net.sf.cglib 完全一致。
 *
 * 观察点：
 *  1. Enhancer 在运行时【生成目标类的子类】作为代理。
 *  2. 代理通过【重写方法 + MethodInterceptor】织入前后置增强逻辑。
 *  3. 真正的原始逻辑通过 methodProxy.invokeSuper() 也就是 super.xxx() 调用。
 *  4. 印证 CGLIB 的限制：final 方法无法被重写 → 不会被拦截。
 */

// ---------------------------------------------------------------------------
// 目标类：一个普通类，没有实现任何接口（这正是 JDK 动态代理搞不定、必须用 CGLIB 的场景）
// 注意：Kotlin 类默认 final，必须显式 open 才能被 CGLIB 继承/重写
// ---------------------------------------------------------------------------
open class OrderService {

    open fun createOrder(orderId: String): String {
        println("    [原始逻辑] 正在创建订单 $orderId ...")
        return "ORDER-$orderId"
    }

    // final 方法：CGLIB 无法重写，因此拦截器拦不到它
    fun healthCheck(): String {
        println("    [原始逻辑] healthCheck（final 方法，拦截器无法介入）")
        return "OK"
    }
}

// ---------------------------------------------------------------------------
// 拦截器：所有被代理方法的调用都会先进到这里（相当于 AOP 的环绕通知）
// ---------------------------------------------------------------------------
class LoggingInterceptor : MethodInterceptor {

    override fun intercept(
        obj: Any,
        method: Method,
        args: Array<out Any?>,
        methodProxy: MethodProxy,
    ): Any? {
        println(">> [代理前置] 即将执行: ${method.name}, 参数=${args.toList()}")
        val start = System.nanoTime()

        // 关键：invokeSuper 相当于调用父类（原始类）的实现，即 super.method()
        val result = methodProxy.invokeSuper(obj, args)

        val costMicros = (System.nanoTime() - start) / 1000
        println("<< [代理后置] 执行完成: ${method.name}, 返回=$result, 耗时=${costMicros}us")
        return result
    }
}

fun main() {
    // 让 CGLIB 把生成的代理类字节码 dump 到磁盘，便于反编译查看真实结构
    val dumpDir = "build/cglib-generated"
    java.io.File(dumpDir).mkdirs()
    System.setProperty("cglib.debugLocation", dumpDir)

    println("========== 1. 用 Enhancer 生成代理类 ==========")
    val enhancer = Enhancer()
    enhancer.setSuperclass(OrderService::class.java)   // ← 指定父类：代理将继承它
    enhancer.setCallback(LoggingInterceptor())         // ← 织入的增强逻辑
    val proxy = enhancer.create() as OrderService

    println("原始类  : ${OrderService::class.java.name}")
    println("代理类  : ${proxy.javaClass.name}")
    println("代理是否为 OrderService 的子类: ${OrderService::class.java.isAssignableFrom(proxy.javaClass)}")
    println("代理的父类: ${proxy.javaClass.superclass.name}")

    println("\n========== 2. 调用被代理的 open 方法（会被拦截）==========")
    val order = proxy.createOrder("A1001")
    println("最终拿到: $order")

    println("\n========== 3. 调用 final 方法（无法被重写，拦截器拦不到）==========")
    proxy.healthCheck()

    println("\n========== 4. 生成的代理类字节码已 dump 到: $dumpDir ==========")
    java.io.File(dumpDir).walkTopDown()
        .filter { it.name.endsWith(".class") }
        .forEach { println("  生成文件: ${it.name}") }

    println(
        """
        |
        |结论：
        |  · 代理类 = 目标类的【子类】（继承关系），由 Enhancer 在运行时生成字节码
        |  · open 方法被子类重写 → 走 MethodInterceptor → 前后置增强生效
        |  · final 方法无法重写 → 直接执行原始实现，增强失效
        |  · 这正是 Spring @Async/@Transactional 在 final/private 方法上失效的根因
        """.trimMargin(),
    )
}
