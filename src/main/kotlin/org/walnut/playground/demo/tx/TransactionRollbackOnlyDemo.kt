package org.walnut.playground.demo.tx

import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionTemplate
import java.util.Properties
import javax.sql.DataSource

/**
 * 复现并修复：非关键路径（metrics）异常污染主业务事务，导致提交阶段抛
 * UnexpectedRollbackException。
 *
 * 背景问题：
 *  在 NotificationService 的 @Transactional 业务事务里调用 putMetrics，
 *  putMetrics 内部又走 JPA 查询（dealRepository.findByIdOrThrow(...)）。
 *  当这里发生异常时，虽然 runSafely 的 try/catch 把异常吞掉了，但因为
 *  putMetrics 与主业务【共用同一个事务】（默认传播 REQUIRED），Spring 在
 *  “参与型事务”抛异常时会把当前事务标记为 rollback-only。业务方法看似正常
 *  返回，但事务代理在提交阶段发现 rollback-only，触发整体回滚并抛
 *  UnexpectedRollbackException。
 *
 * 本质：metrics 与主业务共用同一事务上下文，非关键路径异常污染了主事务。
 *
 * 修复：把 putMetrics 设为 @Transactional(propagation = NOT_SUPPORTED)，
 *  让它在【非事务】上下文执行（挂起当前事务），失败不再标记外层事务
 *  rollback-only，主事务得以正常提交。
 *
 * 运行： ./gradlew runTxDemo
 */

private fun log(tag: String, msg: String) = println("[$tag] $msg")

/** 模拟“非关键路径异常被吞掉”的工具方法。 */
private inline fun runSafely(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        // 当下这个异常确实被吞掉了；但主事务可能已被标记 rollback-only。
        log("SAFE", "已捕获并吞掉 metrics 异常: ${e.message}  →  业务代码以为“没事了”")
    }
}

// ---------------------------------------------------------------------------
// 领域对象与数据访问
// ---------------------------------------------------------------------------

@Entity
open class Deal(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null,
    open var name: String = "",
)

/** 用 EntityManager 直接实现的“仓库”，模拟 dealRepository。 */
open class DealRepository(private val em: EntityManager) {

    open fun save(deal: Deal): Deal {
        em.persist(deal)
        return deal
    }

    open fun count(): Long =
        em.createQuery("select count(d) from Deal d", java.lang.Long::class.java)
            .singleResult.toLong()

    /** 模拟 findByIdOrThrow：查不到（或反序列化/DB 异常）时抛运行时异常。 */
    open fun findByIdOrThrow(id: Long): Deal =
        em.find(Deal::class.java, id)
            ?: throw IllegalStateException("Deal($id) 不存在 —— 模拟 metrics 查询时的反序列化/DB 异常")
}

// ---------------------------------------------------------------------------
// metrics 服务：非关键路径
// ---------------------------------------------------------------------------

open class MetricsService(private val dealRepository: DealRepository) {

    /**
     * ❌ 有问题的版本：默认传播 REQUIRED，加入调用方（主业务）的事务。
     * 内部 JPA 查询抛异常时，Spring 会把【共用的那个事务】标记为 rollback-only。
     */
    @Transactional
    open fun putMetrics(dealId: Long) {
        log("METRICS", "putMetrics(REQUIRED) 开始，参与主业务事务，执行 JPA 查询 ...")
        dealRepository.findByIdOrThrow(dealId) // ← 抛异常 → 外层事务被标记 rollback-only
        log("METRICS", "putMetrics 正常结束（不会执行到这里）")
    }

    /**
     * ✅ 修复版本：NOT_SUPPORTED 挂起当前事务，在【非事务】上下文执行。
     * 即使内部查询抛异常，也不会标记外层主事务 rollback-only。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    open fun putMetricsFixed(dealId: Long) {
        log("METRICS", "putMetricsFixed(NOT_SUPPORTED) 开始，已挂起主事务，在非事务上下文执行 JPA 查询 ...")
        dealRepository.findByIdOrThrow(dealId) // ← 仍然抛异常，但不影响主事务
        log("METRICS", "putMetricsFixed 正常结束（不会执行到这里）")
    }
}

// ---------------------------------------------------------------------------
// 业务服务：主事务入口
// ---------------------------------------------------------------------------

open class NotificationService(
    private val dealRepository: DealRepository,
    private val metricsService: MetricsService,
) {

    /** ❌ 复现：调用有问题的 putMetrics。 */
    @Transactional
    open fun notifyBroken(missingDealId: Long) {
        log("BIZ", "notifyBroken 开始（@Transactional 主事务）")
        dealRepository.save(Deal(name = "broken-business-write")) // 主业务写入
        log("BIZ", "主业务已写入 Deal")

        runSafely { metricsService.putMetrics(missingDealId) } // 非关键路径，异常被吞

        log("BIZ", "notifyBroken 执行完毕，准备正常返回（表面一切正常）")
        // ↑ 方法正常返回；但事务已被标记 rollback-only，提交阶段将抛
        //   UnexpectedRollbackException，且看不到最原始的异常栈。
    }

    /** ✅ 修复：调用 NOT_SUPPORTED 的 putMetricsFixed。 */
    @Transactional
    open fun notifyFixed(missingDealId: Long) {
        log("BIZ", "notifyFixed 开始（@Transactional 主事务）")
        dealRepository.save(Deal(name = "fixed-business-write")) // 主业务写入
        log("BIZ", "主业务已写入 Deal")

        runSafely { metricsService.putMetricsFixed(missingDealId) } // 非关键路径，异常被吞

        log("BIZ", "notifyFixed 执行完毕，准备正常返回")
    }
}

// ---------------------------------------------------------------------------
// Spring 配置（手动装配 JPA + H2，避免干扰主应用的自动配置）
// 注意：本类未加 @Configuration/@Component，因此不会被 PlaygroundApplication 扫描到。
// ---------------------------------------------------------------------------

@EnableTransactionManagement
open class TxDemoConfig {

    @Bean
    open fun dataSource(): DataSource =
        EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build()

    @Bean
    open fun entityManagerFactory(dataSource: DataSource): LocalContainerEntityManagerFactoryBean {
        val emf = LocalContainerEntityManagerFactoryBean()
        emf.dataSource = dataSource
        emf.setPackagesToScan("org.walnut.playground.demo.tx")
        emf.jpaVendorAdapter = HibernateJpaVendorAdapter().apply { setGenerateDdl(true) }
        emf.setJpaProperties(Properties().apply {
            setProperty("hibernate.hbm2ddl.auto", "create-drop")
            setProperty("hibernate.show_sql", "false")
        })
        return emf
    }

    @Bean
    open fun transactionManager(emf: EntityManagerFactory): JpaTransactionManager =
        JpaTransactionManager(emf)

    /** 事务感知的共享 EntityManager（等价于 @PersistenceContext 注入的那个）。 */
    @Bean
    open fun sharedEntityManager(emf: EntityManagerFactory): EntityManager =
        SharedEntityManagerCreator.createSharedEntityManager(emf)

    @Bean
    open fun dealRepository(sharedEntityManager: EntityManager): DealRepository =
        DealRepository(sharedEntityManager)

    @Bean
    open fun metricsService(dealRepository: DealRepository): MetricsService =
        MetricsService(dealRepository)

    @Bean
    open fun notificationService(
        dealRepository: DealRepository,
        metricsService: MetricsService,
    ): NotificationService = NotificationService(dealRepository, metricsService)
}

fun runTransactionRollbackOnlyDemo() {
    val ctx = AnnotationConfigApplicationContext(TxDemoConfig::class.java)
    val notificationService = ctx.getBean(NotificationService::class.java)
    val dealRepository = ctx.getBean(DealRepository::class.java)

    // 一个不存在的 id，用来触发 metrics 内部的 JPA 查询异常
    val missingId = 999L

    println("======================================================")
    println("代理类型检查（有 @Transactional 才会被代理）：")
    println("  NotificationService = ${notificationService.javaClass.name}")
    println("  MetricsService      = ${ctx.getBean(MetricsService::class.java).javaClass.name}")
    println("  事务拦截器存在?      = ${ctx.getBeanNamesForType(TransactionInterceptor::class.java).isNotEmpty()}")
    println("======================================================")

    // -----------------------------------------------------------------
    println("\n---------- 场景一：❌ putMetrics = REQUIRED（复现问题）----------")
    try {
        notificationService.notifyBroken(missingId)
        println(">> 主业务方法返回，未见异常（这只是假象）")
    } catch (e: Exception) {
        println(">> 【提交阶段抛出】${e.javaClass.name}")
        println(">>   message = ${e.message}")
        println(">>   注意：这里看到的是提交期异常，最原始的 metrics 异常栈已被 runSafely 吞掉。")
    }
    println(">> 结果：Deal 表记录数 = ${countInReadOnlyTx(ctx, dealRepository)}（主业务写入被整体回滚 → 仍为 0）")

    // -----------------------------------------------------------------
    println("\n---------- 场景二：✅ putMetrics = NOT_SUPPORTED（修复后）----------")
    try {
        notificationService.notifyFixed(missingId)
        println(">> 主业务方法正常返回，且事务成功提交")
    } catch (e: Exception) {
        println(">> 不应发生：${e.javaClass.name}: ${e.message}")
    }
    println(">> 结果：Deal 表记录数 = ${countInReadOnlyTx(ctx, dealRepository)}（主业务写入已提交 → 变为 1）")

    println("\n======================================================")
    println("结论：")
    println("  · REQUIRED：metrics 与主业务共用事务，metrics 异常 → 外层事务被标记 rollback-only")
    println("             → runSafely 吞掉当下异常，但提交阶段抛 UnexpectedRollbackException，主业务被回滚")
    println("  · NOT_SUPPORTED：metrics 在非事务上下文执行，异常不污染主事务 → 主业务正常提交")
    println("  · 前提：putMetrics 必须【跨 Bean 调用】(注入的代理)，自调用会让传播注解失效")
    println("======================================================")

    ctx.close()
}

/** 在一个只读事务里统计记录数。 */
private fun countInReadOnlyTx(
    ctx: AnnotationConfigApplicationContext,
    dealRepository: DealRepository,
): Long {
    val tm = ctx.getBean(JpaTransactionManager::class.java)
    val tt = TransactionTemplate(tm).apply { isReadOnly = true }
    return tt.execute { dealRepository.count() }!!
}
