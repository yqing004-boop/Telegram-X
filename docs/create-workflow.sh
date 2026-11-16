#!/bin/bash
# 一键创建 GitHub Actions workflow 并触发编译
# 用法：在 GitHub Codespaces 或本地执行 bash docs/create-workflow.sh
set -e

REPO="yqing004-boop/Telegram-X"
BRANCH="main"
FILE_PATH=".github/workflows/build-chinese.yml"
SOURCE="docs/build-chinese.yml"

# 检查 gh CLI
if ! command -v gh &>/dev/null; then
  echo "请先安装 GitHub CLI: https://cli.github.com/"
  exit 1
fi

# 检查登录
if ! gh auth status &>/dev/null; then
  echo "请先登录: gh auth login"
  exit 1
fi

echo "创建 workflow 文件: $FILE_PATH"

# 读取文件内容并 base64 编码
B64=$(base64 -w0 "$SOURCE")

# 用 GitHub API 创建文件
gh api -X PUT "repos/$REPO/contents/$FILE_PATH" \
  -f message="ci: GitHub Actions cloud build arm64 debug APK" \
  -f content="$B64" \
  -f branch="$BRANCH" \
  --jq '.content.html_url' || {
    echo "创建失败，可能文件已存在。如果需要更新，请先删除再重试。"
    exit 1
  }

echo ""
echo "✅ Workflow 创建成功！"
echo "GitHub Actions 将自动开始编译。"
echo "查看编译进度: https://github.com/$REPO/actions"
echo "编译完成后下载 APK: https://github.com/$REPO/actions (找到最新的 Build 任务)"
