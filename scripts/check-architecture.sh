#!/bin/bash
# 阿里巴巴 Java 开发手册 — 分层架构检查
# 参考：https://github.com/alibaba/p3c
#
# ERROR = 零容忍，修复后再提交
# WARN  = 历史债务，不得新增

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/backend/src/main/java/com/aiextract"
ERRORS=0
WARNS=0

echo "=== 阿里巴巴分层架构检查 ==="
echo "  ERROR = 零容忍  |  WARN = 历史债务，不得新增"
echo ""

# ═══════════════════ ERROR 级 ═══════════════════

# E1：Controller 不得有 JdbcTemplate / raw SQL
echo -n "[E1] Controller 无数据访问代码 ... "
HITS=$(grep -rnE "JdbcTemplate|PreparedStatement|DataSource" "$SRC/controller/" 2>/dev/null || true)
if [ -n "$HITS" ]; then
  echo "❌"
  echo "$HITS"
  echo "   → 阿里巴巴规范：Controller 只做路由，数据操作走 Service"
  ERRORS=$((ERRORS + 1))
else
  echo "✅"
fi

# E2：Scheduler(*Scheduler.java) 不得有 JdbcTemplate / raw SQL
echo -n "[E2] Scheduler 无数据访问代码 ... "
HITS=$(find "$SRC" -name "*Scheduler.java" -exec grep -ln "JdbcTemplate" {} \; 2>/dev/null || true)
if [ -n "$HITS" ]; then
  echo "❌"
  echo "$HITS"
  echo "   → 阿里巴巴规范：定时任务只做调度，数据操作走 Service"
  ERRORS=$((ERRORS + 1))
else
  echo "✅"
fi

# E3：Controller 不得有 @Transactional
echo -n "[E3] Controller 无 @Transactional ... "
HITS=$(grep -rn "@Transactional" "$SRC/controller/" 2>/dev/null || true)
if [ -n "$HITS" ]; then
  echo "❌"
  echo "$HITS"
  echo "   → 阿里巴巴规范：事务边界在 Service"
  ERRORS=$((ERRORS + 1))
else
  echo "✅"
fi

# E4：Repository 不得有写事务 @Transactional（JPA 自带只读，无需声明）
echo -n "[E4] Repository 无 @Transactional ... "
HITS=$(grep -rn "@Transactional" "$SRC/repository/" 2>/dev/null || true)
if [ -n "$HITS" ]; then
  echo "❌"
  echo "$HITS"
  echo "   → JPA Repository 默认自带事务，无需额外声明"
  ERRORS=$((ERRORS + 1))
else
  echo "✅"
fi

# E5：Service 无 for-循环内调 repository（N+1 风险）
echo -n "[E5] Service 无 for-循环调 repository ... "
HITS=$(grep -rn -A 2 "for\s*(" "$SRC/service/" --include="*.java" 2>/dev/null | grep "repository\." || true)
if [ -n "$HITS" ]; then
  echo "❌"
  echo "$HITS"
  echo "   → 阿里巴巴规范：禁止循环内调 DAO，用 batch/IN 查询替代"
  ERRORS=$((ERRORS + 1))
else
  echo "✅"
fi

# ═══════════════════ WARN 级 ═══════════════════

# W1：Controller 注入 Repository — 应逐步迁移到 Service
echo -n "[W1] Controller 不注入 Repository ... "
HITS=$(grep -rnE "private\s+final\s+\w+Repository" "$SRC/controller/" 2>/dev/null || true)
if [ -n "$HITS" ]; then
  COUNT=$(echo "$HITS" | wc -l | tr -d ' ')
  echo "⚠️  $COUNT 处（历史债务）"
  echo "   → 建议：简单查询可保留，聚合/写入逻辑必须下沉 Service"
  WARNS=$((WARNS + 1))
else
  echo "✅"
fi

echo ""
if [ $ERRORS -eq 0 ] && [ $WARNS -eq 0 ]; then
  echo "✅ 全部通过"
  exit 0
elif [ $ERRORS -eq 0 ]; then
  echo "⚠️  $WARNS 项警告（历史债务，不得新增）"
  exit 0
else
  echo "❌ $ERRORS 项错误 + $WARNS 项警告 — 修复 ERROR 后再提交"
  exit 1
fi
