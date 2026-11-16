'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import React, { useState, useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { getAuditDashboard, publishSkill, getSkillNotifications, type AuditDashboard, type SkillNotifications } from '@/lib/api/audit';
import { API_BASE, authHeaders} from '@/lib/api/client';
import { getToken } from '@/lib/storage';
import PracticeScenarioModal from '@/components/modals/PracticeScenarioModal';
import ProductDemoModal from '@/components/modals/ProductDemoModal';
import { ReportPreviewModal } from '@/components/modals/ReportPreviewModal';
import ExplicitStep from '@/components/admin/ExplicitStep';
import SkillStep from '@/components/admin/SkillStep';
import SceneStep from '@/components/admin/SceneStep';
import ProductStep from '@/components/admin/ProductStep';
import { MATERIAL_TERMINAL_STATUSES } from '@/lib/material-status';

type Step = 'explicit' | 'skill' | 'scene' | 'product';

const downloadReport = async (skillId: string, format: 'html') => {
  const url = `${API_BASE}/admin/skills/${skillId}/report?download=true`;
  const res = await fetch(url, { headers: { ...authHeaders() } });
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
  const [alerts, setAlerts] = useState<SkillNotifications | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [activeStep, setActiveStep] = useState<Step>('explicit');
  const [publishing, setPublishing] = useState(false);
  const [practiceGrain, setPracticeGrain] = useState<any>(null);
  const [sceneTag, setSceneTag] = useState('');
  const [grainIdx, setGrainIdx] = useState(0);

  // 当前场景标签下的所有颗粒 — 未选中时与 SceneStep 一样默认第一个标签
  const sceneTags = Object.keys(data?.scenarioGrains || {});
  const activeSceneTag = sceneTag || sceneTags[0] || '';
  const grains = (data?.scenarioGrains?.[activeSceneTag] || []) as any[];
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

  // 产品导览需要把页面切到指定步骤后再聚光，避免指着未渲染的内容
  useEffect(() => {
    const onSetView = (e: Event) => {
      const detail = (e as CustomEvent<{ view?: string; sub?: string }>).detail;
      if (detail?.view === 'explicit' || detail?.view === 'skill'
        || detail?.view === 'scene' || detail?.view === 'product') {
        setActiveStep(detail.view);
      }
      if (detail?.sub === 'profile' || detail?.sub === 'verify' || detail?.sub === 'publish') {
        setProductSubStep(detail.sub);
      }
    };
    window.addEventListener('tour:set-view', onSetView);
    return () => window.removeEventListener('tour:set-view', onSetView);
  }, []);


  useEffect(() => {
    let mounted = true;
    let timerId: ReturnType<typeof setTimeout> | null = null;

    const poll = () => {
      if (!mounted) return;
      getAuditDashboard(id).then(d => {
        if (!mounted) return;
        setData(d);
        setLoadError('');
        const allDone = d.materials.every((m: any) => MATERIAL_TERMINAL_STATUSES.has(m.status));
        if (!allDone && mounted) timerId = setTimeout(poll, 5000);
      }).catch((e: unknown) => {
        if (!mounted) return;
        const status = (e as { status?: number })?.status;
        setLoadError(status === 403
          ? '当前账号没有审核该分身的权限'
          : '审核面板加载失败，请稍后重试');
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

  // NT-02：接入已有的质量告警接口（缺口/低质量颗粒/满意率）
  useEffect(() => {
    if (!id) return;
    getSkillNotifications(id).then(setAlerts).catch(() => setAlerts(null));
  }, [id]);

  if (loading) return <LoadingSpinner />;

  if (loadError || !data) {
    return (
      <div className="mx-auto max-w-xl p-10 text-center">
        <div className="rounded-xl border border-border bg-surface-2 p-8">
          <p className="text-sm text-red-500">{loadError || '审核数据为空'}</p>
          {!loadError.includes('权限') && (
            <button onClick={() => location.reload()} className="mt-4 rounded-lg border px-4 py-2 text-sm">
              重试
            </button>
          )}
        </div>
      </div>
    );
  }

  // 完成态按真实数据判断，不能按「当前在第几步」推断，否则跳到第 4 步会把前 3 步全标完成
  const isStepDone = (step: Step): boolean => {
    if (!data) return false;
    switch (step) {
      case 'explicit':
        return data.materials.length > 0
          && data.materials.every(m => MATERIAL_TERMINAL_STATUSES.has(m.status));
      case 'skill':
        return (data.skillsSummary?.totalGrains || 0) > 0;
      case 'scene':
        return (data.skillsSummary?.sceneTags?.length || 0) > 0;
      case 'product':
        return ['published', 'discarded'].includes(data.skill?.status || '');
      default:
        return false;
    }
  };

  const getStepStatus = (step: Step): 'done' | 'current' | 'pending' => {
    if (step === activeStep) return 'current';
    return isStepDone(step) ? 'done' : 'pending';
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
      const res = await publishSkill(id, 'publish');
      // 后端校验不通过时返回 200 + passed:false，不能当成功
      if (res && res.passed === false) {
        alert('发布条件未满足：\n' + (res.blockers || []).join('\n'));
        return;
      }
      router.push('/admin/skills');
    } catch (e) {
      console.error('发布失败:', e);
      alert('发布失败，请重试');
    } finally {
      setPublishing(false);
    }
  };

  const handleUnpublish = async () => {
    if (!confirm('确认撤销发布？撤销后分身回到审核中，员工将看不到它。')) return;
    setPublishing(true);
    try {
      await publishSkill(id, 'unpublish');
      router.push('/admin/skills');
    } catch (e) {
      console.error('撤销发布失败:', e);
      alert('撤销失败，请重试');
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

      {/* NT-02 质量告警：有异常才展示，避免正常分身也顶一条横幅 */}
      {alerts && alerts.totalAlerts > 0 && (
        <div className="mb-5 rounded-lg border border-warning/40 bg-warning-bg px-4 py-3 text-sm text-warning-text">
          <p className="font-medium mb-1">该分身有 {alerts.totalAlerts} 项质量告警</p>
          <ul className="list-disc pl-5 space-y-0.5 text-xs">
            {alerts.gapAlert && (
              <li>知识缺口 {alerts.openGaps} 条（≥10 条建议补充知识库）</li>
            )}
            {alerts.lowQualityAlert && (
              <li>低质量颗粒 {alerts.lowQualityGrains} 条（👎≥5，建议优化话术或废弃）</li>
            )}
            {alerts.satisfactionAlert && (
              <li>近 7 天满意率 {alerts.satisfactionRate}%（低于 70%）</li>
            )}
          </ul>
        </div>
      )}

      {/* 步骤进度条 */}
      <div className="flex items-center gap-0 mb-8 bg-surface-2 rounded-lg border border-border p-4" data-tour="audit-step-tabs">
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
        {activeStep === 'scene' && data && <SceneStep data={data} sceneTag={activeSceneTag} setSceneTag={setSceneTag} grainIdx={grainIdx} setGrainIdx={setGrainIdx} onPracticeGrain={setPracticeGrain} />}

        {/* ④ 场景产品化 */}
        {activeStep === 'product' && data && (
          <ProductStep
            skillId={id} data={data}
            productSubStep={productSubStep} setProductSubStep={setProductSubStep}
            publishing={publishing} publishChecks={publishChecks} allPassed={allPassed}
            handlePublish={handlePublish} handleDiscard={handleDiscard} handleUnpublish={handleUnpublish}
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
