@echo off
REM GitHub 上传脚本
REM 使用方法：双击运行或在命令行执行

echo ========================================
echo   GitHub 上传脚本
echo ========================================
echo.

cd /d "%~dp0"

REM 检查是否已初始化 Git
if not exist .git (
    echo [1/5] 初始化 Git 仓库...
    git init
    echo.
) else (
    echo [1/5] Git 仓库已存在
    echo.
)

REM 检查用户配置
git config user.name >nul 2>&1
if errorlevel 1 (
    echo 警告：未配置 Git 用户信息
    echo 请运行以下命令：
    echo   git config --global user.name "Your Name"
    echo   git config --global user.email "your.email@example.com"
    echo.
    pause
    exit /b 1
)

REM 添加文件
echo [2/5] 添加文件到暂存区...
git add .
echo.

REM 提交更改
echo [3/5] 提交更改...
git commit -m "Update: Add error handling and network testing guide"
if errorlevel 1 (
    echo 警告：没有更改需要提交，或提交失败
    echo.
)

REM 检查远程仓库
echo [4/5] 检查远程仓库配置...
git remote -v >nul 2>&1
if errorlevel 1 (
    echo.
    echo ========================================
    echo   需要配置远程仓库
    echo ========================================
    echo.
    echo 请运行以下命令添加远程仓库：
    echo   git remote add origin https://github.com/你的用户名/仓库名.git
    echo.
    echo 或者如果已经创建了 GitHub 仓库，直接运行：
    echo   git remote add origin https://github.com/USERNAME/REPO.git
    echo.
    pause
    exit /b 0
)

REM 显示远程仓库信息
git remote -v
echo.

REM 推送到 GitHub
echo [5/5] 推送到 GitHub...
echo 提示：如果是第一次推送，可能需要身份验证
echo.
git push -u origin main
if errorlevel 1 (
    git push -u origin master
    if errorlevel 1 (
        echo.
        echo 推送失败，请检查：
        echo 1. 网络连接
        echo 2. GitHub 身份验证（Personal Access Token）
        echo 3. 远程仓库地址是否正确
        echo.
    )
)

echo.
echo ========================================
echo   完成！
echo ========================================
echo.
pause
