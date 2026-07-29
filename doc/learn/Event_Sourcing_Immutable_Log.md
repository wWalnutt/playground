# 📜 Event Sourcing & Immutable Log Architecture Note

> **核心哲学：** 传统系统存的是"结果"（当前状态），而事件溯源存的是"过程"（发生过的一切事实）。数据不是被 `UPDATE` 出来的，而是通过历史事件重放（Replay）推导计算出来的。

---

## 💡 一、核心思维转换：状态存取 vs 事件流

### 1. 模式对比

```text
【 传统 CRUD 模式 (覆盖写) 】
UPDATE t_account SET balance = 80 WHERE user_id = 101;
👉 旧值 100 被直接抹掉！缺乏物理上下文，无法恢复历史轨迹。

【 事件溯源模式 (追加写) 】
INSERT INTO events VALUES (001, 'AccountCreated', {balance: 0});
INSERT INTO events VALUES (002, 'MoneyDeposited', {amount: 100});
INSERT INTO events VALUES (003, 'MoneyWithdrawn', {amount: 20});
👉 记录全量事实日志，状态 80 元是顺着事件重放计算出来的。
```

| 比较维度 | 传统 CRUD 数据库 | 事件溯源 (Event Sourcing) |
| --- | --- | --- |
| **存储内容** | 数据的当前最新状态 | 导致状态变更的所有历史事件序列 |
| **物理操作** | UPDATE / DELETE 破坏性覆盖写 | 仅追加（Append-Only），绝不更新或删除 |
| **审计追溯** | 依赖额外的审计表，极易丢历史轨迹 | 天然具备 100% 完整审计日志，支持"时间旅行" |
| **计算逻辑** | State = Current Row | Current State = f(All Historical Events) |

---

## 🧱 二、底层基石：不可变日志（Immutable Log）

不可变日志是事件溯源、现代消息队列（Kafka）及数据库底层（WAL / Redo Log）的核心物理基础。

**为什么选择"仅追加（Append-Only）"？**

- **极致的写性能（顺序 I/O）**：磁盘/SSD 的随机写入（Random Write）性能极差（产生寻道开销或垃圾回收）；追加日志永远在文件末尾顺序写入，将磁盘物理写性能拉到极致。
- **零并发锁争用（Lock-Free）**：已经写入的日志不可变更（Immutable），读写操作天然解耦，读取者不需要加排他锁。
- **彻底消除数据损坏（Zero Corruption）**：避免传统 UPDATE 掉电导致的半写入数据损坏（Page Corruption），追加写容易通过 Checksum 校验与恢复。

---

## ⚙️ 三、事件溯源的三大核心构建块

```text
 [ Command 命令 ] ──> [ Domain Event 领域事件 ] ──> [ 追加写入 Event Store ]
                                                             │
                                                             ▼
 [ Replay 状态重放 ] <─── [ Periodical Snapshot 快照 ] <─────┘
```

**1. 领域事件（Domain Event）**

表示过去已经发生的业务事实，统一使用过去时态命名（如 `OrderCreated`、`AccountFrozen`）。事件包含元数据（`event_id`, `aggregate_id`, `timestamp`）与业务 Payload。

**2. 事件存储库（Event Store）**

专用的追加型数据库（如 EventStoreDB，或基于 PostgreSQL / Kafka 构建）。核心操作：`appendEvent()` 与 `getEventsByAggregateId()`。

**3. 快照机制（Snapshotting）**

- **解决痛点：** 某个聚合根（如交易频繁的账户）累积了数百万个事件时，每次重建状态都要重放上百万个事件，导致查询严重超时。
- **计算公式：** Current State = Snapshot(v1000) + Replay(Events 1001 → now)
- **做法：** 异步后台线程每隔 N 个事件生成一份快照存入持久层，读取时只需拉取最近一份快照 + 加载快照之后的增量事件即可。

---

## 🏛️ 四、生产极速方案：事件溯源 + CQRS 架构

在实际落地中，事件溯源几乎 100% 配合 **CQRS（命令查询职责分离）** 一起使用：

```text
                         ┌──────────────────────────┐
                         │   客户端 / UI            │
                         └─────────────┬────────────┘
                                       │
               ┌───────────────────────┴───────────────────────┐
               ▼                                               ▼
       【 写链路 (Command) 】                         【 读链路 (Query) 】
               │                                               ▲
               ▼                                               │
  [ 校验业务规则 & 产生 Event ]                                 │
               │                                               │
               ▼                                               │
  [ 追加写入 Event Store (主库) ]                             │
               │                                               │
               └───────────────► [ 异步订阅 / 投影 (Projection) ]
                                               │
                                               ▼
                                   [ 更新物化视图 (Read DB) ]
                                   (如 PostgreSQL / Elasticsearch)
```

- **写侧（Command Path）：** 仅处理写命令，校验业务规则后产生 Event，并将 Event 追加到 Event Store（速度极快）。
- **投影器（Projection）：** 异步监听 Event Store 的事件流，将事件"打平"并"转化"。
- **读侧（Query Path）：** 投影器更新物化视图（Materialized View，如 MySQL / ES），提供极度适合前端展示的单表查询模型。

---

## ⚠️ 五、生产避坑与挑战

### 1. 事件版本演进问题（Schema Evolution）

- **场景：** 3 年前的 `OrderCreated` 事件缺少 `tax_amount` 字段，新代码要求必须包含该字段。
- **解法：** 使用 Upcaster（事件升级器）不修改数据库里老事件的 JSON 字符串；在事件从 Event Store 读取反序列化到内存时，通过 Upcaster 拦截并填充默认值，平滑升级到新版 Schema。

### 2. 最终一致性延迟（Eventual Consistency）

- **场景：** 用户刚提交订单（写侧），立刻刷新页面（读侧），由于 Projection 异步同步有毫秒级延迟，可能查不出最新订单。
- **解法：** 客户端做 UI 预渲染（Optimistic UI Update）；带上版本号查询：`WHERE version >= x`，若未查到则前端轮询重试。

---

## 🎯 选型总结（面试/架构评估）

**🟢 适用场景**

- **金融/银行/审计系统：** 资金往来、交易明细，对数据可追溯性和合规审计要求极高。
- **复杂状态流转系统：** 游戏角色状态变化、物流运单轨迹跟踪。
- **回溯与预测分析：** 需要做"时间旅行（Time Travel）"，在开发环境重放生产环境的历史事件，复现复杂 Bug 或验证新算法。

**🔴 不适用场景**

- 简单的 CRUD 增删改查系统（过度设计，增加系统复杂度）。
- 团队缺乏对最终一致性、CQRS 及异步事件管道处理能力的场景。
