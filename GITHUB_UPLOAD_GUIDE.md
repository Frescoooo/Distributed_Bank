# GitHub 上传指南

## 方法一：使用命令行（推荐）

### 步骤 1：打开 Git Bash 或 PowerShell

在项目目录右键选择 "Git Bash Here" 或打开 PowerShell

### 步骤 2：检查 Git 状态

```bash
git status
```

如果显示 "not a git repository"，需要先初始化：

### 步骤 3：初始化 Git 仓库（如果还没有）

```bash
git init
```

### 步骤 4：添加所有文件

```bash
git add .
```

### 步骤 5：提交更改

```bash
git commit -m "Update: Add error handling and network testing guide"
```

### 步骤 6：连接到 GitHub 仓库

**如果还没有创建 GitHub 仓库：**
1. 登录 GitHub
2. 点击右上角 "+" → "New repository"
3. 输入仓库名称（例如：`Distributed_Bank`）
4. 选择 Public 或 Private
5. **不要**勾选 "Initialize with README"（因为本地已有文件）
6. 点击 "Create repository"

**然后添加远程仓库：**
```bash
git remote add origin https://github.com/你的用户名/仓库名.git
```

例如：
```bash
git remote add origin https://github.com/username/Distributed_Bank.git
```

**如果已经添加过远程仓库，检查现有远程：**
```bash
git remote -v
```

### 步骤 7：推送到 GitHub

```bash
git push -u origin main
```

或者如果默认分支是 master：
```bash
git push -u origin master
```

---

## 方法二：使用 GitHub Desktop（图形界面）

### 步骤 1：下载 GitHub Desktop

从 https://desktop.github.com/ 下载并安装

### 步骤 2：登录 GitHub 账号

### 步骤 3：添加本地仓库

1. File → Add Local Repository
2. 选择项目目录
3. 点击 "Add repository"

### 步骤 4：提交更改

1. 在左侧看到所有更改的文件
2. 在底部输入提交信息：`Update: Add error handling and network testing guide`
3. 点击 "Commit to main"

### 步骤 5：发布到 GitHub

1. 点击 "Publish repository"
2. 输入仓库名称
3. 选择 Public 或 Private
4. 点击 "Publish repository"

---

## 快速命令脚本

### Windows PowerShell 脚本

在项目目录创建 `upload_to_github.ps1`：

```powershell
# 检查是否已初始化
if (-not (Test-Path .git)) {
    Write-Host "Initializing git repository..."
    git init
}

# 添加所有文件
Write-Host "Adding files..."
git add .

# 提交
Write-Host "Committing changes..."
git commit -m "Update: Add error handling and network testing guide"

# 检查远程仓库
$remote = git remote -v
if (-not $remote) {
    Write-Host "Please add remote repository first:"
    Write-Host "git remote add origin https://github.com/username/repo.git"
} else {
    Write-Host "Pushing to GitHub..."
    git push -u origin main
}
```

运行：
```powershell
.\upload_to_github.ps1
```

---

## 常见问题

### 问题 1：提示需要配置用户信息

```bash
git config --global user.name "Your Name"
git config --global user.email "your.email@example.com"
```

### 问题 2：推送被拒绝（需要先拉取）

```bash
git pull origin main --allow-unrelated-histories
git push -u origin main
```

### 问题 3：需要身份验证

GitHub 现在使用 Personal Access Token：
1. GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Generate new token
3. 选择权限：`repo`
4. 复制 token
5. 推送时使用 token 作为密码

### 问题 4：查看当前状态

```bash
git status          # 查看未提交的更改
git log             # 查看提交历史
git remote -v       # 查看远程仓库
```

---

## 更新已存在的仓库

如果仓库已经存在，只需要：

```bash
git add .
git commit -m "Update: description of changes"
git push
```

---

## 推荐的文件结构

确保 `.gitignore` 文件已创建，排除编译文件：
- `*.class` (Java 编译文件)
- `out/` (输出目录)
- `*.exe` (可执行文件)

这些文件不应该上传到 GitHub。
