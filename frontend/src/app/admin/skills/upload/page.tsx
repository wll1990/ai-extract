'use client';
import React, { useState, useEffect } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { apiClient, API_BASE } from '@/lib/api/client';
import { MaterialUploadGuide } from '@/components/skill/MaterialUploadGuide';
import { uploadMaterialText } from '@/lib/api/materials';

interface UserOption {
  id: string; name: string; account: string; spaceId: string;
}

export default function UploadPage() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const [files, setFiles] = useState<File[]>([]);
  const [spaceId, setSpaceId] = useState(searchParams.get('spaceId') || '');
  const [domain, setDomain] = useState('');
  const [users, setUsers] = useState<UserOption[]>([]);
  const [domainTree, setDomainTree] = useState<any[]>([]);
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState<any>(null);
  const [error, setError] = useState('');

  // 文本粘贴模式
  const [inputMode, setInputMode] = useState<'file' | 'text'>('file');
  const [textContent, setTextContent] = useState('');
  const [textTitle, setTextTitle] = useState('');

  useEffect(() => {
    apiClient<UserOption[]>('/admin/users').then(list => {
      setUsers(Array.isArray(list) ? list.filter(u => u.spaceId) : []);
    }).catch(() => {});
    apiClient<any[]>('/api/domains').then(tree => setDomainTree(tree)).catch(() => {});
  }, []);

  const selectedUser = users.find(u => u.spaceId === spaceId);

  const handleUpload = async () => {
    if (files.length === 0) { setError('请至少选择一个文件'); return; }
    if (files.length > 5) { setError('每次最多上传5个文件'); return; }
    if (!spaceId) { setError('请选择要上传给谁'); return; }
    if (!domain) { setError('请选择领域'); return; }

    setUploading(true); setError('');
    const form = new FormData();
    files.forEach(f => form.append('files', f));
    form.append('spaceId', spaceId);
    form.append('domain', domain);

    try {
      const res = await fetch(`${API_BASE}/admin/materials/upload`, {
        method: 'POST', credentials: 'include', body: form,
      });
      const data = await res.json();
      if (data.code === 200) {
        setResult(data.data);
      } else {
        setError(data.message || '上传失败');
      }
    } catch { setError('网络错误'); }
    setUploading(false);
  };

  const handleTextUpload = async () => {
    if (!textContent.trim()) { setError('请输入文本内容'); return; }
    if (textContent.trim().length < 10) { setError('文本内容至少10个字'); return; }
    if (!spaceId) { setError('请选择上传给谁'); return; }
    if (!domain) { setError('请选择领域'); return; }

    setUploading(true); setError('');
    try {
      const data = await uploadMaterialText({
        text: textContent.trim(),
        spaceId,
        domain,
        title: textTitle || undefined,
      });
      setResult(data);
    } catch { setError('网络错误'); }
    setUploading(false);
  };

  // ── 结果页 ──
  if (result) {
    const firstItem = result.results?.[0];
    const acceptance = firstItem?.acceptance;
    const preCheck = firstItem?.preCheck;
    const isRejected = acceptance && !acceptance.passed;

    return (
      <div className="max-w-6xl mx-auto p-6">
        <button onClick={() => { setResult(null); setFiles([]); setTextContent(''); setTextTitle(''); }}
          className="text-sm text-muted-foreground hover:text-foreground mb-6">← 继续上传</button>

        <div className="grid grid-cols-5 gap-8">
          <div className="col-span-3">
            {isRejected ? (
              <div className="text-center">
                <div className="text-5xl mb-4">🚫</div>
                <h1 className="text-xl font-bold mb-2 text-red-500">素材未通过审核</h1>
                <div className="rounded-xl bg-red-50 border border-red-200 p-4 text-left mt-4">
                  <p className="text-sm text-red-700">{acceptance.rejectReason}</p>
                  {acceptance.details && (
                    <div className="mt-2 text-xs text-red-500 space-y-0.5">
                      {Object.entries(acceptance.details).map(([k, v]) => (
                        <p key={k}>{k}: {String(v)}</p>
                      ))}
                    </div>
                  )}
                </div>
                <p className="text-xs text-muted-foreground mt-4">请根据以上原因修改素材后重新上传</p>
              </div>
            ) : (
              <div className="text-center">
                <div className="text-5xl mb-4">
                  {preCheck?.grade === 'good' ? '🟢' : preCheck?.grade === 'warning' ? '🟡' : '✅'}
                </div>
                <h1 className="text-xl font-bold mb-2">上传成功</h1>
                <p className="text-muted-foreground mb-1">共 {result.uploaded} 个文件</p>

                {preCheck && (
                  <div className="rounded-xl border border-border bg-surface-2 p-4 mt-4 text-left">
                    <div className="flex items-center justify-between mb-3">
                      <span className="text-sm font-semibold">素材质量预检</span>
                      <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                        preCheck.grade === 'good' ? 'bg-green-100 text-green-700' :
                        preCheck.grade === 'warning' ? 'bg-yellow-100 text-yellow-700' :
                        'bg-red-100 text-red-700'
                      }`}>
                        {preCheck.grade === 'good' ? '优秀' : preCheck.grade === 'warning' ? '可用' : '偏低'} · {preCheck.overallScore}分
                      </span>
                    </div>

                    {preCheck.estimatedGrainMin > 0 && (
                      <p className="text-xs text-muted-foreground mb-2">
                        预计可提取 {preCheck.estimatedGrainMin}-{preCheck.estimatedGrainMax} 条{firstItem?.skillName ? '经验' : ''}
                      </p>
                    )}

                    {preCheck.detectedScenes?.length > 0 && (
                      <div className="flex flex-wrap gap-1 mb-3">
                        {preCheck.detectedScenes.map((s: string) => (
                          <span key={s} className="rounded-full bg-primary-light text-primary text-[10px] px-2 py-0.5">{s}</span>
                        ))}
                      </div>
                    )}

                    {preCheck.checks?.length > 0 && (
                      <div className="space-y-1.5">
                        {preCheck.checks.map((c: any, i: number) => (
                          <div key={i} className="flex items-start gap-2 text-xs">
                            <span className="flex-shrink-0 mt-0.5">{c.passed ? '✅' : '⚠️'}</span>
                            <div>
                              <span className={c.passed ? 'text-muted-foreground' : 'text-yellow-600'}>{c.feedback}</span>
                              {c.suggestion && <p className="text-muted-foreground-2 mt-0.5">{c.suggestion}</p>}
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}

            <div className="flex gap-3 justify-center mt-6">
              {firstItem?.skillId && !isRejected && (
                <button onClick={() => router.push(`/admin/skills/${firstItem.skillId}/audit`)}
                  className="px-4 py-2 bg-primary text-white rounded-lg text-sm">进入审核</button>
              )}
              <button onClick={() => { setResult(null); setFiles([]); setTextContent(''); setTextTitle(''); }}
                className="px-4 py-2 border rounded-lg text-sm">继续上传</button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  // ── 上传表单（左右分屏）──
  return (
    <div className="max-w-6xl mx-auto p-6">
      <button onClick={() => router.back()} className="text-sm text-muted-foreground hover:text-foreground mb-4">← 返回</button>
      <h1 className="text-xl font-bold mb-2">上传素材</h1>

      {/* 输入模式切换 */}
      <div className="flex items-center gap-0.5 bg-surface rounded-lg p-0.5 w-fit mb-6">
        <button onClick={() => { setInputMode('file'); setError(''); }}
          className={`rounded-md px-4 py-1.5 text-sm font-medium transition-colors ${inputMode === 'file' ? 'bg-white text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}>
          📁 上传文件
        </button>
        <button onClick={() => { setInputMode('text'); setError(''); }}
          className={`rounded-md px-4 py-1.5 text-sm font-medium transition-colors ${inputMode === 'text' ? 'bg-white text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}>
          📝 粘贴文本
        </button>
      </div>

      <div className="grid grid-cols-5 gap-8">
        {/* 左侧：功能区 */}
        <div className="col-span-3 space-y-5">
          {/* 领域选择 */}
          <div>
            <label className="block text-sm font-medium text-muted-foreground mb-2">
              选择领域 <span className="text-red-400">*</span>
            </label>
            <select value={domain} onChange={e => setDomain(e.target.value)}
              className="w-full border border-border rounded-lg p-2 text-sm">
              <option value="">-- 请选择领域 --</option>
              {domainTree.map((parent: any) => (
                <optgroup key={parent.id} label={parent.name}>
                  {parent.children?.map((child: any) => (
                    <option key={child.id} value={child.id}>{child.name}</option>
                  ))}
                </optgroup>
              ))}
            </select>
          </div>

          {/* 上传给谁 — 选择用户 */}
          <div>
            <label className="block text-sm font-medium text-muted-foreground mb-2">
              上传给谁 <span className="text-red-400">*</span>
            </label>
            <select value={spaceId} onChange={e => setSpaceId(e.target.value)}
              className="w-full border border-border rounded-lg p-2 text-sm">
              <option value="">-- 选择用户 --</option>
              {users.map(u => (
                <option key={u.id} value={u.spaceId}>{u.name}{u.account ? ` · ${u.account}` : ''}</option>
              ))}
            </select>
            {selectedUser && (
              <p className="text-xs text-muted-foreground mt-1">
                将上传到「{selectedUser.name}」的空间，系统会使用其领域配置进行预检和萃取
              </p>
            )}
          </div>

          {/* 文件选择 / 文本粘贴 */}
          {inputMode === 'file' ? (
            <>
              <div>
                <label className="block text-sm font-medium text-muted-foreground mb-2">选择文件</label>
                <input type="file" multiple onChange={e => {
                    const selected = Array.from(e.target.files || []).slice(0, 5);
                    setFiles(selected); setError('');
                  }}
                  accept=".txt,.md,.csv,.html,.json,.xml,.pdf,.docx,.doc,.xlsx,.xls,.png,.jpg,.jpeg,.mp3,.wav,.m4a"
                  className="w-full border border-border rounded-lg p-3 text-sm file:mr-4 file:py-1 file:px-3 file:rounded file:border-0 file:text-sm file:bg-primary-light file:text-primary" />
                {files.length > 0 && (
                  <div className="mt-2 space-y-1">
                    {files.map((f, i) => (
                      <p key={i} className="text-xs text-muted-foreground-2">{f.name} ({(f.size / 1024).toFixed(0)}KB)</p>
                    ))}
                    <p className="text-xs text-primary">共 {files.length} 个文件</p>
                  </div>
                )}
              </div>
              {error && <p className="text-red-500 text-sm">{error}</p>}
              <button onClick={handleUpload} disabled={uploading}
                className="w-full py-3 bg-primary text-white rounded-lg font-medium disabled:opacity-50">
                {uploading ? '上传中...' : '上传素材'}
              </button>
            </>
          ) : (
            <>
              <div>
                <label className="block text-sm font-medium text-muted-foreground mb-2">标题（可选）</label>
                <input value={textTitle} onChange={e => setTextTitle(e.target.value)}
                  placeholder="给这段素材起个名字，方便后续查找"
                  className="w-full border border-border rounded-lg p-2 text-sm" />
              </div>
              <div>
                <label className="block text-sm font-medium text-muted-foreground mb-2">
                  文本内容 <span className="text-red-400">*</span>
                </label>
                <textarea value={textContent} onChange={e => setTextContent(e.target.value)}
                  placeholder="粘贴对话记录、笔记、案例等...&#10;&#10;要求：中文占比 ≥ 70%，至少 50 字，真实经验"
                  rows={14}
                  className="w-full border border-border rounded-lg p-3 text-sm resize-y" />
                <p className="text-xs text-muted-foreground-2 mt-1">{textContent.length} 字</p>
              </div>
              {error && <p className="text-red-500 text-sm">{error}</p>}
              <button onClick={handleTextUpload} disabled={uploading}
                className="w-full py-3 bg-primary text-white rounded-lg font-medium disabled:opacity-50">
                {uploading ? '上传中...' : '上传文本素材'}
              </button>
            </>
          )}
        </div>

        {/* 右侧：上传指南 */}
        <div className="col-span-2">
          <div className="sticky top-6 rounded-xl border border-border bg-surface-2 p-5 max-h-[calc(100vh-6rem)] overflow-y-auto">
            <h3 className="text-sm font-bold text-foreground mb-4">📋 上传前必看</h3>
            <MaterialUploadGuide />
          </div>
        </div>
      </div>
    </div>
  );
}
