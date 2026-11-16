'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { ArrowLeft, ArrowRight, Check, Sparkle, Warning } from '@phosphor-icons/react';
import { PortalHeader } from '@/components/portal/PortalHeader';
import { Reveal } from '@/components/portal/Reveal';
import { EVAL_ROWS, EVAL_SUMMARY } from '@/lib/portal/evaluation-data';
import { track } from '@/lib/analytics';
import { getToken } from '@/lib/storage';

/**
 * 公开自评测页。
 *
 * 数字来自 [evaluation-data.ts] —— 真实链路跑出来的结果，包括没通过的那一条。
 * 页面刻意不隐藏失败项：一个只贴成功率的评测，本身就不值得信。
 */
export default function EvaluationPage() {
  const router = useRouter();
  const [loggedIn, setLoggedIn] = useState(false);
  const [showAll, setShowAll] = useState(false);

  useEffect(() => {
    setLoggedIn(Boolean(getToken()));
    track('evaluation_view', {});
  }, []);

  const rows = showAll ? EVAL_ROWS : EVAL_ROWS.slice(0, 8);
  const inScopeMiss = EVAL_ROWS.filter(r => r.inScope && !r.passed);

  return (
    <div className="public-page">
      <div className="top-ribbon">REAL EXPERIENCE × AI · 把真人经验变成可对话的分身</div>
      <PortalHeader loggedIn={loggedIn} />

      <main>
        <article className="note-page">
          <button type="button" className="back-link" onClick={() => router.push('/portal')}>
            <ArrowLeft /> 回到首页
          </button>

          <Reveal>
            <span className="eyebrow"><Sparkle weight="fill" />自评测 · {EVAL_SUMMARY.generatedAt}</span>
            <h1>我们怎么测自己</h1>
            <p className="note-meta">
              24 个问题，跑真实链路，结果全部公开——包括没通过的那一条。
            </p>
          </Reveal>

          <Reveal className="eval-summary">
            <div>
              <strong>{EVAL_SUMMARY.inScopePass}/{EVAL_SUMMARY.inScopeTotal}</strong>
              <span>该答的问题，给出了带依据的回答</span>
            </div>
            <div>
              <strong>{EVAL_SUMMARY.outScopePass}/{EVAL_SUMMARY.outScopeTotal}</strong>
              <span>不该答的问题，正确拒答</span>
            </div>
            <div>
              <strong>{(EVAL_SUMMARY.avgMs / 1000).toFixed(1)}s</strong>
              <span>单次回答平均耗时</span>
            </div>
          </Reveal>

          <Reveal className="note-body">
            <h2>测什么，怎么判</h2>
            <p>
              准备两组问题：<b>12 个应该答的</b>（对应演示分身的 12 条经验），
              <b>12 个不该答的</b>（报销、招聘、行政这类和销售经验无关的问题）。
              每个问题走完整的真实链路：检索 → 生成 → 返回引用。
            </p>
            <p>判定规则只有两条，事先写死，跑完不改：</p>
            <ol>
              <li>
                <b>该答的</b>——回答里召回到经验颗粒（引用数 &gt; 0）<b>且</b>没有使用拒答话术，算通过。
              </li>
              <li>
                <b>不该答的</b>——回答里出现明确的拒答表述（「不在我擅长的范围」「不敢乱给建议」等），算通过。
              </li>
            </ol>

            <h2>结果</h2>
            <p>
              不该答的 12 个问题，全部正确拒答——它没有因为「都是中文提问」就硬答。
              该答的 12 个问题里，<b>11 个给出了带引用的回答</b>，1 个没通过。
            </p>

            {inScopeMiss.map(item => (
              <div className="eval-issue" key={item.question}>
                <Warning />
                <div>
                  <b>已知问题：明明检索到依据，却拒答了</b>
                  <p>
                    「{item.question}」召回了 {item.grainCount} 条经验颗粒，但模型仍然回了
                    「没有成熟的应对思路」。这是<b>过度拒答</b>——安全方向没错，但损失了可用性。
                    我们把它留在页面上，而不是修完再贴。
                  </p>
                  <span className="eval-quote">模型实际回答：{item.answerHead}…</span>
                </div>
              </div>
            ))}

            <h2>逐条明细</h2>
            <table className="eval-table">
              <thead>
                <tr>
                  <th>问题</th>
                  <th>类型</th>
                  <th>召回</th>
                  <th>判定</th>
                  <th>用时</th>
                </tr>
              </thead>
              <tbody>
                {rows.map(row => (
                  <tr key={row.question} className={row.passed ? '' : 'failed'}>
                    <td>
                      <details>
                        <summary>{row.question}</summary>
                        <span className="eval-quote">{row.answerHead}…</span>
                      </details>
                    </td>
                    <td>{row.inScope ? '该答' : '不该答'}</td>
                    <td>{row.grainCount} 条</td>
                    <td>
                      {row.passed ? <span className="eval-pass"><Check />通过</span>
                        : <span className="eval-fail"><Warning />未通过</span>}
                    </td>
                    <td>{(row.ms / 1000).toFixed(1)}s</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!showAll && (
              <button type="button" className="button button-secondary" onClick={() => setShowAll(true)}>
                展开全部 {EVAL_ROWS.length} 条
              </button>
            )}

            <h2>这份评测不能说明什么</h2>
            <p>
              语料只有 12 条经验、24 个问题，测的是演示分身，不是你们团队的真实场景。
              它能说明系统行为是通顺的——该答的给依据、不该答的会拒答、有错也照实写出来；
              它<b>不能</b>说明你的行业里效果如何。那件事只能拿你自己的客户来跑。
            </p>
          </Reveal>

          <Reveal className="note-footer">
            <p>想在你的场景上测一遍？</p>
            <div className="hero-actions">
              <button type="button" className="button button-primary" onClick={() => router.push('/portal#contact')}>
                预约 20 分钟 <ArrowRight />
              </button>
              <button type="button" className="button button-secondary" onClick={() => router.push('/portal/product')}>
                看产品怎么运作
              </button>
            </div>
          </Reveal>
        </article>
      </main>

      <footer className="public-footer">
        <button className="brand" type="button" onClick={() => router.push('/portal')}>
          <span className="brand-mark"><Sparkle weight="fill" /></span>
          <span>MindForge</span>
        </button>
        <p>真人经验驱动的 AI 分身网络</p>
        <button type="button" className="button button-primary" onClick={() => router.push('/portal#contact')}>
          预约一次演示 <ArrowRight />
        </button>
      </footer>
    </div>
  );
}
