# 项目初始化与 GitHub 关联

本卡用于完成代码库从 0 到可协作状态的初始化：本地仓库可用、远程 GitHub 仓库已绑定、首个提交与分支规范就绪。

---

## 一、Context（背景）

### 1. Feature 背景

当前团队需要将本地项目纳入 Git 协作流程，确保后续开发可追踪、可回滚、可通过 PR 协作。

### 2. 本卡背景

本卡聚焦基础设施层面的“起盘”工作：初始化仓库、补齐基础忽略规则、连接 GitHub 远程、推送首个提交。完成后，后续功能卡才能基于标准 Git 流程开展。

---

## 二、In Scope / Out of Scope（边界）

### In Scope（做什么）

- 初始化本地 Git 仓库（若尚未初始化）
- 配置基础 `.gitignore`（按当前技术栈）
- 创建首个初始化提交（Initial commit）
- 在 GitHub 创建对应远程仓库（空仓库）
- 添加 `origin` 并完成首次推送
- 设置默认分支（`main`）并验证本地与远程跟踪关系

### Out of Scope（不做什么）

- CI/CD 流水线配置
- 分支保护规则（保护主分支、强制审查等）
- Issue/PR 模板、CODEOWNERS、自动发布流程
- 业务代码开发与重构

---

## 三、Acceptance Criteria（验收标准）

**AC1 - 本地仓库初始化完成**
- **Given** 本地项目目录已存在
- **When** 执行仓库初始化与状态检查
- **Then** 目录存在有效 `.git`，`git status` 可正常返回

**AC2 - 首个提交完成**
- **Given** 初始化文件与忽略规则已准备
- **When** 执行 `git add` 与 `git commit`
- **Then** 本地存在首个提交记录，提交信息可识别为初始化提交

**AC3 - GitHub 远程关联完成**
- **Given** GitHub 空仓库已创建
- **When** 执行 `git remote add origin <repo-url>`
- **Then** `git remote -v` 显示正确 `origin` 地址

**AC4 - 首次推送成功**
- **Given** 本地 `main` 分支已有提交且已关联 `origin`
- **When** 执行 `git push -u origin main`
- **Then** 远程仓库可见同样提交历史，且本地分支已建立 upstream

---

## 四、Tech 实现参考

### 推荐命令流程

```bash
# 1) 初始化（已初始化可跳过）
git init

# 2) 配置默认分支名（可选，建议）
git branch -M main

# 3) 添加文件并提交
git add .
git commit -m "chore: initialize project"

# 4) 绑定远程仓库
git remote add origin <github-repo-url>

# 5) 首次推送并建立跟踪关系
git push -u origin main
```

### 关键注意事项

- GitHub 新建仓库时保持“空仓库”（不要勾选 README/.gitignore 初始化），避免与本地首提交流程冲突。
- 若远程已存在提交历史，先 `git pull --rebase origin main` 再推送。
- 如本地误配远程地址，可用 `git remote set-url origin <new-url>` 修正。

---

## 五、Open Questions（待澄清问题）


---

## 六、Self Check（自检清单）

### 仓库状态
- [ ] `git status` 正常
- [ ] `git log --oneline` 可见初始化提交
- [ ] `.gitignore` 已覆盖构建产物与本地环境文件

### 远程关联
- [ ] `git remote -v` 显示正确 `origin`
- [ ] `git push -u origin main` 成功
- [ ] GitHub 页面可见同一提交历史

### 协作准备
- [ ] 已确认仓库可见性
- [ ] 已确认默认分支命名
- [ ] 团队成员具备仓库访问权限