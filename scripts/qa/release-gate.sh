#!/usr/bin/env bash
# 发版门禁：权限矩阵 + 黄金链路 + 回归集 + UI（+ Holdout）。
# 退出码：0 通过；1 内容失败；2 环境/清理阻断。
#
# 用法：bash scripts/qa/release-gate.sh
# 注意：门禁必须针对新构建产物；当前先作为发布前手动门禁，不要直接测旧线上。
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"
cd "$ROOT"
# 发版门禁必须覆盖真实聊天链路（建内容→分身聊天命中），默认开启 LLM 用例；
# 紧急情况下可用 QA_ALLOW_LLM=0 临时关闭（会跳过所有聊天黄金用例）。
export QA_ALLOW_LLM="${QA_ALLOW_LLM:-1}"
exec node scripts/qa/run.mjs --profile=release "$@"
