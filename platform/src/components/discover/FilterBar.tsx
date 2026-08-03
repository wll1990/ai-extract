'use client';

interface Props {
  total: number;
  activeType: string;
  activeSort: string;
  onTypeChange: (type: string) => void;
  onSortChange: (sort: string) => void;
}

const TYPES = [
  { value: '', label: '全部' },
  { value: 'individual', label: '个人' },
  { value: 'organization', label: '组织' },
];

const SORTS = [
  { value: 'recommended', label: '综合推荐' },
  { value: 'grains', label: '最多经验' },
  { value: 'popular', label: '最活跃' },
];

export function FilterBar({ total, activeType, activeSort, onTypeChange, onSortChange }: Props) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
      <div style={{ display: 'flex', gap: 4 }}>
        {TYPES.map(t => (
          <button
            key={t.value}
            onClick={() => onTypeChange(t.value)}
            style={{
              padding: '6px 14px', borderRadius: 8, border: 'none', cursor: 'pointer',
              fontSize: 13, fontWeight: 600,
              background: activeType === t.value ? 'var(--s3)' : 'transparent',
              color: activeType === t.value ? 'var(--fg-high)' : 'var(--fg-low)',
              transition: 'all 0.15s',
            }}
          >
            {t.label}{t.value === '' ? ` (${total})` : ''}
          </button>
        ))}
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span style={{ fontSize: 12, color: 'var(--fg-low)' }}>排序</span>
        <select
          value={activeSort}
          onChange={e => onSortChange(e.target.value)}
          style={{
            padding: '6px 10px', borderRadius: 8, border: '1px solid var(--border-subtle)',
            background: 'var(--surface)', fontSize: 12, color: 'var(--fg-mid)',
            cursor: 'pointer', outline: 'none',
          }}
        >
          {SORTS.map(s => (
            <option key={s.value} value={s.value}>{s.label}</option>
          ))}
        </select>
      </div>
    </div>
  );
}
