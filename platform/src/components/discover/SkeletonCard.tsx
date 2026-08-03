'use client';

export function SkeletonCard() {
  return (
    <div style={{
      display: 'flex', gap: 16, alignItems: 'center',
      padding: 20, borderRadius: 20,
      background: 'var(--surface)',
      boxShadow: '0 2px 12px rgba(0,0,0,0.04)',
    }}>
      <div style={{
        width: 56, height: 56, borderRadius: 16, flexShrink: 0,
        background: 'linear-gradient(90deg, var(--s3) 25%, var(--border-subtle) 50%, var(--s3) 75%)',
        backgroundSize: '200% 100%',
        animation: 'shimmer 2s infinite',
      }} />
      <div style={{ flex: 1 }}>
        <div style={{
          width: '40%', height: 16, borderRadius: 8, marginBottom: 8,
          background: 'linear-gradient(90deg, var(--s3) 25%, var(--border-subtle) 50%, var(--s3) 75%)',
          backgroundSize: '200% 100%', animation: 'shimmer 2s infinite',
        }} />
        <div style={{
          width: '25%', height: 12, borderRadius: 6, marginBottom: 12,
          background: 'linear-gradient(90deg, var(--s3) 25%, var(--border-subtle) 50%, var(--s3) 75%)',
          backgroundSize: '200% 100%', animation: 'shimmer 2s infinite',
        }} />
        <div style={{
          width: '60%', height: 32, borderRadius: 16,
          background: 'linear-gradient(90deg, var(--s3) 25%, var(--border-subtle) 50%, var(--s3) 75%)',
          backgroundSize: '200% 100%', animation: 'shimmer 2s infinite',
        }} />
      </div>
    </div>
  );
}
