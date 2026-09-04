/** 与后端 ImConstants 对齐的渠道常量 */
export const CHANNEL_FEISHU = 'feishu';
export const CHANNEL_DINGTALK = 'dingtalk';
export const CHANNEL_WECOM = 'wecom';
export const CHANNEL_WECHAT = 'wechat';

/** 会话类型：云文档评论（文档问答/复盘） */
export const CONV_DOC = 'doc';

/** 文档内容截断上限（字符），超长截断避免撑爆 AI 上下文 */
export const DOC_MAX_CHARS = 6000;

/** 扫码二维码有效期（秒），与飞书官方一致 */
export const QR_EXPIRE_SECONDS = 600;

/** 隐私告知文案（附在开场白末尾，满足合规） */
export const PRIVACY_NOTICE = '（为持续优化分身，你的对话会被记录到平台）';
