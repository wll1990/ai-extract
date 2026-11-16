'use client';

import { CustomerSignal, ReviewPoint, ReviewReport, NextStep } from '@/lib/api/sales';

type Token = { text: string; kind: 'same' | 'add' | 'del' };

const MAX_DIFF_CHARS = 2000;

/** 字符级 LCS；中文按字、英文按字符，避免中文被整段标成新增/删除。 */
function diffChars(before: string, after: string): Token[] {
  const a = Array.from((before || '').slice(0, MAX_DIFF_CHARS));
  const b = Array.from((after || '').slice(0, MAX_DIFF_CHARS));
  const n = a.length;
  const m = b.length;
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i -= 1) {
    for (let j = m - 1; j >= 0; j -= 1) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }
  const out: Token[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({ text: a[i], kind: 'same' });
      i += 1;
      j += 1;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      out.push({ text: a[i], kind: 'del' });
      i += 1;
    } else {
      out.push({ text: b[j], kind: 'add' });
      j += 1;
    }
  }
  while (i < n) {
    out.push({ text: a[i], kind: 'del' });
    i += 1;
  }
  while (j < m) {
    out.push({ text: b[j], kind: 'add' });
    j += 1;
  }
  return out;
}

function DiffText({ before, after, side }: { before: string; after: string; side: 'before' | 'after' }) {
  const tokens = diffChars(before, after).filter(
    t => t.kind === 'same' || (side === 'before' ? t.kind === 'del' : t.kind === 'add'),
  );
  return (
    <p className="whitespace-pre-wrap text-sm text-gray-800">
      {tokens.map((t, idx) => (
        <span
          key={idx}
          className={
            t.kind === 'add'
              ? 'bg-green-100 text-green-800'
              : t.kind === 'del'
                ? 'bg-red-100 text-red-700 line-through'
                : undefined
          }
        >
          {t.text}
        </span>
      ))}
      {!tokens.length && <span className="text-gray-400">（空）</span>}
    </p>
  );
}

function PointColumns({
  title,
  before,
  after,
}: {
  title: string;
  before: ReviewPoint[];
  after: ReviewPoint[];
}) {
  const max = Math.max(before.length, after.length);
  return (
    <section className="rounded border border-gray-200 p-3">
      <h4 className="mb-2 text-sm font-medium text-gray-900">
        {title} <span className="text-xs font-normal text-gray-500">{before.length} → {after.length} 条</span>
      </h4>
      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        <div>
          <div className="mb-1 text-xs text-gray-500">当前查看</div>
          <ul className="space-y-2">
            {Array.from({ length: max }).map((_, i) => (
              <li key={i} className="rounded bg-gray-50 p-2">
                {before[i] ? (
                  <DiffText before={before[i].point} after={after[i]?.point ?? ''} side="before" />
                ) : (
                  <span className="text-xs text-gray-400">—</span>
                )}
              </li>
            ))}
          </ul>
        </div>
        <div>
          <div className="mb-1 text-xs text-gray-500">下一版</div>
          <ul className="space-y-2">
            {Array.from({ length: max }).map((_, i) => (
              <li key={i} className="rounded bg-gray-50 p-2">
                {after[i] ? (
                  <DiffText before={before[i]?.point ?? ''} after={after[i].point} side="after" />
                ) : (
                  <span className="text-xs text-gray-400">—</span>
                )}
              </li>
            ))}
          </ul>
        </div>
      </div>
    </section>
  );
}

function StepColumns({ before, after }: { before: NextStep[]; after: NextStep[] }) {
  const max = Math.max(before.length, after.length);
  return (
    <section className="rounded border border-gray-200 p-3">
      <h4 className="mb-2 text-sm font-medium text-gray-900">
        下一步 <span className="text-xs font-normal text-gray-500">{before.length} → {after.length} 条</span>
      </h4>
      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        <div>
          <div className="mb-1 text-xs text-gray-500">当前查看</div>
          {Array.from({ length: max }).map((_, i) => (
            <div key={i} className="mb-2 rounded bg-gray-50 p-2">
              {before[i] ? (
                <DiffText before={before[i].title} after={after[i]?.title ?? ''} side="before" />
              ) : (
                <span className="text-xs text-gray-400">—</span>
              )}
            </div>
          ))}
        </div>
        <div>
          <div className="mb-1 text-xs text-gray-500">下一版</div>
          {Array.from({ length: max }).map((_, i) => (
            <div key={i} className="mb-2 rounded bg-gray-50 p-2">
              {after[i] ? (
                <DiffText before={before[i]?.title ?? ''} after={after[i].title} side="after" />
              ) : (
                <span className="text-xs text-gray-400">—</span>
              )}
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function SignalTable({ before, after }: { before: CustomerSignal[]; after: CustomerSignal[] }) {
  const fields = Array.from(new Set([...before, ...after].map(s => s.field)));
  return (
    <section className="rounded border border-gray-200 p-3">
      <h4 className="mb-2 text-sm font-medium text-gray-900">客户信号</h4>
      <div className="space-y-1 text-sm">
        {fields.map(field => {
          const b = before.find(s => s.field === field);
          const a = after.find(s => s.field === field);
          const changed = (b?.value ?? null) !== (a?.value ?? null);
          return (
            <div key={field} className="grid grid-cols-3 gap-2 border-b border-gray-100 py-1">
              <span className="text-gray-500">{field}</span>
              <span className="text-gray-700">{b?.value ?? '未提及'}</span>
              <span className={changed ? 'text-amber-700' : 'text-gray-700'}>
                {a?.value ?? '未提及'}
                {changed && <span className="ml-1 text-xs">有变化</span>}
              </span>
            </div>
          );
        })}
        {!fields.length && <p className="text-xs text-gray-400">两版都没有客户信号。</p>}
      </div>
    </section>
  );
}

export function ReportVersionDiff({
  current,
  previous,
}: {
  current: ReviewReport;
  previous: ReviewReport;
}) {
  const c = current.payload;
  const p = previous.payload;
  if (!c || !p) {
    return (
      <div className="rounded border border-gray-200 p-3 text-sm text-gray-500">
        <h3 className="mb-1 text-sm font-medium text-gray-900">
          版本对比 v{previous.version} → v{current.version}
        </h3>
        对比版本内容缺失（v{current.version} 状态：{current.status}），无法展示差异。
      </div>
    );
  }
  return (
    <div className="space-y-3">
      <div className="rounded border border-blue-100 bg-blue-50 p-3">
        <h3 className="text-sm font-medium text-blue-900">
          版本对比 v{previous.version} → v{current.version}
        </h3>
        <p className="mt-1 text-xs text-blue-800">
          AI 每次会改写措辞，词级变化不代表结论改变；这里只展示文本差异，不推断哪条是新增或删除。
        </p>
        <div className="mt-2 grid grid-cols-1 gap-2 md:grid-cols-2">
          <div>
            <div className="mb-1 text-xs text-gray-500">v{previous.version}</div>
            <DiffText before={p.summary} after={c.summary} side="before" />
          </div>
          <div>
            <div className="mb-1 text-xs text-gray-500">v{current.version}</div>
            <DiffText before={p.summary} after={c.summary} side="after" />
          </div>
        </div>
      </div>
      <PointColumns title="做得好的" before={p.strengths} after={c.strengths} />
      <PointColumns title="不足" before={p.weaknesses} after={c.weaknesses} />
      <PointColumns title="风险" before={p.risks} after={c.risks} />
      <SignalTable before={p.customerSignals} after={c.customerSignals} />
      <StepColumns before={p.nextSteps} after={c.nextSteps} />
    </div>
  );
}
