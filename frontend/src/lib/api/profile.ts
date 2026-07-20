import { apiClient } from './client';

export interface SkillProfile {
  personality?: string; speakingStyle?: string; background?: string;
  commonPhrases?: string; knowledgeDomains?: string;
  communicationPreferences?: string; weaknessNotes?: string; extraContext?: string;
}

export function getProfile(skillId: string): Promise<SkillProfile> {
  return apiClient(`/admin/skills/${skillId}/profile`);
}

export function saveProfile(skillId: string, data: Partial<SkillProfile>): Promise<SkillProfile> {
  return apiClient(`/admin/skills/${skillId}/profile`, { method: 'PUT', body: JSON.stringify(data) });
}
