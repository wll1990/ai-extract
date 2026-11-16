import { ChannelManager as FeishuManager } from './channels/feishu.js';
import { WecomManager } from './channels/wecom.js';
import { DingtalkManager } from './channels/dingtalk.js';

/**
 * 渠道适配器注册表：一个桥进程同时承载飞书 / 企微 / 钉钉。
 * 与后端 im_channel.channel_type 一一对应。
 */
export function createManagers(bridge) {
  return {
    feishu: new FeishuManager(bridge),
    wecom: new WecomManager(bridge),
    dingtalk: new DingtalkManager(bridge),
  };
}
