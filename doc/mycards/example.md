# 如何写一张合格的 Jira 卡

一张好的 Jira 卡，不只是一句话需求描述，它应该让接手的人不需要追着问就能理解背景、明确边界、知道怎么做、知道做完怎么验。

下面以「用户登录接入 MFA 双因素认证」为例，介绍一张合格 Jira 卡的完整结构。

---

## 一、Context（背景）

Context 分两层写，缺一不可。

### 1. Feature 背景
说明这个功能**为什么要做**，业务或产品层面的驱动是什么。读者应该能理解这张卡所属的更大目标。

> 本季度安全团队评审发现，当前用户登录仅依赖用户名+密码，存在账号被盗风险。产品决策在 Q3 引入 MFA（Multi-Factor Authentication）能力，优先覆盖企业用户，后续推广至全量。MFA 方案采用 TOTP，兼容 Google Authenticator / Authy 等主流 App。

### 2. 本卡背景
说明**这张卡具体要解决什么**，在整个 Feature 中处于什么位置，有哪些前置依赖。

> 本卡负责登录页面前端侧的 MFA 接入：在用户完成用户名+密码校验（Step 1）后，展示 6 位验证码输入步骤（Step 2），并调用后端验证接口完成整体登录流程。后端 MFA 验证接口已由 PROJ-1230 完成，本卡依赖该接口。UI 设计稿见 Figma：[链接]

---

## 二、In Scope / Out of Scope（边界）

### In Scope（做什么）

明确列出本卡要交付的内容，粒度要细到可以逐项 check。

- 登录流程新增 Step 2 页面，展示 6 位 TOTP 验证码输入框
- 用户输入验证码后调用 `POST /api/auth/mfa/verify` 接口
- 验证成功后跳转至原登录成功逻辑
- 验证失败展示错误提示，最多重试 3 次，超出后锁定并引导重新登录
- 加载态、错误态、成功态的完整 UI 状态处理
- 单元测试覆盖核心交互逻辑

### Out of Scope（不做什么）

主动写出范围外的内容，防止需求蔓延，也让 PM / QA 不产生误解。

- MFA 绑定/解绑流程（见 PROJ-1235）
- 后端 MFA 验证接口实现（已完成，见 PROJ-1230）
- 记住设备 30 天功能（排期 Q4，见 PROJ-1280）
- 短信 OTP 实际发送逻辑（本卡仅预留 UI 入口）

---

## 三、Acceptance Criteria（验收标准）

用 **Given / When / Then** 格式写，每条 AC 对应一个独立的验收场景，QA 可以直接转为测试用例。

**AC1 - 正常验证流程**
- **Given** 用户已完成 Step 1 登录，且账号已绑定 MFA
- **When** 用户在 Step 2 输入正确的 6 位验证码并提交
- **Then** 登录成功，跳转 Dashboard，Token 正确写入

**AC2 - 验证码错误提示**
- **Given** 用户在 Step 2 输入验证码
- **When** 输入的验证码错误（接口返回 401）
- **Then** 展示「验证码错误，请重试」，输入框清空，剩余次数减 1

**AC3 - 超过重试次数锁定**
- **Given** 用户已连续错误 3 次
- **When** 第 3 次错误后
- **Then** 验证按钮禁用，展示锁定提示，提供「返回登录」入口

**AC4 - 未绑定 MFA 的用户不展示 Step 2**
- **Given** 用户账号未绑定 MFA
- **When** 完成 Step 1 登录
- **Then** 直接跳过 Step 2，正常完成登录

**AC5 - 网络异常处理**
- **Given** 用户点击「验证」
- **When** 接口超时或返回 5xx
- **Then** 展示通用网络错误提示，按钮恢复可点击

---

## 四、Tech 实现参考

给开发者提供足够的技术上下文，不需要面面俱到，但关键的接口契约、数据流、组件位置要说清楚。

### 接口

```
POST /api/auth/mfa/verify
Authorization: Bearer <step1_token>

Request Body:
{ "code": "123456" }

Response 200: { "access_token": "...", "refresh_token": "..." }
Response 401: { "error": "INVALID_CODE", "remaining_attempts": 2 }
Response 423: { "error": "ACCOUNT_LOCKED" }
```

### 页面流程

```
LoginPage (Step 1)
    │ 成功 → 后端返回 { mfa_required: true, step1_token }
    ▼
MfaVerifyPage (Step 2)
    ├── 200 → 写入 Token → redirect
    ├── 401 → 更新剩余次数 → 展示错误
    └── 423 → 锁定态 UI
```

### 组件 & 注意事项

- 新建 `MfaVerifyStep` 组件，放在 `src/features/auth/components/`
- 可复用已有 `OtpInput` 组件（`src/components/OtpInput`）
- `step1_token` 通过 React state 传递，**不要存入 localStorage**

---

## 五、Open Questions（待澄清问题）

开发前未解决的问题要显式列出来，标注负责人和状态，避免在开发过程中被阻塞。

| # | 问题 | 负责人 | 状态 |
|---|------|--------|------|
| 1 | 锁定后是否需要发邮件通知用户？后端是否已实现？ | @backend | 🔴 待确认 |
| 2 | 「重新发送」冷却时间是 60s 还是 30s？ | @PM | 🔴 待确认 |
| 3 | MFA 验证失败是否需要上报安全日志？ | @security | 🟡 讨论中 |
| 4 | Step 2 是否需要支持回车键提交？ | @design | ✅ 已确认：需要 |

> ⚠️ **原则**：Open Questions 中状态为 🔴 的问题，必须在开发开始前解决或降级处理，不能带着歧义开发。

---

## 六、Self Check（自检清单）

提交 PR 前，由开发者自行逐项确认，减少 review 来回。

### 功能
- [ ] 所有 AC 在本地验证通过
- [ ] 未绑定 MFA 的用户登录流程回归正常

### 代码质量
- [ ] 无 `console.log` 残留
- [ ] 无硬编码文案，均走 i18n
- [ ] 新增代码有单元测试，覆盖率达标
- [ ] 接口所有错误码均有处理

### UI
- [ ] 与 Figma 设计稿对齐
- [ ] Mobile 视图布局正常
- [ ] Loading / Error / Disabled 态均已实现

### 安全
- [ ] `step1_token` 未持久化到 localStorage
- [ ] 验证码输入框设置了 `autocomplete="one-time-code"`

### 协作
- [ ] PR 描述关联了 Jira 卡号
- [ ] 已通知 QA 更新测试用例
- [ ] Open Questions 中的 🔴 问题均已解决

---

## 总结：一张合格 Jira 卡的核心原则

| 原则 | 说明 |
|------|------|
| **背景清晰** | 让任何人拿到卡就能理解为什么要做 |
| **边界明确** | 明确说 in scope / out of scope，防止需求蔓延 |
| **AC 可测试** | 每条 AC 可以直接转为测试用例，不依赖口头约定 |
| **技术可落地** | 提供足够的技术上下文，开发者不需要自己猜接口 |
| **问题显式化** | 不确定的事情写出来追踪，不要藏在脑子里 |
| **自检兜底** | 提交前自己先 check，降低 review 来回成本 |
