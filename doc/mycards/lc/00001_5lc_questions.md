# 完成 5 道 LeetCode 题目（LC 训练卡）

本卡目标是完成并提交 `lc` 目录下 5 道算法题实现，形成可复盘、可扩展的题解代码基础。

---

## 一、Context（背景）

### 1. Feature 背景

为提升 Kotlin 编码熟练度与常见算法题型覆盖，需按固定节奏完成 LeetCode 练习，并将解法沉淀到代码库。

### 2. 本卡背景

本卡对应首次 LC 训练提交（`be06ac3`），范围限定为 `src/main/kotlin/org/walnut/playground/lc` 下的 5 道题。重点是完成可运行实现与题型覆盖（哈希、双指针/字符串、贪心、枚举）。

---

## 二、In Scope / Out of Scope（边界）

### In Scope（做什么）

- 在 `lc` 目录完成以下 5 题 Kotlin 实现：
  - Two Sum
  - Merge Strings Alternately（1768）
  - Kids With the Greatest Number of Candies（1431）
  - Can Place Flowers（605）
  - Greatest Common Divisor of Strings（1071）
- 每题提供独立函数实现，命名与题意一致
- 对应测试
- 代码提交到仓库并保留在同一提交链路中（当前对应 `be06ac3`）

### Out of Scope（不做什么）

- 不要求补充题解文档（复杂度推导/图解）

---

## 三、Acceptance Criteria（验收标准）

**AC1 - 题目数量达标**
- **Given** `lc` 目录存在题解代码
- **When** 检查目标文件
- **Then** 恰好包含本卡定义的 5 道题实现文件

**AC2 - 函数可用**
- **Given** 每题函数已实现
- **When** 以典型输入调用
- **Then** 返回结果符合题目预期输出

**AC3 - 提交可追踪**
- **Given** 代码已进入版本库
- **When** 查看 `lc` 路径提交历史
- **Then** 可定位到本卡对应提交（`be06ac3`）及其改动

**AC4 - 目录结构规范**
- **Given** 所有题目代码位于 Kotlin 工程
- **When** 查看文件路径
- **Then** 均位于 `src/main/kotlin/org/walnut/playground/lc`

---

## 四、Tech 实现参考

### 当前题目清单（基于仓库文件）

```text
src/main/kotlin/org/walnut/playground/lc/TwoSums.kt
src/main/kotlin/org/walnut/playground/lc/MergeStringsAlternately1768.kt
src/main/kotlin/org/walnut/playground/lc/KidsWithTheGreatestNumberOfCandies1431.kt
src/main/kotlin/org/walnut/playground/lc/CanPlaceFlowers605.kt
src/main/kotlin/org/walnut/playground/lc/GreatestCommonDivisorOfStrings1071.kt
```

### 题型与实现要点

- **Two Sum**：哈希表记录已遍历数字与下标，单次扫描查补数。
- **Merge Strings Alternately**：按最短长度交替拼接，尾部追加剩余子串。
- **Kids With Candies**：先取最大值，再映射判断 `candy + extra >= max`。
- **Can Place Flowers**：贪心判断当前位置及相邻位置是否可种花。
- **GCD of Strings**：先判可拼接相等性，再从长度因子中找最大公共构造串。

---

## 五、Open Questions（待澄清问题）

---

## 六、Self Check（自检清单）

### 功能
- [ ] 5 道题代码均已存在且可编译
- [ ] 每题用至少 1 组样例手工验证通过
- [ ] 边界输入有基本覆盖（空/最小长度/极值）

### 代码质量
- [ ] 函数职责单一，命名清晰
- [ ] 无无效变量与冗余分支
- [ ] 复杂度符合题目常见最优解思路

### 协作
- [ ] 代码已提交并可通过 commit 定位
- [ ] 卡片描述与目录实际内容一致