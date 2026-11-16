'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { ArrowLeft, ArrowRight, Sparkle } from '@phosphor-icons/react';
import { PortalHeader } from '@/components/portal/PortalHeader';
import { Reveal } from '@/components/portal/Reveal';
import { track } from '@/lib/analytics';
import { getToken } from '@/lib/storage';

/**
 * 内容承接第一篇：讲方法，不卖产品。
 * 内容对应系统里真实存在的六章报告与经验颗粒字段，不是泛泛而谈。
 */
export default function NotePage() {
  const router = useRouter();
  const [loggedIn, setLoggedIn] = useState(false);

  useEffect(() => {
    setLoggedIn(Boolean(getToken()));
    track('note_view', { slug: 'interview-to-grain' });
  }, []);

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
            <span className="eyebrow"><Sparkle weight="fill" />方法笔记 · 01</span>
            <h1>我们把一次销冠访谈，拆成了什么</h1>
            <p className="note-meta">写在试点期 · 内容来自系统里真实在用的访谈结构</p>
          </Reveal>

          <Reveal className="note-body">
            <p>
              大部分企业的「经验传承」是这么做的：让销冠写一份 SOP，或者录一段培训视频。
              半年后打开看，SOP 里全是「要建立信任」「要挖掘需求」这类正确但没用的句子，
              视频没人看完。
            </p>
            <p>
              问题不在销冠不愿意分享，在于他讲不清楚自己是怎么判断的。
              决策发生在几秒钟里，事后复述时，那些真正起作用的东西已经被忘掉了。
            </p>

            <h2>一、先问事件，不问方法论</h2>
            <p>
              所以我们不问「你有哪些销售技巧」，而是问「上个月那个卡住的单子，第一天你做了什么」。
              具体到时间、对象、原话，才能逼出细节。
            </p>
            <p>
              访谈按四个阶段推进：还原事件 → 追问判断依据 → 找反例与边界 → 整理成结构化报告。
              工程师管这叫 elicitation，我们叫它「把话说透」。
            </p>

            <h2>二、一份六章报告，每一条都要能回到原话</h2>
            <p>访谈结束后产出的不是摘要，是六章结构：</p>
            <ol>
              <li><b>案例</b>——这一单发生了什么，谁在什么时候说了什么。</li>
              <li><b>步骤</b>——他实际的动作顺序，不是应该的顺序。</li>
              <li><b>决策</b>——每个岔路口他为什么选这边。</li>
              <li><b>心法</b>——他自己总结的规律。</li>
              <li><b>边界</b>——什么情况下这套不成立。</li>
              <li><b>清单</b>——可以直接拿去用的动作项。</li>
            </ol>
            <p>
              关键约束在第 1 章：报告里的每一条判断，都要能对回访谈录音里的某一句原话。
              对不上的，要么删掉，要么标成「推断」。
            </p>

            <h2>三、从报告到颗粒：一条经验长什么样</h2>
            <p>报告是给人读的，颗粒是给检索用的。一条经验颗粒拆成五个字段：</p>
            <table>
              <thead>
                <tr><th>字段</th><th>放什么</th><th>为什么需要</th></tr>
              </thead>
              <tbody>
                <tr><td>场景</td><td>客户说「再考虑考虑」</td><td>决定什么问题能命中它</td></tr>
                <tr><td>专家思考</td><td>他当时的判断和依据</td><td>回答的「为什么」出自这里</td></tr>
                <tr><td>标准话术</td><td>他实际说的话</td><td>可以被直接复用</td></tr>
                <tr><td>常见错误</td><td>新人通常怎么答错</td><td>避免把错误当成经验</td></tr>
                <tr><td>适用条件</td><td>什么情况下不适用</td><td>决定它不该被引用的时候</td></tr>
              </tbody>
            </table>
            <p>
              「适用条件」是最容易被跳过、也最重要的一栏。
              没有它，模型会把一个特定场景下的做法，当成通用规律到处套。
            </p>

            <h2>四、为什么最后一定要本人审核</h2>
            <p>
              自动萃取会犯错：把客套话当成判断依据，把巧合当成因果。
              所以每一条经验发布前，都要由专家本人确认三件事——依据对不对、语气像不像他、哪些范围不能回答。
            </p>
            <p>
              最后一件最容易被忽略。一个销冠的能力不只是「知道什么该说」，
              还包括「知道什么不该由自己来说」。这条边界必须写进系统，否则分身迟早会在不该开口的地方开口。
            </p>

            <h2>写在最后</h2>
            <p>
              这套结构现在就在系统里跑：访谈、六章报告、经验颗粒、审核发布。
              你可以拿一个自己团队的销冠试一遍，看萃取出来的东西对不对得上他本人。
            </p>
          </Reveal>

          <Reveal className="note-footer">
            <p>想看你自己的场景被拆成什么样？</p>
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
