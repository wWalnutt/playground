# Kafka 核心架构与高并发高可用速查笔记

---

## 🏗️ 一、 Kafka 核心概念解析

### 1. 逻辑与物理概念对照

| 概念 | 层面 | 对应物理/真实含义 | 核心作用 |
| :--- | :--- | :--- | :--- |
| **Cluster** | 物理 | 物理服务器集群 | 多个 Kafka 节点组成的分布式系统。 |
| **Broker** | 物理 | 单台物理/虚拟服务器 | 集群中的独立节点服务进程。 |
| **Topic** | 逻辑 | 消息分类名称（如 `order-topic`） | 业务消息的分类与路由管道。 |
| **Partition** | 物理 | **磁盘上的真实文件夹** | Topic 的横向物理切片。一个 Topic 包含多个 Partition，分布在不同 Broker 上，**是高吞吐量的核心设计**。 |
| **Segment** | 物理 | `.log` / `.index` / `.timeindex` 文件块 | 单个 Partition 内部切分的大文件块，以追加写（Append-Only）方式记录数据。 |
| **Offset** | 逻辑/物理 | 64 位单调递增整数 | 消息在 Partition 内部的唯一物理排队序号，以及消费者组的消费进度“书签”。 |
| **Replica** | 物理 | 分区在其他 Broker 上的备份文件夹 | 副本分为 **Leader（负责读写）** 和 **Follower（默默同步 Leader 备份）**，实现分区级高可用。 |
| **Consumer Group** | 逻辑 | 共享同一个 `group.id` 的应用实例集合 | 消费者组。组内多个实例协同消费同一个 Topic，**每条消息在组内仅被消费一次**。 |

---

## 🚀 二、 Kafka 如何实现极致的高吞吐量？（性能五大底牌）

### 1. 分区切片与水平并行（Partitioning）

* 将一个 Topic 拆分为多个 Partition 打散在不同 Broker 上。
* 生产者与消费者可同时对多个 Partition 开展并行读写，吞吐量随节点数线性扩展。

### 2. 磁盘顺序写（Sequential Disk I/O）

* 避免传统数据库随机寻道带来的巨大开销，Kafka 在 Segment `.log` 文件末尾仅进行 **追加写入（Append-Only）**。
* 磁头无需频繁寻道，磁盘顺序写的吞吐性能堪比内存读写。

### 3. 页缓存机制（Page Cache）

* Kafka 避开了 JVM 堆内存（避免频繁 GC 停顿与内存复制开销），直接大量使用操作系统内核层的 **Page Cache** 读写数据。

### 4. 零拷贝技术（Zero-Copy）

* 在 Consumer 消费拉取数据时，通过 Linux 的 `sendfile` 系统调用：
  `磁盘文件 -> Page Cache -> 网卡 Socket 缓冲区`
* 数据无需经过 JVM 用户态内存，减少了两次 CPU 上下文切换与两次内存拷贝。

### 5. 稀疏索引与二分查找（Sparse Index）

* `.index` 索引文件每隔指定字节数记录一次消息 Offset 与物理字节偏移的映射。
* 检索消息时，通过 **二分查找（Binary Search）** 极速定位磁盘物理位置。

---

## 🛡️ 三、 高并发下如何保证数据一致性？（全链路防丢失）

保证数据一致性的核心在于 **“生产者不漏发、服务端不丢失、消费者不漏读”**：

```text
[ Producer ] ───(1. acks=all + 自动重试)───> [ Leader Partition ]
                                                    │
                                           (2. ISR 副本强同步)
                                                    ▼
[ Consumer ] <───(3. 先业务落盘，后手动提交 Offset)── [ Follower Partition ]
```

### 1. 生产者端（Producer）：强确认 + 自动重试

acks = all (或 -1)： 发送消息后，必须等待 ISR（In-Sync Replicas）列表中所有的 Follower 副本 全部写入成功，才返回 SUCCESS ACK。

retries = Integer.MAX_VALUE： 遇到网络超时或发送失败时，自动发起无限重试直至成功。

max.in.flight.requests.per.connection = 1： 限制单连接并发数，确保重试时消息不会乱序。

### 2. 服务端（Broker）：副本冗余 + 拒绝脏 Leader

replication.factor >= 3： 一个 Partition 配置 3 个物理副本（1 Leader + 2 Follower），打散部署在不同机器上。

min.insync.replicas = 2： 配合 acks = all 使用，保证至少有 2 个副本写入成功才算本次写入成功。

unclean.leader.election.enable = false： 禁用非同步 Follower 选举。当 Leader 挂掉时，绝对不允许没跟上进度的脏 Follower 升为新 Leader。

### 3. 消费者端（Consumer）：手动提交 Offset

enable.auto.commit = false： 关闭自动提交 Offset，防止未消费完机器重启导致丢消息。

先业务落盘，后手动提交： 确保数据库 UPDATE 或业务消费逻辑完全执行成功后，再显式调用 commitSync() / commitAsync() 提交 Offset。

---

## ⚡ 四、 高并发下如何保证幂等性？（全链路防重复写脏）

为应对网络抖动导致的重发或消费者重平衡导致的重复拉取，Kafka 采用 “框架原生 + 业务防重” 双层防线：

### 1. 第一层：Kafka 原生 Producer 幂等（框架自带）

配置方式：enable.idempotence = true（从 0.11 版本起默认推荐开启）。

底层的核心机制：

- PID (Producer ID)： 启动时由 Broker 统一颁发给 Producer 进程的全局唯一 ID。

- Sequence Number（序列号）： Producer 客户端 SDK 在本地内存中为发往每个 Partition 的消息贴上递增的序号（0, 1, 2...）。

Broker 防重判重逻辑：

- Broker 在内存与磁盘中记录每一个 <PID, Partition> 对应的最高序列号 Max_Seq。

- 若收到重发的平级或落后序号（Seq <= Max_Seq），Broker 丢弃该消息 Payload（不重复写盘），但依然返回 SUCCESS ACK 终止 Producer 的重试。

局限： 仅保证单个 Producer 会话、单个 Partition 内部不重复。

### 2. 第二层：Consumer 业务层幂等（最关键底线）

因消费者面临 Rebalance 或重启，仍可能收到重复 Offset 的消息，必须在 Consumer 业务代码层实现强幂等：

数据库唯一索引（Unique Index）：

- 在数据库中对业务主键（如 deal_id / event_id）建立 UNIQUE INDEX。
- 重复插入触发 DuplicateKeyException 异常，捕获后直接跳过并提交 Offset。

Redis SETNX 防重占位：

- 消费前先执行 SET deal_12345 "1" NX EX 86400。
- 若返回 null 说明此前已成功消费，跳过业务逻辑。

状态机前置条件限制（State Guard）：

- 利用状态更新 SQL 的严格前置检查：

```sql
UPDATE t_deal SET status = 'DONE' WHERE deal_id = 888 AND status = 'PENDING';
```

- 若状态已被修改，affected_rows 为 0，避免重复写脏数据。
