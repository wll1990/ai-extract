'use client';

import { useState, useEffect, useCallback, useRef } from 'react';
import { Navbar } from '@/components/Navbar';
import { Footer } from '@/components/Footer';
import { ExpertCard } from '@/components/discover/ExpertCard';
import { SkeletonCard } from '@/components/discover/SkeletonCard';
import { FilterBar } from '@/components/discover/FilterBar';
import { fetchPublicSkills, type PublicSkillInfo } from '@/lib/api/skill';

export default function DiscoverPage() {
  const [skills, setSkills] = useState<PublicSkillInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [activeType, setActiveType] = useState('');
  const [activeSort, setActiveSort] = useState('recommended');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const debounceRef = useRef<ReturnType<typeof setTimeout>>();

  const load = useCallback(async (p: number, s: string, t: string, sort: string) => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchPublicSkills({ search: s, type: t, sort, page: p, size: 20 });
      if (p === 1) {
        setSkills(data.content);
      } else {
        setSkills(prev => [...prev, ...data.content]);
      }
      setTotal(data.total);
      setTotalPages(data.totalPages);
    } catch {
      setError('加载失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, []);

  // 首次加载
  useEffect(() => { load(1, '', '', 'recommended'); }, [load]);

  // 搜索防抖
  const handleSearchChange = (value: string) => {
    setSearchInput(value);
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      setSearch(value);
      setPage(1);
      load(1, value, activeType, activeSort);
    }, 300);
  };

  // 类型切换
  const handleTypeChange = (type: string) => {
    setActiveType(type);
    setPage(1);
    load(1, search, type, activeSort);
  };

  // 排序切换
  const handleSortChange = (sort: string) => {
    setActiveSort(sort);
    setPage(1);
    load(1, search, activeType, sort);
  };

  // 加载更多
  const handleLoadMore = () => {
    const next = page + 1;
    setPage(next);
    load(next, search, activeType, activeSort);
  };

  // 搜索按钮
  const handleSearchSubmit = () => {
    setSearch(searchInput);
    setPage(1);
    load(1, searchInput, activeType, activeSort);
  };

  return (
    <main style={{ background: 'var(--s1)', color: 'var(--fg-high)', minHeight: '100vh' }}>
      <Navbar />

      <section style={{ maxWidth: 960, margin: '0 auto', padding: '60px 40px 40px' }}>
        <h1 style={{ fontSize: 26, fontWeight: 800, marginBottom: 6 }}>专家知识库</h1>
        <p style={{ color: 'var(--fg-mid)', fontSize: 14, marginBottom: 20 }}>
          与行业顶尖销冠对话，汲取实战经验，提升你的销售能力
        </p>

        {/* 搜索栏 */}
        <div style={{ display: 'flex', gap: 8, marginBottom: 20 }}>
          <div style={{
            flex: 1, display: 'flex', alignItems: 'center',
            border: '1.5px solid var(--border-subtle)', borderRadius: 14,
            padding: '0 16px', height: 48, background: 'var(--surface)',
            transition: 'border-color 0.2s',
          }}
            onFocusCapture={e => (e.currentTarget.style.borderColor = 'var(--s12)')}
            onBlurCapture={e => (e.currentTarget.style.borderColor = 'var(--border-subtle)')}
          >
            <span style={{ fontSize: 16, marginRight: 8 }}>🔍</span>
            <input
              value={searchInput}
              onChange={e => handleSearchChange(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') handleSearchSubmit(); }}
              placeholder="搜索专家、行业、技能..."
              style={{
                flex: 1, border: 'none', outline: 'none', fontSize: 14,
                background: 'transparent', color: 'var(--fg-high)',
              }}
            />
          </div>
          <button onClick={handleSearchSubmit} style={{
            padding: '0 20px', borderRadius: 14, border: 'none', cursor: 'pointer',
            background: 'var(--s12)', color: '#fff', fontSize: 14, fontWeight: 600,
            whiteSpace: 'nowrap',
          }}>
            搜索
          </button>
        </div>

        {/* 筛选栏 */}
        <FilterBar
          total={total}
          activeType={activeType}
          activeSort={activeSort}
          onTypeChange={handleTypeChange}
          onSortChange={handleSortChange}
        />

        {/* 错误 */}
        {error && (
          <div style={{ textAlign: 'center', padding: '40px 0', color: 'var(--fg-mid)' }}>
            <p>{error}</p>
            <button onClick={() => load(1, search, activeType, activeSort)}
              style={{ marginTop: 12, padding: '8px 24px', borderRadius: 10, border: 'none', background: 'var(--s12)', color: '#fff', cursor: 'pointer', fontSize: 13 }}>
              重试
            </button>
          </div>
        )}

        {/* 骨架屏 */}
        {loading && skills.length === 0 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {Array.from({ length: 3 }).map((_, i) => (<SkeletonCard key={i} />))}
          </div>
        )}

        {/* 空状态 */}
        {!loading && !error && skills.length === 0 && (
          <div style={{ textAlign: 'center', padding: '80px 20px', color: 'var(--fg-low)' }}>
            <div style={{ fontSize: 48, marginBottom: 16 }}>{search ? '🔍' : '📭'}</div>
            <p style={{ fontSize: 14 }}>
              {search ? '暂无匹配的专家' : '还没有已发布的分身'}
            </p>
            <p style={{ fontSize: 12, marginTop: 4 }}>
              {search ? '试试其他搜索词' : '创建并发布你的第一个分身吧'}
            </p>
          </div>
        )}

        {/* 卡片列表 */}
        {skills.length > 0 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {skills.map((skill, i) => (
              <ExpertCard key={skill.id} skill={skill} index={i} />
            ))}
          </div>
        )}

        {/* 加载更多 */}
        {skills.length > 0 && page < totalPages && (
          <div style={{ textAlign: 'center', marginTop: 20 }}>
            <button onClick={handleLoadMore} disabled={loading} style={{
              padding: '10px 32px', borderRadius: 12, border: '1px solid var(--border-subtle)',
              background: 'var(--surface)', color: 'var(--fg-mid)', cursor: 'pointer',
              fontSize: 13, fontWeight: 600,
            }}>
              {loading ? '加载中...' : `加载更多（第 ${page} 页 / 共 ${totalPages} 页）`}
            </button>
          </div>
        )}
      </section>

      <Footer />
    </main>
  );
}
