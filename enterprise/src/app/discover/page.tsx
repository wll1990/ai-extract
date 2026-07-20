import { Navbar } from '@/components/Navbar';
import { Footer } from '@/components/Footer';
import { SearchBar } from '@/components/discover/SearchBar';
import { TopicFilters } from '@/components/discover/TopicFilters';
import { ExpertRow } from '@/components/discover/ExpertRow';
import { fetchPublicSkills, type PublicSkillInfo } from '@/lib/api/skill';

interface DiscoverPageProps {
  searchParams: { q?: string; topic?: string };
}

export default async function DiscoverPage({ searchParams }: DiscoverPageProps) {
  const search = searchParams.q || '';
  const topic = searchParams.topic || '';

  let skills: PublicSkillInfo[] = [];
  let error: string | null = null;
  let allTopics: string[] = [];

  try {
    // 服务端 fetch — 首屏 SSR，无需客户端再请求
    const allSkills = await fetchPublicSkills();
    allTopics = [...new Set(allSkills.flatMap(s => s.tags || []))].sort();

    // 服务端过滤
    skills = allSkills.filter(s => {
      if (topic && !(s.tags || []).includes(topic)) return false;
      if (search) {
        const q = search.toLowerCase();
        const name = (s.displayName || s.ownerName || '').toLowerCase();
        const title = (s.ownerTitle || '').toLowerCase();
        if (!name.includes(q) && !title.includes(q)) return false;
      }
      return true;
    });
  } catch {
    error = '加载专家列表失败，请稍后重试';
  }

  return (
    <main style={{ background: 'var(--s1)', color: 'var(--fg-high)', minHeight: '100vh' }}>
      <Navbar />

      <section style={{ maxWidth: 960, margin: '0 auto', padding: '60px 40px' }}>
        <h1 style={{ fontSize: 26, fontWeight: 800, marginBottom: 6 }}>专家知识库</h1>
        <p style={{ color: 'var(--fg-mid)', fontSize: 14, marginBottom: 20 }}>
          找到你需要的那位专家，随时请教、对练学习
        </p>

        <SearchBar />
        <TopicFilters topics={allTopics} activeTopic={topic} />

        {error ? (
          <div style={{
            textAlign: 'center', padding: '60px 20px', color: 'var(--fg-mid)',
          }}>
            <p>{error}</p>
          </div>
        ) : skills.length === 0 ? (
          <div style={{
            textAlign: 'center', padding: '60px 20px', color: 'var(--fg-low)',
          }}>
            <div style={{ fontSize: 48, marginBottom: 16 }}>🔍</div>
            <p style={{ fontSize: 14 }}>暂无匹配的专家</p>
            <p style={{ fontSize: 12, marginTop: 4 }}>
              {search || topic ? '试试其他搜索词或话题' : '还没有已发布的分身'}
            </p>
          </div>
        ) : (
          <div>
            {skills.map((skill, i) => (
              <ExpertRow key={skill.id} skill={skill} index={i} />
            ))}
          </div>
        )}
      </section>

      <Footer />
    </main>
  );
}
