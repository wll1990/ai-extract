// ═══ Chat Components ═══
export { PracticeView, type PracticeViewProps, type PracticeMessage, type PracticeEval, type PracticeSource, type PracticeData } from './components/PracticeView';
export { TrustBadge } from './components/TrustBadge';
export { DefaultAvatar } from './components/DefaultAvatar';
export { PortraitCard } from './components/PortraitCard';
export { ChatAvatar } from './components/ChatAvatar';
export { ChatComposer, type ChatComposerProps } from './chat/ChatComposer';
export { ThinkingCard, type ThinkingCardProps } from './chat/ThinkingCard';
export { QuickReplies, type QuickRepliesProps } from './chat/QuickReplies';
export { WaveThinking, type WaveThinkingProps } from './chat/WaveThinking';
export { RadarReveal, type RadarRevealProps, type Dimension } from './chat/RadarReveal';

// ═══ API ═══
export { configureApi, apiClient, getApiBaseUrl, type ApiConfig } from './api/client';
export { connectSse, type SseCallbacks } from './api/sse';
export {
  fetchPublicStats, fetchPublicSkills, fetchSkillDetail,
  fetchRecommendedQuestions, chat, listConversations,
  getConversationMessages, deleteConversation, submitFeedback,
  fetchSceneTags, fetchPracticeScenes, startPractice,
  respondPractice, evaluatePractice, evaluatePracticeRound,
  fetchPracticeTrend,
  type SceneTag, type PublicSkillInfo, type SkillDetail,
  type ConversationItem, type ConversationMessage,
  type PracticeSceneData, type PracticeStartData, type RoundEval,
} from './api/skill';

// ═══ Hooks ═══
export {
  usePracticeFlow, practiceReducer,
  type PracticeFlowInputs,
} from './hooks/usePracticeFlow';
export { resolveConfig, type ChatConfig } from './hooks/types';

// ═══ Lib (constants) ═══
export { TALK_NAME_CARD, MODE_GUIDE } from './lib/guide-text';
