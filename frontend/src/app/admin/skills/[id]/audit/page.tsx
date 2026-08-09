'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import React, { useState, useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { getAuditDashboard, publishSkill, type AuditDashboard } from '@/lib/api/audit';
import { API_BASE } from '@/lib/api/client';
import { getToken } from '@/lib/storage';
import PracticeScenarioModal from '@/components/modals/PracticeScenarioModal';
import ProductDemoModal from '@/components/modals/ProductDemoModal';
import { ReportPreviewModal } from '@/components/modals/ReportPreviewModal';
import ExplicitStep from '@/components/admin/ExplicitStep';
import SkillStep from '@/components/admin/SkillStep';
import SceneStep from '@/components/admin/SceneStep';
import ProductStep from '@/components/admin/ProductStep';

type Step = 'explicit' | 'skill' | 'scene' | 'product';

const downloadReport = async (skillId: string, format: 'html') => {
  const url = `${API_BASE}/admin/skills/${skillId}/report?download=true`;
  const res = await fetch(url, { headers: { Authorization: `Bearer ${getToken()}` } });
  const blob = await res.blob();
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `extraction-report-${skillId}.html`;
  a.click();
  URL.revokeObjectURL(a.href);
};


const STEPS: { key: Step; label: string; subtitle: string; icon: string }[] = [
  { key: 'explicit', label: '知识显性化', subtitle: '解析·清洗·文本归一', icon: '📄' },
  { key: 'skill', label: '经验技能化', subtitle: 'AI提取·验证·模式发现', icon: '🔬' },
  { key: 'scene', label: '技能场景化', subtitle: '情境·FAQ·叙事重放', icon: '🎯' },
  { key: 'product', label: '场景产品化', subtitle: '报告·画像·发布', icon: '🚀' },
];


export default function AuditPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const [data, setData] = useState<AuditDashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [activeStep, setActiveStep] = useState<Step>('explicit');
  const [publishing, setPublishing] = useState(false);
  const [practiceGrain, setPracticeGrain] = useState<any>(null);
  const [sceneTag, setSceneTag] = useState('');
  const [grainIdx, setGrainIdx] = useState(0);

  // 当前场景标签下的所有颗粒
  const grains = (data?.scenarioGrains?.[sceneTag] || []) as any[];
  const safeIdx = Math.min(grainIdx, grains.length - 1);

  // 弹窗打开时 grainIdx 变化 → 同步 practiceGrain
  useEffect(() => {
    if (practiceGrain && grains[safeIdx] && grains[safeIdx].id !== practiceGrain.id) {
      setPracticeGrain(grains[safeIdx]);
    }
  }, [grainIdx, practiceGrain, grains, safeIdx]);
  const [showDemoModal, setShowDemoModal] = useState(false);
  const [showPreviewModal, setShowPreviewModal] = useState(false);
  const [productSubStep, setProductSubStep] = useState<'profile' | 'verify' | 'publish'>('profile');


  useEffect(() => {
    let mounted = true;
    let timerId: ReturnType<typeof setTimeout> | null = null;

    const poll = () => {
      if (!mounted) return;
      getAuditDashboard(id).then(d => {
        if (!mounted) return;
        setData(d);
        const allDone = d.materials.every((m: any) => m.status === 'extracted' || m.status === 'discarded');
        if (!allDone && mounted) timerId = setTimeout(poll, 5000);
      }).catch((e) => {
        console.error('加载审核面板失败', e);
      }).finally(() => {
        if (mounted) setLoading(false);
      });
    };

    poll();
    return () => {
      mounted = false;
      if (timerId) clearTimeout(timerId);
    };
  }, [id]);

  if (loading || !data) return <LoadingSpinner />;

  const getStepStatus = (step: Step): 'done' | 'current' | 'pending' => {
    const idx = STEPS.findIndex(s => s.key === step);
    const activeIdx = STEPS.findIndex(s => s.key === activeStep);
    if (idx < activeIdx) return 'done';
    if (idx === activeIdx) return 'current';
    return 'pending';
  };

  const publishChecks = [
    { label: '活跃颗粒 ≥ 10', pass: data.skillsSummary.activeGrains >= 10 },
    { label: '场景覆盖 ≥ 3', pass: data.skillsSummary.sceneTags.length >= 3 },
    { label: '画像已填写（姓名+性格+背景+领域）', pass: !!(
      data?.skill?.ownerName &&
      data.profile?.personality &&
      data.profile?.background &&
      data.profile?.knowledgeDomains && data.profile.knowledgeDomains !== '[]' && data.profile.knowledgeDomains !== ''
    ) },
  ];
  const allPassed = publishChecks.every(c => c.pass);

  const handlePublish = async () => {
    if (!allPassed) return;
    setPublishing(true);
    try {
      await publishSkill(id, 'publish');
      router.push('/admin/skills');
    } catch (e) {
      console.error('发布失败:', e);
      alert('发布失败，请重试');
    } finally {
      setPublishing(false);
    }
  };

  const handleDiscard = async () => {
    if (!confirm('确认废弃该分身？30天内可恢复。')) return;
    try {
      await publishSkill(id, 'discard');
      router.push('/admin/skills');
    } catch (e) {
      console.error('废弃失败:', e);
      alert('操作失败，请重试');
    }
  };

  return (
    <div className="max-w-6xl mx-auto p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <button onClick={() => router.back()} className="text-sm text-muted-foreground hover:text-foreground mb-1">← 返回</button>
          <h1 className="text-xl font-bold">审核与发布：{data.skill.displayName || data.skill.ownerName || '未命名'}</h1>
        </div>
        <div className="flex gap-3"></div>
      </div>

      {/* 步骤进度条 */}
      <div className="flex items-center gap-0 mb-8 bg-surface-2 rounded-lg border border-border p-4">
        {STEPS.map((step, i) => {
          const status = getStepStatus(step.key);
          return (
            <React.Fragment key={step.key}>
              <button onClick={() => setActiveStep(step.key)}
                className={`flex items-center gap-3 px-4 py-2 rounded-lg transition flex-1 ${
                  activeStep === step.key ? 'bg-primary-light ring-1 ring-primary' : 'hover:bg-primary-light'}`}>
                <span className={`w-8 h-8 rounded-full flex items-center justify-center text-sm font-bold shrink-0 ${
                  status === 'done' ? 'bg-success-bg text-success' :
                  status === 'current' ? 'bg-primary text-white' : 'bg-primary-light text-muted-foreground-2'}`}>
                  {status === 'done' ? '✓' : i + 1}
                </span>
                <div className="text-left">
                  <div className={`text-sm font-medium ${status === 'pending' ? 'text-muted-foreground-2' : 'text-foreground'}`}>{step.label}</div>
                  <div className="text-xs text-muted-foreground-2">{step.subtitle}</div>
                </div>
              </button>
              {i < STEPS.length - 1 && <span className="text-gray-300 shrink-0">→</span>}
            </React.Fragment>
          );
        })}
      </div>

      {/* 步骤内容 */}
      <div className="bg-surface-2 rounded-lg border border-border p-6">
        {/* ① 知识显性化 */}
        {activeStep === 'explicit' && data && <ExplicitStep data={data} />}

        {/* ② 经验技能化 — AI 裁决 + 核心心法 + 折叠详情 */}
        {activeStep === 'skill' && data && <SkillStep skillId={id} data={data} onPreviewReport={() => setShowPreviewModal(true)} onDownloadReport={downloadReport} />}

        {/* ③ 技能场景化 — 金句卡片 + 折叠详情 */}
        {activeStep === 'scene' && data && <SceneStep data={data} sceneTag={sceneTag} setSceneTag={setSceneTag} grainIdx={grainIdx} setGrainIdx={setGrainIdx} onPracticeGrain={setPracticeGrain} />}

        {/* ④ 场景产品化 */}
        {activeStep === 'product' && data && (
          <ProductStep
            skillId={id} data={data}
            productSubStep={productSubStep} setProductSubStep={setProductSubStep}
            publishing={publishing} publishChecks={publishChecks} allPassed={allPassed}
            handlePublish={handlePublish} handleDiscard={handleDiscard}
            showDemoModal={showDemoModal} setShowDemoModal={setShowDemoModal}
            onPreviewReport={() => setShowPreviewModal(true)} onDownloadReport={downloadReport}
            onDataRefresh={(d) => setData(d)}
          />
        )}
      </div>

{practiceGrain && (
        <PracticeScenarioModal
          skillId={id}
          grain={practiceGrain}
          grains={grains}
          grainIdx={safeIdx}
          onPrev={() => setGrainIdx(Math.max(0, grainIdx - 1))}
          onNext={() => setGrainIdx(Math.min(grains.length - 1, grainIdx + 1))}
          onClose={() => setPracticeGrain(null)}
        />
      )}

      {showPreviewModal && (
        <ReportPreviewModal
          skillId={id}
          skillName={data.skill.displayName || data.skill.ownerName || '未命名'}
          onClose={() => setShowPreviewModal(false)}
        />
      )}

      {showDemoModal && (
        <ProductDemoModal
          skillId={id}
          skill={{
            ownerName: data.skill.ownerName,
            ownerTitle: data.skill.ownerTitle,
            department: data.skill.department,
            seniority: data.skill.seniority,
            displayName: data.skill.displayName,
          }}
          profile={data.profile ? {
            personality: data.profile.personality,
            speakingStyle: data.profile.speakingStyle,
            background: data.profile.background,
          } : undefined}
          scenarioGrains={data.scenarioGrains || {}}
          isPublished={data.skill.status === 'published'}
          onClose={() => {
            setShowDemoModal(false);
            getAuditDashboard(id).then(d => {
              setData(d);
            });
          }}
        />
      )}
    </div>
  );
}
