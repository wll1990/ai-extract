#!/usr/bin/env python3
"""
颗粒萃取质量审计工具 — 对比管线实际产出 vs Golden Set 预期产出。

用法:
    python3 scripts/grain-audit.py --material-id <uuid> --golden scripts/golden/夏宇.json \
        --api http://localhost:8080/api/v1 --token <jwt>

输出:
    - Precision / Recall / F1
    - 命中 / 漏提 / 多提 明细
    - 漏提项回溯到 chunk 级定位
"""

import argparse, json, sys, os, re
from urllib.request import Request, urlopen
from urllib.error import HTTPError


def fetch_audit_report(material_id: str, api_base: str, token: str) -> dict:
    """调 audit-report API 获取萃取审计数据"""
    url = f"{api_base}/admin/materials/{material_id}/audit-report"
    req = Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/json"})
    try:
        with urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read())
            if data.get("code") == 200:
                return data["data"]
            else:
                print(f"API 错误: {data.get('message', 'unknown')}", file=sys.stderr)
                sys.exit(1)
    except HTTPError as e:
        print(f"HTTP {e.code}: {e.reason}", file=sys.stderr)
        if e.code == 401:
            print("提示: 需要有效的 JWT token，请通过 --token 传入", file=sys.stderr)
        sys.exit(1)


def load_golden(golden_path: str) -> dict:
    """加载 Golden Set 标注文件"""
    with open(golden_path, "r", encoding="utf-8") as f:
        return json.load(f)


def extract_actual_grains(report: dict) -> list[str]:
    """从审计报告中提取实际产生的颗粒标签列表"""
    tags = []
    for g in report.get("finalGrains", []):
        tag = g.get("sceneTag", "")
        if tag:
            tags.append(tag)
    return tags


def extract_chunk_results(report: dict) -> list[dict]:
    """提取逐 chunk 结果"""
    return report.get("chunkDetail", [])


def soft_match(expected: str, actual: str) -> bool:
    """软匹配: 任一方的标签包含在另一方中，或共享至少 2 个连续汉字"""
    e, a = expected.strip(), actual.strip()
    if e == a:
        return True
    if e in a or a in e:
        return True
    # 2-gram 重合率检测
    def to_2grams(s: str):
        return {s[i:i+2] for i in range(len(s)-1)}
    eg = to_2grams(e)
    ag = to_2grams(a)
    if eg and ag:
        overlap = len(eg & ag) / min(len(eg), len(ag))
        return overlap > 0.5
    return False


def find_missed_chunks(missed_tags: list[str], chunk_detail: list[dict]) -> dict[str, list[dict]]:
    """对于漏提的颗粒，回溯到相关 chunk"""
    missed_map = {}
    for tag in missed_tags:
        related = []
        for ch in chunk_detail:
            preview = ch.get("textPreview", "")
            raw_items = ch.get("rawItems", [])
            # 检查 chunk 预览或提取结果中是否包含相关关键词
            if any(kw in preview for kw in tag):
                related.append(ch)
            # 也检查 rawItems
            for item in raw_items:
                desc = item.get("scene_description", "")
                if tag in desc:
                    related.append(ch)
                    break
        missed_map[tag] = related
    return missed_map


def print_report(golden: dict, report: dict):
    """打印格式化的萃取质量报告"""
    material_name = report.get("materialInfo", {}).get("fileName", "unknown")
    expected = golden.get("expectedGrains", [])
    actual = extract_actual_grains(report)
    summary = report.get("summary", {})

    # 匹配
    matched = []
    missed = []
    for exp in expected:
        found = False
        for act in actual:
            if soft_match(exp, act):
                matched.append((exp, act))
                found = True
                break
        if not found:
            missed.append(exp)
    extra = [act for act in actual if not any(soft_match(e, act) for e in expected)]

    # 计算
    tp = len(matched)
    fp = len(extra)
    fn = len(missed)
    precision = tp / (tp + fp) if (tp + fp) > 0 else 0
    recall = tp / (tp + fn) if (tp + fn) > 0 else 0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0

    # 输出
    print("=" * 60)
    print(f"萃取质量报告: {material_name}")
    print("=" * 60)
    print(f"总 chunk 数:   {summary.get('totalChunks', '?')}")
    print(f"非空 chunk:    {summary.get('nonEmptyChunks', '?')}")
    print(f"验证通过:      {summary.get('verifiedGrains', '?')}")
    print(f"验证拒绝:      {summary.get('rejectedGrains', '?')}")
    print(f"最终颗粒:      {summary.get('finalGrains', '?')}")
    print()
    print(f"Precision: {tp}/{tp+fp} = {precision:.1%}")
    print(f"Recall:    {tp}/{tp+fn} = {recall:.1%}")
    print(f"F1:        {f1:.2f}")
    print()

    if matched:
        print(f"✅ 命中 ({len(matched)}):")
        for exp, act in matched:
            prefix = "=" if exp == act else "≈"
            print(f"  {prefix} {exp}" + (f" → {act}" if exp != act else ""))
        print()

    if missed:
        print(f"❌ 漏提 ({len(missed)}):")
        chunk_detail = extract_chunk_results(report)
        missed_chunks = find_missed_chunks(missed, chunk_detail)
        for tag in missed:
            related = missed_chunks.get(tag, [])
            if related:
                for ch in related[:2]:  # 最多展示 2 个相关 chunk
                    ext_count = ch.get("extractedCount", 0)
                    preview = ch.get("textPreview", "")[:80]
                    print(f"  - {tag}")
                    print(f"    → chunk #{ch.get('chunkIndex')} ({ch.get('charCount')}字, "
                          f"提取{ext_count}条, {ch.get('promptType')})")
                    print(f"    → {preview}...")
            else:
                print(f"  - {tag} → 无关联 chunk（可能被清洗层过滤或分布过散）")
        print()

    if extra:
        print(f"➕ 多提 ({len(extra)}):")
        for tag in extra:
            print(f"  - {tag}")
        print()

    print(f"Golden Set 备注: {golden.get('notes', '无')}")
    print("=" * 60)


def main():
    parser = argparse.ArgumentParser(description="颗粒萃取质量审计工具")
    parser.add_argument("--material-id", required=True, help="素材 UUID")
    parser.add_argument("--golden", required=True, help="Golden Set JSON 文件路径")
    parser.add_argument("--api", default="http://localhost:8080/api/v1", help="API base URL")
    parser.add_argument("--token", default="", help="JWT token")
    args = parser.parse_args()

    golden = load_golden(args.golden)
    report = fetch_audit_report(args.material_id, args.api, args.token)

    if args.token:
        print_report(golden, report)
    else:
        # 无 token 时仅打印摘要（适合本地开发直接看）
        print("⚠ 未提供 --token，仅展示管线概要数据\n")
        summary = report.get("summary", {})
        info = report.get("materialInfo", {})
        print(f"素材: {info.get('fileName', '?')} ({info.get('status', '?')})")
        print(f"文本: {info.get('textLength', 0)} 字")
        print(f"Chunk 总数: {summary.get('totalChunks', 0)}")
        print(f"非空 chunk: {summary.get('nonEmptyChunks', 0)}")
        print(f"候选颗粒:   {summary.get('totalCandidates', 0)}")
        print(f"验证通过:   {summary.get('verifiedGrains', 0)}")
        print(f"验证拒绝:   {summary.get('rejectedGrains', 0)}")
        print(f"最终颗粒:   {summary.get('finalGrains', 0)}")
        print(f"淘汰记录:   {summary.get('droppedEntries', 0)}")
        print()
        print("finalGrains 标签:")
        for g in report.get("finalGrains", []):
            print(f"  - {g.get('sceneTag', '?'):15s} q={g.get('qualityScore', 0):.1f} d={g.get('difficultyLevel', '?')}")
        print()
        print("提示: 带 --token 可做 Golden Set vs 实际对比分析")


if __name__ == "__main__":
    main()
