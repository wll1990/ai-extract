import { Navbar } from '@/components/Navbar';
import { Footer } from '@/components/Footer';
import { Hero } from '@/components/landing/Hero';
import { StatsBar } from '@/components/landing/StatsBar';
import { Features } from '@/components/landing/Features';
import { Flywheel } from '@/components/landing/Flywheel';
import { Moat } from '@/components/landing/Moat';
import { RoiComparison } from '@/components/landing/RoiComparison';
import { UseCases } from '@/components/landing/UseCases';
import { CtaSection } from '@/components/landing/CtaSection';

/**
 * MindForge 落地页 — System B 首页
 *
 * Server Component：8 个 section 中只有 StatsBar 和 Flywheel 是 Client Component。
 * 其余全部服务端渲染，保证 SEO 和首屏性能。
 */
export default function LandingPage() {
  return (
    <main style={{ background: 'var(--s1)', color: 'var(--fg-high)' }}>
      <Navbar />
      <Hero />
      <div style={{ padding: '0 40px 80px' }}>
        <StatsBar />
      </div>
      <Features />
      <Flywheel />
      <Moat />
      <RoiComparison />
      <UseCases />
      <CtaSection />
      <Footer />
    </main>
  );
}
