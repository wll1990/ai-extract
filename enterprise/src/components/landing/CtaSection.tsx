import Link from 'next/link';

export function CtaSection() {
  return (
    <section style={{
      textAlign: 'center', padding: '80px 40px',
    }}>
      <div style={{
        maxWidth: 600, margin: '0 auto',
        padding: '64px 40px', borderRadius: 'var(--radius-3xl)',
        background: 'linear-gradient(135deg, var(--s12), #3e1700)',
        color: '#fff',
      }}>
        <h2 style={{
          fontSize: 'clamp(28px, 4vw, 36px)', fontWeight: 800,
          letterSpacing: '-0.04em', lineHeight: 1.2, marginBottom: 12,
        }}>
          准备好让团队拥有
          <br />
          顶级专家的 AI 分身了吗？
        </h2>
        <p style={{
          fontSize: 15, color: 'rgba(255,255,255,0.7)',
          margin: '16px 0 32px',
        }}>
          免费开始，无需信用卡
        </p>
        <Link href="/discover" style={{
          display: 'inline-flex', alignItems: 'center', gap: 6,
          padding: '17px 38px', borderRadius: 100,
          background: 'var(--tangerine)', color: '#fff',
          fontSize: 16, fontWeight: 600, textDecoration: 'none',
          boxShadow: '0 2px 2px 0 rgba(255,255,255,0.2) inset, 0 -2px 2px 0 rgba(255,255,255,0.2) inset, 0 4px 43px 0 rgba(0,0,0,0.06)',
          animation: 'pulse-orange 2s infinite',
        }}>
          免费开始 →
        </Link>
      </div>
    </section>
  );
}
