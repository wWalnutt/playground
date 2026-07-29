package org.walnut.playground.demo.async

import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Service
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 验证 Spring @Async 在【同一个 Service 内部自调用】与【跨 Service 调用】时的区别。
 *
 * 对应文档：doc/learn/AutoScanService_AOP_Analysis.md
 *
 * 结论预期：
 *  1. 跨 Service 调用（CoordinatorService -> RunnerService.run()）：
 *     调用的是注入进来的另一个 Bean（代理对象），@Async 生效，run() 在异步线程执行。
 *  2. 同一个 Service 内部自调用（MergedService.trigger() -> this.run()）：
 *     this 指向原始对象而非代理，绕过了 AOP 拦截，@Async 失效，run() 在调用线程同步执行。
 *
 * 判断标准：观察每个方法打印的线程名。
 *  - 异步线程名前缀为 "async-demo-"
 *  - 主线程名为 "main"
 */

@Configuration
@EnableAsync
@ComponentScan(basePackageClasses = [AsyncSelfInvocationMarker::class])
class AsyncSelfInvocationConfig : AsyncConfigurer {

    override fun getAsyncExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 2
        executor.maxPoolSize = 4
        executor.setThreadNamePrefix("async-demo-")
        executor.initialize()
        return executor
    }
}

/** 仅用于限定 @ComponentScan 的扫描范围。*/
class AsyncSelfInvocationMarker

private fun log(tag: String, msg: String) {
    println("[$tag] 线程=${Thread.currentThread().name} | $msg")
}

// ---------------------------------------------------------------------------
// 场景一：跨 Service 调用（✅ 正确，@Async 生效）
// ---------------------------------------------------------------------------

@Service
open class RunnerService {

    @Async
    open fun run(taskId: Int) {
        log("RUNNER", "开始执行核心逻辑 task=$taskId")
        TimeUnit.MILLISECONDS.sleep(300)
        log("RUNNER", "完成核心逻辑 task=$taskId")
    }
}

@Service
open class CoordinatorService(
    private val runnerService: RunnerService,
) {
    // 注意：这里没有 @Async。异步性完全依赖 runnerService 这个【注入的代理对象】。
    open fun trigger(taskId: Int) {
        log("COORDINATOR", "调用 runnerService.run() —— 这是对另一个 Bean(代理) 的调用")
        runnerService.run(taskId)
        log("COORDINATOR", "trigger 返回（若异步生效，此行应在 RUNNER 完成之前打印）")
    }
}

// ---------------------------------------------------------------------------
// 场景二：同一个 Service 内部自调用（❌ 错误，@Async 失效）
// ---------------------------------------------------------------------------

@Service
open class MergedService {

    // 入口方法：无 @Async
    open fun trigger(taskId: Int) {
        log("MERGED", "调用 this.run() —— 这是对同一对象原始方法的调用")
        run(taskId)
        log("MERGED", "trigger 返回（若 @Async 失效，此行会在 run 完成之后才打印）")
    }

    // 核心方法：标记 @Async，但由于自调用绕过代理，注解不会生效
    @Async
    open fun run(taskId: Int) {
        log("MERGED", "开始执行核心逻辑 task=$taskId")
        TimeUnit.MILLISECONDS.sleep(300)
        log("MERGED", "完成核心逻辑 task=$taskId")
    }
}

fun main() {
    val ctx = AnnotationConfigApplicationContext(AsyncSelfInvocationConfig::class.java)
    val coordinator = ctx.getBean(CoordinatorService::class.java)
    val merged = ctx.getBean(MergedService::class.java)

    println("======================================================")
    println("代理类型检查：")
    println("  CoordinatorService bean = ${coordinator.javaClass.name}")
    println("  MergedService      bean = ${merged.javaClass.name}")
    println("======================================================")

    println("\n---------- 场景一：跨 Service 调用（期望：异步生效）----------")
    coordinator.trigger(1)
    TimeUnit.MILLISECONDS.sleep(500)

    println("\n---------- 场景二：同一 Service 内部自调用（期望：异步失效）----------")
    merged.trigger(2)
    TimeUnit.MILLISECONDS.sleep(500)

    println("\n======================================================")
    println("结论：")
    println("  场景一 RUNNER 打印的线程应为 async-demo-*  → @Async 生效")
    println("  场景二 MERGED 打印的线程应为 main          → @Async 失效（自调用绕过代理）")
    println("======================================================")

    ctx.close()
}
