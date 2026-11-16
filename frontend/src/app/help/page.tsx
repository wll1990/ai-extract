'use client';

import React, { useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { FAQ, GLOSSARY, MANUAL, SPEC, searchHelp } from '@/lib/help/content';
import { track } from '@/lib/analytics';

type DocKey = 'manual' | 'spec' | 'faq' | 'glossary';

const CHAPTER_BY_SECTION: Record<string, string> = {
  upload: 'source',
  interview: 'source',
  materials: 'pipeline',
  audit: 'pipeline',
  chat: 'use',
  review: 'review',
  'knowledge-update': 'update',
  'knowledge-base': 'assets',
  users: 'rollout',
  training: 'rollout',
  share: 'share',
};

export default function HelpPage() {
  const router = useRouter();
  const [doc, setDoc] = useState<DocKey>('manual');
  const [keyword, setKeyword] = useState('');
  const [showAllFaq, setShowAllFaq] = useState(false);

  useEffect(() => {
    track('help_view', { doc });
  }, [doc]);

  // 支持从侧栏「帮助与学习」直达：/help?doc=spec、/help#faq
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const param = params.get('doc');
    const hash = window.location.hash.replace('#', '');
    const next: DocKey | null =
      param === 'manual' || param === 'spec' || param === 'faq' || param === 'glossary' ? param
        : hash === 'faq' || hash.startsWith('faq-') ? 'faq'
          : null;
    if (next) setDoc(next);
  }, []);

  const results = useMemo(() => searchHelp(keyword), [keyword]);
  const sections = doc === 'manual' ? MANUAL.sections : doc === 'spec' ? SPEC.sections : [];
  const visibleFaq = showAllFaq ? FAQ : FAQ.slice(0, 4);

  const navBtn = (key: DocKey, label: string) => (
    <button
      type="button"
      key={key}
      onClick={() => { setDoc(key); setKeyword(''); }}
      className={`block w-full rounded px-3 py-2 text-left text-sm ${
        doc === key ? 'bg-primary-light text-primary font-medium' : 'text-muted-foreground hover:bg-surface'
      }`}
    >
      {label}
    </button>
  );

  return (
    <div className="min-h-screen bg-surface px-6 py-8 print:bg-white print:px-0 print:py-0">
      <div className="mx-auto flex max-w-[1080px] gap-8 print:block">
        <aside className="w-[220px] shrink-0 print:hidden">
          <button type="button" onClick={() => router.back()}
            className="mb-4 text-sm text-muted-foreground hover:text-foreground">← 返回</button>
          <h1 className="mb-4 text-lg font-bold text-foreground">使用帮助</h1>
          <input
            value={keyword}
            onChange={e => setKeyword(e.target.value)}
            placeholder="搜索：上传格式、发布条件…"
            className="mb-3 w-full rounded-lg border border-border bg-surface-2 px-3 py-2 text-sm outline-none"
          />
          <nav className="space-y-0.5">
            {navBtn('manual', `操作手册 · ${MANUAL.sections.length} 节`)}
            {navBtn('spec', `系统说明书 · ${SPEC.sections.length} 章`)}
            {navBtn('faq', '常见问题')}
            {navBtn('glossary', '术语表')}
          </nav>
          <button type="button" onClick={() => window.print()}
            className="mt-4 w-full rounded border border-border px-3 py-2 text-xs text-muted-foreground">
            打印 / 导出 PDF
          </button>
        </aside>

        <main className="min-w-0 flex-1">
          {keyword && (
            <div className="mb-6 rounded-xl border border-border bg-surface-2 p-4">
              <p className="mb-2 text-xs text-muted-foreground">找到 {results.length} 条</p>
              {results.length === 0 && (
                <p className="text-sm text-muted-foreground">换个说法试试，比如「格式」「发布」「缺口」。</p>
              )}
              <ul className="space-y-2">
                {results.map(r => (
                  <li key={`${r.anchor}-${r.title}`}>
                    <a href={`#${r.anchor}`} className="text-sm font-medium text-primary hover:underline"
                      onClick={() => setKeyword('')}>{r.title}</a>
                    <p className="line-clamp-2 text-xs text-muted-foreground">{r.text}</p>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {(doc === 'manual' || doc === 'spec') && (
            <article className="space-y-8">
              {(doc === 'manual' ? MANUAL : SPEC).sections.map(s => (
                <section key={s.id} id={s.id} className="scroll-mt-6">
                  <h2 className="mb-1 text-lg font-semibold text-foreground">{s.title}</h2>
                  {s.summary && <p className="mb-3 text-sm text-muted-foreground">{s.summary}</p>}
                  {s.paragraphs?.map(p => (
                    <p key={p} className="mb-2 text-sm leading-relaxed text-foreground">{p}</p>
                  ))}
                  {s.steps && (
                    <ol className="mb-3 space-y-2 text-sm text-foreground">
                      {s.steps.map((st, i) => (
                        <li key={st.text} className="flex gap-2">
                          <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary/10 text-[11px] font-medium text-primary">
                            {i + 1}
                          </span>
                          <div>
                            <p>{st.text}</p>
                            {st.shot && (
                              <img src={`/help/shots/${st.shot}`} alt={st.text}
                                className="mt-2 w-full rounded-lg border border-border"
                                onError={e => { (e.currentTarget as HTMLImageElement).style.display = 'none'; }} />
                            )}
                          </div>
                        </li>
                      ))}
                    </ol>
                  )}
                  {s.bullets && (
                    <ul className="mb-3 space-y-1 text-sm text-foreground">
                      {s.bullets.map(b => (
                        <li key={b} className="flex gap-2"><span className="text-primary">•</span><span>{b}</span></li>
                      ))}
                    </ul>
                  )}
                  {s.note && (
                    <p className="rounded-lg bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-700">
                      {s.note}
                    </p>
                  )}
                  {CHAPTER_BY_SECTION[s.id] && (
                    <button type="button"
                      onClick={() => router.push(`/admin?tour=${CHAPTER_BY_SECTION[s.id]}`)}
                      className="mt-3 text-xs text-primary hover:underline">
                      在系统里演示这一节 →
                    </button>
                  )}
                </section>
              ))}
            </article>
          )}

          {doc === 'faq' && (
            <article className="space-y-6">
              {visibleFaq.map(g => (
                <section key={g.id} id={`faq-${g.id}`}>
                  <h2 className="mb-2 text-base font-semibold text-foreground">{g.title}</h2>
                  <div className="space-y-2">
                    {g.items.map(it => (
                      <details key={it.q} className="rounded-lg border border-border bg-surface-2 px-4 py-3">
                        <summary className="cursor-pointer text-sm font-medium text-foreground">{it.q}</summary>
                        <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{it.a}</p>
                      </details>
                    ))}
                  </div>
                </section>
              ))}
              {!showAllFaq && (
                <button type="button" onClick={() => setShowAllFaq(true)}
                  className="rounded border border-border px-4 py-2 text-sm text-foreground">
                  展开全部 {FAQ.length} 组问题
                </button>
              )}
            </article>
          )}

          {doc === 'glossary' && (
            <article id="glossary" className="space-y-3">
              {GLOSSARY.map(t => (
                <div key={t.term} className="rounded-lg border border-border bg-surface-2 px-4 py-3">
                  <p className="text-sm font-semibold text-foreground">{t.term}</p>
                  <p className="mt-1 text-sm text-muted-foreground">{t.def}</p>
                </div>
              ))}
            </article>
          )}
        </main>
      </div>
    </div>
  );
}
