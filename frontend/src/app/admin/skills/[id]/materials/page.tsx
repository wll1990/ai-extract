'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { Pagination } from '@/components/ui/Pagination';
import React, { useState, useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { listMaterials, deleteMaterial, submitManualText, retryMaterial, updateMaterialStatus, type MaterialItem } from '@/lib/api/materials';
import {
  MATERIAL_STATUS_LABELS as STATUS_LABELS,
  MATERIAL_STATUS_COLORS as STATUS_COLORS,
  MATERIAL_TERMINAL_STATUSES as TERMINAL_STATUSES,
  MATERIAL_FILTER_STATUSES,
} from '@/lib/material-status';

const PAGE_SIZE = 20;

export default function MaterialsPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const [materials, setMaterials] = useState<MaterialItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState('all');
  // 服务端分页：接口本来就支持 page/size，前端不再一次性拉全量
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [reloadKey, setReloadKey] = useState(0);

  // 手动补录弹窗
  const [manualModal, setManualModal] = useState<{ open: boolean; materialId: string; fileName: string }>({ open: false, materialId: '', fileName: '' });
  const [manualText, setManualText] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let timer: ReturnType<typeof setInterval> | null = null;
    const startedAt = Date.now();
    const MAX_POLL_MS = 10 * 60 * 1000;
    const stop = () => {
      if (timer) { clearInterval(timer); timer = null; }
    };
    const fetchData = async () => {
      if (Date.now() - startedAt > MAX_POLL_MS) {
        stop();
        return;
      }
      try {
        const d = await listMaterials(id, page, PAGE_SIZE, filter);
        const items = d.content || [];
        setMaterials(items);
        setTotal(d.total ?? 0);
        setTotalPages(Math.max(1, d.totalPages ?? 1));
        // 当前页没有正在处理中的素材时停止轮询；否则每 3 秒刷新当前页
        if (items.length > 0 && items.every(m => TERMINAL_STATUSES.has(m.status))) {
          stop();
        }
      } catch (e) {
        console.error('加载素材列表失败', e);
      } finally {
        setLoading(false);
      }
    };
    fetchData();
    timer = setInterval(fetchData, 3000);
    return () => { if (timer) clearInterval(timer); };
  }, [id, page, filter, reloadKey]);

  if (loading) return <LoadingSpinner />;

  const filtered = materials;
  const refresh = () => setReloadKey(k => k + 1);

  return (
    <div className="max-w-5xl mx-auto p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <button onClick={() => router.back()} className="text-sm text-muted-foreground hover:text-foreground mb-1">← 返回</button>
          <h1 className="text-xl font-bold">素材管理</h1>
        </div>
        <button onClick={() => router.push(`/admin/skills/${id}/profile`)}
          className="px-4 py-2 bg-primary text-white rounded-lg text-sm hover:bg-primary-hover">
          ✏️ 编辑画像
        </button>
      </div>

      {/* 状态筛选 */}
      <div className="flex gap-2 mb-4">
        {['all', ...MATERIAL_FILTER_STATUSES].map(s => (
          <button key={s} onClick={() => { setFilter(s); setPage(1); }}
            className={`px-3 py-1 rounded-full text-xs ${filter === s ? 'bg-primary text-white' : 'bg-primary-light text-muted-foreground hover:bg-border'}`}>
            {s === 'all' ? '全部' : STATUS_LABELS[s] || s}
          </button>
        ))}
      </div>

      {/* 文件列表 */}
      {filtered.length === 0 ? (
        <div className="text-center py-20 text-muted-foreground-2">
          <p className="text-4xl mb-2">📁</p>
          <p>{filter === 'all' ? '这个分身还没有素材，去「素材上传」添加第一份' : '当前筛选条件下没有素材'}</p>
        </div>
      ) : (
        <div className="bg-surface-2 rounded-lg border border-border overflow-hidden" data-tour="materials-table">
          <table className="w-full text-sm">
            <thead className="bg-surface border-b">
              <tr>
                <th className="text-left p-3 font-medium text-muted-foreground">文件名</th>
                <th className="text-left p-3 font-medium text-muted-foreground">类型</th>
                <th className="text-left p-3 font-medium text-muted-foreground">大小</th>
                <th className="text-left p-3 font-medium text-muted-foreground">版本</th>
                <th className="text-left p-3 font-medium text-muted-foreground">状态</th>
                <th className="text-left p-3 font-medium text-muted-foreground">上传时间</th>
                <th className="text-right p-3 font-medium text-muted-foreground">操作</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(m => (
                <tr key={m.id} className="border-b last:border-0 hover:bg-surface">
                  <td className="p-3 font-medium">{m.fileName}</td>
                  <td className="p-3 text-muted-foreground">{m.fileType || '-'}</td>
                  <td className="p-3 text-muted-foreground">{m.fileSize ? `${(m.fileSize / 1024).toFixed(0)}KB` : '-'}</td>
                  <td className="p-3 text-muted-foreground">v{m.version}</td>
                  <td className="p-3">
                    <span className={`px-2 py-0.5 rounded-full text-xs ${STATUS_COLORS[m.status] || 'bg-primary-light'}`}>
                      {STATUS_LABELS[m.status] || m.status}
                    </span>
                  </td>
                  <td className="p-3 text-muted-foreground">{m.createdAt ? new Date(m.createdAt).toLocaleDateString('zh-CN') : '-'}</td>
                  <td className="p-3 text-right">
                    {['parse_failed', 'cleaning_failed', 'rejected'].includes(m.status) && (
                      <button onClick={() => {
                        retryMaterial(m.id)
                          .then(() => refresh())
                          .catch((e: unknown) => alert(e instanceof Error ? e.message : '重试失败'));
                      }}
                        className="text-primary hover:underline text-xs mr-2">重试</button>
                    )}
                    {(m.analysisNotes && m.analysisNotes.includes('需人工补充')) && (
                      <button onClick={() => { setManualModal({ open: true, materialId: m.id, fileName: m.fileName }); setManualText(''); }}
                        className="text-primary hover:underline text-xs mr-2">✏️ 补录文字</button>
                    )}
                    {m.status !== 'discarded' && m.status !== 'extracted' && (
                      <button onClick={() => {
                        if (!confirm('放弃这个素材？放弃后不会参与萃取，可随时恢复。')) return;
                        updateMaterialStatus(id, m.id, 'discarded')
                          .then(() => refresh())
                          .catch((e: unknown) => alert(e instanceof Error ? e.message : '操作失败'));
                      }}
                        className="text-warning hover:underline text-xs mr-2">放弃</button>
                    )}
                    {m.status === 'discarded' && (
                      <button onClick={() => {
                        updateMaterialStatus(id, m.id, 'uploaded')
                          .then(() => refresh())
                          .catch((e: unknown) => alert(e instanceof Error ? e.message : '恢复失败'));
                      }}
                        className="text-primary hover:underline text-xs mr-2">恢复</button>
                    )}
                    <button onClick={() => {
                      if (!confirm('确认删除？')) return;
                      deleteMaterial(id, m.id)
                        .then(() => { if (materials.length === 1 && page > 1) setPage(page - 1); else refresh(); })
                        .catch((e: unknown) => alert(e instanceof Error ? e.message : '删除失败，请稍后重试'));
                    }}
                      className="text-red-500 hover:underline text-xs">删除</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Pagination page={page} totalPages={totalPages} totalElements={total}
        onPageChange={p => setPage(p)} loading={loading} />

      {/* 手动补录弹窗 */}
      {manualModal.open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={() => setManualModal({ open: false, materialId: '', fileName: '' })}>
          <div className="bg-white rounded-xl shadow-xl p-6 w-full max-w-lg mx-4" onClick={e => e.stopPropagation()}>
            <h3 className="text-lg font-bold mb-1">✏️ 手动补录文字</h3>
            <p className="text-sm text-muted-foreground mb-4">{manualModal.fileName} — 无法自动解析，请粘贴对话文本</p>
            <textarea value={manualText} onChange={e => setManualText(e.target.value)}
              placeholder="在此粘贴录音转写文本或图片中的对话内容..."
              className="w-full h-48 border border-border rounded-lg p-3 text-sm resize-none focus:outline-none focus:ring-2 focus:ring-primary/20"
            />
            <div className="flex justify-end gap-2 mt-4">
              <button onClick={() => setManualModal({ open: false, materialId: '', fileName: '' })}
                className="px-4 py-2 text-sm text-muted-foreground hover:text-foreground">取消</button>
              <button disabled={!manualText.trim() || submitting}
                onClick={async () => {
                  if (!manualText.trim() || submitting) return;
                  setSubmitting(true);
                  try {
                    await submitManualText(manualModal.materialId, manualText);
                    setManualModal({ open: false, materialId: '', fileName: '' });
                    // 刷新列表（留在当前页与筛选条件上）
                    refresh();
                  } catch { alert('提交失败，请重试'); }
                  setSubmitting(false);
                }}
                className="px-4 py-2 text-sm bg-primary text-white rounded-lg hover:bg-primary-hover disabled:opacity-40">
                {submitting ? '提交中...' : '提交并进入清洗'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
