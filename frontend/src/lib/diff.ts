/** 行级 diff 结果：same=未变，del=旧版有、add=新版有 */
export type DiffLine = { type: 'same' | 'del' | 'add'; text: string };

/**
 * LCS 行级差异。
 *
 * <p>超大文本（a.length * b.length > 200000）返回 null，调用方降级为并排原文，
 * 避免 O(n*m) 内存把页面拖死。</p>
 */
export function diffLines(before: string, after: string): DiffLine[] | null {
  const a = (before || '').split('\n');
  const b = (after || '').split('\n');
  if (a.length * b.length > 200_000) return null;

  const n = a.length;
  const m = b.length;
  const dp: Uint32Array[] = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }

  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({ type: 'same', text: a[i] });
      i++;
      j++;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      out.push({ type: 'del', text: a[i] });
      i++;
    } else {
      out.push({ type: 'add', text: b[j] });
      j++;
    }
  }
  while (i < n) out.push({ type: 'del', text: a[i++] });
  while (j < m) out.push({ type: 'add', text: b[j++] });
  return out;
}
