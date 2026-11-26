import { computed } from 'vue';
import { getLocale } from './index';

const en = {
  title: "Delivery",
  intro: "Choose verified destinations for future daily runs. Preview editions are never sent.",
  settings: "Delivery targets",
  history: "Delivery history",
  refresh: "Refresh delivery status",
  disabled: "Sending is disabled on the server. Saved targets and history remain visible; you can remove targets or request a stop.",
  binding: "Destinations must first be verified and bound by an administrator. This page only selects your verified destinations.",
  qq: "QQ proactive scheduled messages are unavailable on the official platform. Email and Feishu are supported.",
  emptyDestinations: "No verified destinations available.",
  emptyTargets: "No delivery targets selected.",
  choose: "Choose a destination",
  add: "Add target",
  remove: "Remove",
  enabled: "Receive daily editions",
  full: "Full edition",
  brief: "Brief edition",
  mode: "Content format",
  modeHint: "Full: up to 20 headlines and 10 available details per category. Brief: all headlines and available details for ranks 1\u20133. Long messages are split in order.",
  effective: "New targets and format changes apply to the next daily run. Removing or disabling a target also requests a stop for its unfinished deliveries.",
  save: "Save delivery settings",
  saved: "Delivery settings saved.",
  reload: "Reload saved delivery settings",
  discard: "Reloading replaces your unsaved delivery settings. Continue?",
  conflict: "Settings changed elsewhere. Your draft is preserved; reload the saved version before saving again.",
  unavailable: "Could not load or update delivery data. Retry or refresh to confirm the current result.",
  targetInvalid: "The destination is disabled, unavailable, or unsupported. Refresh destinations and adjust your selection.",
  stateChanged: "This delivery has changed or expired. Refresh its status before another action.",
  emptyHistory: "No deliveries yet. Only future daily runs capture enabled targets.",
  historyHint: "Latest 50 destination batches. Provider acceptance is not confirmation of inbox delivery or reading.",
  read: "Read edition",
  stop: "Stop remaining parts",
  stopConfirm: "Request a stop? Parts already accepted cannot be recalled; an in-flight result may remain unknown.",
  stopPending: "Stop requested; parts already started retain their actual outcomes.",
  part: "Part",
  generation: "Attempt generation",
  expires: "Expires",
  retry: "Retry failed part",
  retryUnknown: "Review retry risk",
  retryConfirm: "Create a new attempt for this failed part?",
  duplicateRisk: "The provider may already have accepted this part. Retrying may send duplicate content. Confirm only if you accept that risk.",
  acceptRisk: "Accept risk and retry",
  confirm: "Confirm",
  keep: "Keep current state",
  retryPending: "Retry recorded; waiting for dispatch.",
  retryUnknownRequest: "Retry submission is unconfirmed. Resume the same request or refresh to reconcile.",
  resume: "Resume original retry",
  redrive: "Resume original part",
  redriveConfirm: "After fixing the destination or configuration, resume the original part with the same identity and content?",
  missingDestination: "Unlisted destination",
  destinationDisabled: "Destination disabled",
  loading: "Loading delivery data\u2026"
};

const enStatus: Record<string, string> = {
  WAITING_EDITION: "Waiting for edition",
  QUEUED: "Queued for delivery",
  PENDING: "Pending",
  SUBMITTING: "Submitting",
  ACCEPTED: "Queued by messaging service",
  SENDING: "Sending",
  PROVIDER_ACCEPTED: "Provider accepted",
  FAILED: "Failed",
  UNKNOWN: "Result unknown",
  CANCELLED: "Cancelled",
  EXPIRED: "Expired",
  REJECTED: "Needs configuration repair",
  IN_PROGRESS: "In progress",
  PARTIAL: "Mixed part results"
};

const zh = {
  title: "\u6d88\u606f\u6295\u9012",
  intro: "\u9009\u62e9\u5df2\u9a8c\u8bc1\u76ee\u6807\uff0c\u7528\u4e8e\u4e4b\u540e\u7684\u6bcf\u65e5\u4efb\u52a1\u3002\u9884\u89c8\u65e5\u62a5\u4e0d\u4f1a\u53d1\u9001\u3002",
  settings: "\u63a5\u6536\u914d\u7f6e",
  history: "\u6295\u9012\u8bb0\u5f55",
  refresh: "\u5237\u65b0\u6295\u9012\u72b6\u6001",
  disabled: "\u670d\u52a1\u7aef\u5c1a\u672a\u542f\u7528\u6295\u9012\u3002\u53ef\u67e5\u770b\u5df2\u6709\u914d\u7f6e\u548c\u8bb0\u5f55\u3001\u79fb\u9664\u63a5\u6536\u76ee\u6807\u6216\u8bf7\u6c42\u505c\u6b62\u3002",
  binding: "\u63a5\u6536\u76ee\u6807\u9700\u5148\u7531\u7ba1\u7406\u5458\u6838\u5b9e\u5e76\u7ed1\u5b9a\uff1b\u6b64\u5904\u4ec5\u9009\u62e9\u5c5e\u4e8e\u4f60\u7684\u5df2\u9a8c\u8bc1\u76ee\u6807\u3002",
  qq: "QQ \u5b98\u65b9\u5e73\u53f0\u5df2\u505c\u6b62\u4e3b\u52a8\u6d88\u606f\u63a8\u9001\uff1b\u5b9a\u65f6\u6295\u9012\u652f\u6301\u90ae\u4ef6\u548c\u98de\u4e66\u3002",
  emptyDestinations: "\u6682\u65e0\u53ef\u7528\u7684\u5df2\u9a8c\u8bc1\u76ee\u6807\u3002",
  emptyTargets: "\u5c1a\u672a\u9009\u62e9\u63a5\u6536\u76ee\u6807\u3002",
  choose: "\u9009\u62e9\u63a5\u6536\u76ee\u6807",
  add: "\u6dfb\u52a0\u76ee\u6807",
  remove: "\u79fb\u9664",
  enabled: "\u63a5\u6536\u6bcf\u65e5\u901f\u62a5",
  full: "\u5b8c\u6574\u901f\u62a5",
  brief: "\u7cbe\u7b80\u901f\u62a5",
  mode: "\u5185\u5bb9\u5f62\u5f0f",
  modeHint: "\u5b8c\u6574\uff1a\u6bcf\u7c7b\u6700\u591a 20 \u6761\u6807\u9898\u53ca\u524d 10 \u6761\u53ef\u7528\u8be6\u60c5\u3002\u7cbe\u7b80\uff1a\u4fdd\u7559\u5168\u90e8\u6807\u9898\u53ca\u524d 3 \u6761\u53ef\u7528\u8be6\u60c5\u3002\u957f\u6d88\u606f\u6309\u987a\u5e8f\u5206\u7247\u3002",
  effective: "\u65b0\u589e\u76ee\u6807\u53ca\u5185\u5bb9\u5f62\u5f0f\u4ece\u4e0b\u4e00\u6b21\u6bcf\u65e5\u4efb\u52a1\u751f\u6548\uff1b\u79fb\u9664\u6216\u505c\u7528\u76ee\u6807\u4e5f\u4f1a\u8bf7\u6c42\u505c\u6b62\u5176\u5c1a\u672a\u5b8c\u6210\u7684\u6295\u9012\u3002",
  save: "\u4fdd\u5b58\u63a5\u6536\u914d\u7f6e",
  saved: "\u63a5\u6536\u914d\u7f6e\u5df2\u4fdd\u5b58\u3002",
  reload: "\u91cd\u65b0\u8f7d\u5165\u63a5\u6536\u914d\u7f6e",
  discard: "\u91cd\u65b0\u8f7d\u5165\u4f1a\u66ff\u6362\u5c1a\u672a\u4fdd\u5b58\u7684\u63a5\u6536\u914d\u7f6e\uff0c\u662f\u5426\u7ee7\u7eed\uff1f",
  conflict: "\u63a5\u6536\u914d\u7f6e\u5df2\u5728\u5176\u4ed6\u4f4d\u7f6e\u53d8\u66f4\u3002\u8349\u7a3f\u5df2\u4fdd\u7559\uff0c\u8bf7\u91cd\u65b0\u8f7d\u5165\u540e\u518d\u4fdd\u5b58\u3002",
  unavailable: "\u65e0\u6cd5\u8bfb\u53d6\u6216\u66f4\u65b0\u6295\u9012\u6570\u636e\u3002\u8bf7\u91cd\u8bd5\u6216\u5237\u65b0\u4ee5\u786e\u8ba4\u5f53\u524d\u7ed3\u679c\u3002",
  targetInvalid: "\u76ee\u6807\u5df2\u505c\u7528\u3001\u4e0d\u53ef\u7528\u6216\u6e20\u9053\u4e0d\u53d7\u652f\u6301\uff0c\u8bf7\u5237\u65b0\u76ee\u6807\u540e\u8c03\u6574\u9009\u62e9\u3002",
  stateChanged: "\u6295\u9012\u72b6\u6001\u5df2\u53d8\u5316\u6216\u5df2\u8fc7\u671f\uff0c\u8bf7\u5237\u65b0\u72b6\u6001\u540e\u518d\u64cd\u4f5c\u3002",
  emptyHistory: "\u6682\u65e0\u6295\u9012\u8bb0\u5f55\u3002\u4e4b\u540e\u7684\u6bcf\u65e5\u4efb\u52a1\u624d\u4f1a\u8bb0\u5f55\u5df2\u542f\u7528\u7684\u63a5\u6536\u76ee\u6807\u3002",
  historyHint: "\u5c55\u793a\u6700\u8fd1 50 \u4e2a\u76ee\u6807\u6279\u6b21\u3002\u63d0\u4f9b\u65b9\u5df2\u63a5\u6536\u4e0d\u4ee3\u8868\u5df2\u8fdb\u5165\u6536\u4ef6\u7bb1\u6216\u5df2\u8bfb\u3002",
  read: "\u9605\u8bfb\u65e5\u62a5",
  stop: "\u505c\u6b62\u5269\u4f59\u7247\u6bb5",
  stopConfirm: "\u8bf7\u6c42\u505c\u6b62\u5269\u4f59\u7247\u6bb5\uff1f\u5df2\u63a5\u6536\u5185\u5bb9\u65e0\u6cd5\u64a4\u56de\uff0c\u6b63\u5728\u53d1\u9001\u7684\u7247\u6bb5\u4ecd\u53ef\u80fd\u7ed3\u679c\u672a\u77e5\u3002",
  stopPending: "\u5df2\u8bf7\u6c42\u505c\u6b62\uff1b\u5df2\u5f00\u59cb\u53d1\u9001\u7684\u7247\u6bb5\u4ee5\u5b9e\u9645\u7ed3\u679c\u4e3a\u51c6\u3002",
  part: "\u7247\u6bb5",
  generation: "\u53d1\u9001\u4ee3\u6570",
  expires: "\u6709\u6548\u671f\u81f3",
  retry: "\u91cd\u8bd5\u5931\u8d25\u7247\u6bb5",
  retryUnknown: "\u91cd\u8bd5\u672a\u77e5\u7ed3\u679c",
  retryConfirm: "\u4e3a\u8fd9\u4e2a\u5931\u8d25\u7247\u6bb5\u521b\u5efa\u4e00\u6b21\u65b0\u5c1d\u8bd5\uff1f",
  duplicateRisk: "\u63d0\u4f9b\u65b9\u53ef\u80fd\u5df2\u7ecf\u63a5\u6536\u6b64\u7247\u6bb5\u3002\u518d\u6b21\u53d1\u9001\u53ef\u80fd\u9020\u6210\u91cd\u590d\u5185\u5bb9\uff1b\u4ec5\u5728\u63a5\u53d7\u8be5\u98ce\u9669\u65f6\u786e\u8ba4\u3002",
  acceptRisk: "\u63a5\u53d7\u91cd\u590d\u98ce\u9669\u5e76\u91cd\u8bd5",
  confirm: "\u786e\u8ba4",
  keep: "\u4fdd\u6301\u73b0\u72b6",
  retryPending: "\u91cd\u8bd5\u5df2\u8bb0\u5f55\uff0c\u7b49\u5f85\u6267\u884c\u3002",
  retryUnknownRequest: "\u91cd\u8bd5\u63d0\u4ea4\u7ed3\u679c\u5c1a\u672a\u786e\u8ba4\u3002\u53ef\u6062\u590d\u540c\u4e00\u8bf7\u6c42\uff0c\u6216\u5237\u65b0\u6838\u5bf9\u72b6\u6001\u3002",
  resume: "\u6062\u590d\u539f\u91cd\u8bd5\u8bf7\u6c42",
  redrive: "\u6062\u590d\u539f\u7247\u6bb5",
  redriveConfirm: "\u4fee\u590d\u76ee\u6807\u6216\u914d\u7f6e\u540e\uff0c\u4f7f\u7528\u539f\u6807\u8bc6\u548c\u5185\u5bb9\u6062\u590d\u8be5\u7247\u6bb5\uff1f",
  missingDestination: "\u672a\u5217\u51fa\u7684\u63a5\u6536\u76ee\u6807",
  destinationDisabled: "\u76ee\u6807\u5df2\u505c\u7528",
  loading: "\u6b63\u5728\u52a0\u8f7d\u6295\u9012\u6570\u636e\u2026"
};

const zhStatus: Record<string, string> = {
  WAITING_EDITION: "\u7b49\u5f85\u65e5\u62a5\u751f\u6210",
  QUEUED: "\u7b49\u5f85\u6295\u9012",
  PENDING: "\u7b49\u5f85\u5904\u7406",
  SUBMITTING: "\u6b63\u5728\u63d0\u4ea4",
  ACCEPTED: "\u6d88\u606f\u670d\u52a1\u5df2\u6392\u961f",
  SENDING: "\u53d1\u9001\u4e2d",
  PROVIDER_ACCEPTED: "\u63d0\u4f9b\u65b9\u5df2\u63a5\u6536",
  FAILED: "\u5931\u8d25",
  UNKNOWN: "\u7ed3\u679c\u672a\u77e5",
  CANCELLED: "\u5df2\u53d6\u6d88",
  EXPIRED: "\u5df2\u8fc7\u671f",
  REJECTED: "\u9700\u8981\u4fee\u590d\u914d\u7f6e",
  IN_PROGRESS: "\u8fdb\u884c\u4e2d",
  PARTIAL: "\u90e8\u5206\u7247\u6bb5\u7ed3\u679c\u4e0d\u540c"
};

/** 投递文案明确区分提供方接收和最终送达。 */
export function useNewsDeliveryText() {
  const locale = computed(() => getLocale() === 'zh-CN' ? 'zh-CN' : 'en-US');
  const text = computed(() => locale.value === 'zh-CN' ? zh : en);
  const status = (value: string) => (locale.value === 'zh-CN' ? zhStatus : enStatus)[value] || value;
  const error = (code: string) => {
    if (code === 'NEWS_022') return text.value.conflict;
    if (code === 'NEWS_024') return text.value.targetInvalid;
    if (code === 'NEWS_025') return text.value.stateChanged;
    if (code === 'NEWS_021') return text.value.disabled;
    return text.value.unavailable;
  };
  return { text, locale, status, error };
}
