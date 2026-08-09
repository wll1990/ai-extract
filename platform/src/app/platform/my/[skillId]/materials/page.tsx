'use client';

import { useState, useEffect, useCallback, useRef } from 'react';
import { useParams, useRouter } from 'next/navigation';
import PlatformTopBar from '@/components/ui/PlatformTopBar';
import { MaterialTypeSelector } from '@/components/materials/MaterialTypeSelector';
import { PreUploadChecklist } from '@/components/materials/PreUploadChecklist';
import { MaterialUploadZone } from '@/components/materials/MaterialUploadZone';
import { FilePreviewCard } from '@/components/materials/FilePreviewCard';
import { MaterialList } from '@/components/materials/MaterialList';
import { UploadTips } from '@/components/materials/UploadTips';
import { uploadMaterial, listSkillMaterials, deleteSkillMaterial } from '@/lib/api/materials';
import { fetchSkillDetail } from '@/lib/api/skill';
import type { MaterialItem } from '@/lib/api/materials';

interface SelectedFile {
  file: File;
  progress: number;
  status: 'pending' | 'uploading' | 'done' | 'error';
  error?: string;
}

export default function MaterialsPage() {
  const params = useParams();
  const router = useRouter();
  const skillId = (params.skillId as string) || '';

  const [skillName, setSkillName] = useState('');
  const [selectedType, setSelectedType] = useState('dialogue');
  const [guideRead, setGuideRead] = useState(false);
  const [files, setFiles] = useState<SelectedFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const [uploadDone, setUploadDone] = useState(false);
  const [materials, setMaterials] = useState<MaterialItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const pollingRef = useRef<NodeJS.Timeout | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  // 记住"必看"已读
  useEffect(() => {
    setGuideRead(localStorage.getItem('material_guide_read') === 'true');
  }, []);

  const markGuideRead = () => {
    localStorage.setItem('material_guide_read', 'true');
    setGuideRead(true);
  };

  const resetGuide = () => {
    localStorage.removeItem('material_guide_read');
    setGuideRead(false);
  };

  // 切换素材类型时重置上传完成状态
  useEffect(() => { setUploadDone(false); }, [selectedType]);

  // 加载技能名
  useEffect(() => {
    if (!skillId) return;
    fetchSkillDetail(skillId)
      .then((d) => {
        const name = d.displayName || d.ownerName;
        if (!name) console.warn('分身详情缺少名称字段', d);
        setSkillName(name || '素材管理');
      })
      .catch((e) => {
        console.error('获取分身名称失败', e);
        setSkillName('素材管理');
      });
  }, [skillId]);

  // 加载素材列表（generation 计数器防止竞态）
  const genRef = useRef(0);

  const fetchMaterials = useCallback(() => {
    if (!skillId) return;
    const gen = ++genRef.current;
    listSkillMaterials(skillId, 1, 50)
      .then((data) => {
        if (gen !== genRef.current) return; // 过期请求，丢弃
        setMaterials(data.content || []);
        setLoading(false);
        const hasActive = (data.content || []).some(
          (m) => !['extracted', 'rejected', 'discarded', 'analyzed'].includes(m.status),
        );
        if (hasActive && !pollingRef.current) {
          pollingRef.current = setInterval(() => {
            const pollGen = ++genRef.current;
            listSkillMaterials(skillId, 1, 50)
              .then((d) => {
                if (pollGen !== genRef.current) return;
                setMaterials(d.content || []);
                const stillActive = (d.content || []).some(
                  (m) => !['extracted', 'rejected', 'discarded', 'analyzed'].includes(m.status),
                );
                if (!stillActive && pollingRef.current) {
                  clearInterval(pollingRef.current);
                  pollingRef.current = null;
                }
              })
              .catch(() => {});
          }, 3000);
        }
      })
      .catch((err) => { if (gen === genRef.current) { setError(err.message || '加载失败'); setLoading(false); } });
  }, [skillId]);

  useEffect(() => {
    fetchMaterials();
    return () => { if (pollingRef.current) clearInterval(pollingRef.current); };
  }, [fetchMaterials]);

  // 添加文件
  const handleFilesSelected = (newFiles: File[]) => {
    const valid: SelectedFile[] = [];
    for (const f of newFiles) {
      const ext = f.name.split('.').pop()?.toLowerCase() || '';
      const allowed = ['pdf', 'doc', 'docx', 'txt', 'mp3', 'm4a', 'wav', 'jpg', 'jpeg', 'png'];
      if (!allowed.includes(ext)) { valid.push({ file: f, progress: 0, status: 'error', error: `不支持 .${ext} 格式` }); continue; }
      if (f.size > 20 * 1024 * 1024) { valid.push({ file: f, progress: 0, status: 'error', error: '文件超过 20MB' }); continue; }
      valid.push({ file: f, progress: 0, status: 'pending' });
    }
    setFiles((prev) => [...prev, ...valid]);
  };

  const removeFile = (idx: number) => setFiles((prev) => prev.filter((_, i) => i !== idx));

  // 逐文件上传
  const handleUploadAll = async () => {
    const pending = files.filter((f) => f.status === 'pending' || f.status === 'error');
    if (pending.length === 0) return;
    setUploading(true);

    for (let i = 0; i < files.length; i++) {
      if (files[i].status === 'done') continue;
      setFiles((prev) => prev.map((f, j) => (j === i ? { ...f, status: 'uploading' as const, progress: 0 } : f)));
      try {
        await uploadMaterial(skillId, files[i].file, selectedType, (pct) => {
          setFiles((prev) => prev.map((f, j) => (j === i ? { ...f, progress: pct } : f)));
        });
        setFiles((prev) => prev.map((f, j) => (j === i ? { ...f, status: 'done' as const, progress: 100 } : f)));
      } catch (e: any) {
        setFiles((prev) => prev.map((f, j) => (j === i ? { ...f, status: 'error' as const, error: e.message || '上传失败' } : f)));
      }
    }
    setUploading(false);
    setUploadDone(true);
    fetchMaterials();
  };

  const handleDelete = async (materialId: string) => {
    if (!confirm('确定删除这个素材吗？删除后不可恢复。')) return;
    try {
      await deleteSkillMaterial(skillId, materialId);
      setMaterials((prev) => prev.filter((m) => m.id !== materialId));
    } catch (e: any) {
      alert(e.message || '删除失败');
    }
  };

  return (
    <div style={{
      minHeight: '100vh',
      background: '#f8fafc',
    }}>
      <PlatformTopBar backTo="/platform/my" backLabel="我的分身" />
      <div style={{ maxWidth: 1024, margin: '0 auto', padding: '32px 20px' }}>
        <div style={{ marginBottom: 28 }}>
          <h1 style={{ fontSize: 28, fontWeight: 700, color: '#0f172a', margin: '0 0 6px', letterSpacing: '-0.02em' }}>
            {skillName || '素材管理'}
          </h1>
          <p style={{ fontSize: 14, color: '#475569', margin: 0 }}>上传销售素材，AI 自动萃取经验</p>
        </div>

        {/* 桌面端左右布局，移动端堆叠 */}
        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 320px', gap: 24, alignItems: 'start' }}
          className="upload-layout">
          {/* 左侧：上传流程 */}
          <div style={{ minWidth: 0 }}>
            <MaterialTypeSelector value={selectedType} onChange={setSelectedType} />
            <PreUploadChecklist read={guideRead} onMarkRead={markGuideRead} onReset={resetGuide} />
            <MaterialUploadZone disabled={!guideRead} onFilesSelected={handleFilesSelected} />

            {files.length > 0 && (
              <div style={{ marginTop: 16 }}>
                {files.map((f, i) => (
                  <FilePreviewCard key={i} fileName={f.file.name} fileSize={f.file.size}
                    progress={f.progress} status={f.status} error={f.error}
                    materialType={selectedType} onRemove={() => removeFile(i)} />
                ))}
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 12 }}>
                  <span style={{ fontSize: 12, color: '#747f9e' }}>
                    已选 {files.length} 个文件{files.length > 0 && `，共 ${(files.reduce((s, f) => s + f.file.size, 0) / 1024 / 1024).toFixed(1)}MB`}
                  </span>
                  <button onClick={handleUploadAll} disabled={uploading || files.every((f) => f.status === 'done')}
                    style={{ padding: '8px 20px', borderRadius: 100, border: 'none', cursor: uploading ? 'not-allowed' : 'pointer',
                      background: uploading ? '#a0b4ff' : '#2147ff', color: '#fff', fontSize: 13, fontWeight: 500, fontFamily: 'inherit', opacity: uploading ? 0.7 : 1 }}>
                    {uploading ? '上传中...' : '上传全部文件'}
                  </button>
                </div>
              </div>
            )}

            {uploadDone && (
              <div style={{ marginTop: 16, padding: '14px 18px', borderRadius: 14,
                background: '#f0fdf4', border: '1px solid #bbf7d0', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontSize: 13, color: '#166534', fontWeight: 500 }}>✅ 素材已上传，AI 正在后台萃取经验颗粒</span>
                <button onClick={() => router.push(`/platform/my/${skillId}/audit`)}
                  style={{ padding: '7px 16px', borderRadius: 100, border: 'none', cursor: 'pointer',
                    background: '#166534', color: '#fff', fontSize: 12, fontWeight: 600, fontFamily: 'inherit' }}>
                  去审核页查看 →
                </button>
              </div>
            )}
          </div>

          {/* 右侧：指南 + 素材列表 */}
          <div style={{ minWidth: 0 }}>
            <UploadTips />
            <MaterialList materials={materials} loading={loading} error={error}
              onDelete={handleDelete} onRetry={fetchMaterials} />
          </div>
        </div>
      </div>

      {/* 移动端响应式：窄屏切回堆叠 */}
      <style jsx>{`
        @media (max-width: 767px) {
          .upload-layout { grid-template-columns: 1fr !important; }
        }
      `}</style>
    </div>
  );
}
