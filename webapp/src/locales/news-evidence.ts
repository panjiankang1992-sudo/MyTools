import { computed } from 'vue';
import { getLocale } from './index';

/** 文案只解释本地证据保留状态，避免将可用误解为新闻核实。 */
const en = {
  "title": "Saved source evidence",
  "note": "Status describes retained local evidence. It does not verify the report or check whether the original website is online. Saved excerpts remain part of this edition.",
  "checked": "Checked",
  "expires": "Retention deadline",
  "refresh": "Refresh evidence status",
  "loading": "Checking evidence\u2026",
  "failed": "Evidence status could not be checked. You can still read the saved edition.",
  "AVAILABLE": "Evidence retained",
  "RETENTION_EXPIRED": "Retention expired",
  "UNAVAILABLE": "Evidence unavailable",
  "PENDING": "Status not checked"
};
const zh: typeof en = {
  "title": "\u6765\u6e90\u8bc1\u636e\u4fdd\u7559\u72b6\u6001",
  "note": "\u8fd9\u91cc\u663e\u793a\u672c\u5730\u6765\u6e90\u8bc1\u636e\u7684\u4fdd\u7559\u72b6\u6001\uff0c\u4e0d\u4ee3\u8868\u62a5\u9053\u5df2\u6838\u5b9e\u6216\u539f\u7f51\u9875\u4ecd\u53ef\u8bbf\u95ee\u3002\u65e5\u62a5\u4e2d\u7684\u8282\u9009\u4ecd\u4fdd\u7559\u751f\u6210\u65f6\u7684\u5185\u5bb9\u3002",
  "checked": "\u6838\u5bf9\u65f6\u95f4",
  "expires": "\u4fdd\u7559\u622a\u6b62\u65f6\u95f4",
  "refresh": "\u5237\u65b0\u8bc1\u636e\u72b6\u6001",
  "loading": "\u6b63\u5728\u6838\u5bf9\u8bc1\u636e\u2026",
  "failed": "\u6682\u65f6\u65e0\u6cd5\u6838\u5bf9\u8bc1\u636e\u72b6\u6001\uff0c\u4ecd\u53ef\u9605\u8bfb\u5df2\u4fdd\u5b58\u7684\u65e5\u62a5\u3002",
  "AVAILABLE": "\u8bc1\u636e\u5df2\u4fdd\u7559",
  "RETENTION_EXPIRED": "\u5df2\u8fc7\u4fdd\u7559\u671f\u9650",
  "UNAVAILABLE": "\u8bc1\u636e\u4e0d\u53ef\u7528",
  "PENDING": "\u72b6\u6001\u672a\u6838\u5bf9"
};

/** 跟随用户当前语言设置。 */
export function useNewsEvidenceText() {
  return computed(() => getLocale() === 'zh-CN' ? zh : en);
}
