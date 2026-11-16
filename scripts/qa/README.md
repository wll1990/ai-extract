# QA harness

黑盒验收 runner：权限矩阵 / 黄金链路 / 回归集 / UI 编排。

```bash
npm run qa:setup     # 一次性：生成 .qa.env、建 QA 租户、验证清理
npm run qa:quick     # 改完代码：lint + tsc + 架构检查 + 权限矩阵
npm run qa:release   # 发版前：权限 + 黄金 + 回归 + UI（默认不跑 LLM）
npm run qa:nightly   # 夜间：release + 真实 LLM 链路
```

完整说明、QA 租户信息、已知问题和安全规则见
[docs/67-黑盒测试使用手册.md](../../docs/67-黑盒测试使用手册.md)。

## 目录

```
run.mjs                 # runner
cases/permissions.json  # 角色×接口期望矩阵
cases/golden/*.json     # 黄金链路
cases/regression/*.json # 回归集（必须带 sourceBug）
seed/qa-seed.sql        # QA 租户（幂等）
seed/qa-cleanup.sql     # 按 runId 清理
release-gate.sh         # 发版门禁入口
```

## 安全

- 默认只读、默认不跑 LLM；写操作需 `QA_ALLOW_WRITE=1`，LLM 需 `QA_ALLOW_LLM=1`。
- 只写 QA 租户，对象名带 `QA-<runId>-`，清理按 runId。
- DB 断言通过 `ssh mindforge psql` 执行，本地不连生产库。
- `.qa.env`、`reports/qa/` 已 gitignore。
