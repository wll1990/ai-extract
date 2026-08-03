import Link from 'next/link';

export function Hero() {
  return (
    <section
      style={{
        position: 'relative', textAlign: 'center',
        padding: '80px 40px 90px', overflow: 'hidden',
      }}
    >
      {/* Background glow */}
      <div style={{
        position: 'absolute', top: '-30%', left: '50%',
        transform: 'translateX(-50%)', width: 900, height: 900,
        background: 'radial-gradient(circle, rgba(255,92,0,0.07) 0%, rgba(37,99,235,0.03) 40%, transparent 70%)',
        borderRadius: '50%', pointerEvents: 'none',
      }} />

      <div style={{ position: 'relative', zIndex: 1 }}>
        <div style={{
          display: 'inline-flex', alignItems: 'center', gap: 6,
          padding: '7px 18px', borderRadius: 100,
          background: 'rgba(255,92,0,0.07)', color: 'var(--tangerine)',
          fontSize: 12, fontWeight: 600, marginBottom: 32,
        }}>
          ✨ AI 驱动 · 经验萃取 · 专家分身
        </div>

        <h1 style={{
          fontSize: 'clamp(44px, 8vw, 68px)', fontWeight: 900,
          letterSpacing: '-0.05em', lineHeight: 1.06, marginBottom: 20,
          color: 'var(--fg-high)',
        }}>
          顶级专家的经验，
          <br />
          现在成为团队的
          <span style={{ color: 'var(--tangerine)' }}>AI 分身</span>
        </h1>

        <p style={{
          fontSize: 18, color: 'var(--fg-mid)', maxWidth: 540,
          margin: '0 auto 36px', lineHeight: 1.7,
        }}>
          上传对话录音或文档，AI 自动萃取销售经验颗粒。
          随时对话、实战对练——把一个人的顶级能力变成团队的共同战斗力。
        </p>

        <div style={{ display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' }}>
          <Link href="/discover" className="btn btn-primary btn-xl">发现专家</Link>
          <Link href="/register" prefetch={false} className="btn btn-ghost btn-xl">成为专家</Link>
        </div>
      </div>
    </section>
  );
}
