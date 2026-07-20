import { fetchSkillDetail, type SkillDetail } from '@/lib/api/skill';
import { ChatView } from '@/components/chat/ChatView';
import { notFound } from 'next/navigation';

interface ChatPageProps {
  params: { skillId: string };
}

async function getSkill(skillId: string): Promise<SkillDetail | null> {
  try {
    return await fetchSkillDetail(skillId);
  } catch {
    return null;
  }
}

export default async function ChatPage({ params }: ChatPageProps) {
  const skill = await getSkill(params.skillId);

  if (!skill) {
    notFound();
  }

  return <ChatView skill={skill} />;
}
