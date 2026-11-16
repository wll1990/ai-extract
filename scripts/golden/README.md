# Golden set scripts

销售问答黄金集（50 题 / 10 场景）的生成、落库、校验与研发侧回归。

| 文件 | 作用 |
|---|---|
| `sales-qa-golden.items.json` | 唯一内容源，改题只改这里 |
| `build-sales-qa-golden.mjs` | 校验 + 生成 seed SQL；`--check` 防漂移 |
| `sales-qa-golden-seed.sql` | 生成物，幂等落库到评测中心（平台公共） |
| `sales-qa-golden-reset.sql` | **仅开发环境**，定向清理 |
| `sales-qa-golden-verify.sh` | DB + API 只读校验 |
| `run-sales-qa-golden.mjs` | 研发侧端到端回归（消耗 token） |

## 常用命令

```bash
node scripts/golden/build-sales-qa-golden.mjs --check
psql -d aiextract -v ON_ERROR_STOP=1 -f scripts/golden/sales-qa-golden-seed.sql
bash scripts/golden/sales-qa-golden-verify.sh
node scripts/golden/run-sales-qa-golden.mjs --dry-run
node scripts/golden/run-sales-qa-golden.mjs
```

完整设计、覆盖矩阵、评审清单见 `docs/65-销售问答黄金集-种子与验收.md`。

## 注意

- 黄金集是平台公共模板（`company_id IS NULL`），对所有租户可见；只放演示/合成内容。
- 运行脚本会真实调用 LLM 并写运行历史，默认不进 CI。
- `evaluation-reset.sql` 会清空黄金集；`demo-reset.sql` 不会。
- v1.0 为 AI 起草、待人工复核；修改已发布内容应升 v2，不要原地改。
