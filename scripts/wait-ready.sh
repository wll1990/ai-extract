#!/bin/bash
# 等待 HTTP 服务就绪 — 轮询直到返回 2xx 或超时
# 用法: wait-ready.sh <label> <url> [timeout_sec]
# 就绪返回 0，超时返回 1
LABEL="${1:?用法: wait-ready.sh <label> <url> [timeout_sec]}"
URL="${2:?缺少 url}"
TIMEOUT="${3:-60}"

elapsed=0
while [ "$elapsed" -lt "$TIMEOUT" ]; do
  if curl -sf -o /dev/null "$URL" 2>/dev/null; then
    echo "  ✓ $LABEL ready"
    exit 0
  fi
  sleep 3
  elapsed=$((elapsed + 3))
done
echo "  ✗ $LABEL 未在 ${TIMEOUT}s 内就绪（$URL）"
exit 1
