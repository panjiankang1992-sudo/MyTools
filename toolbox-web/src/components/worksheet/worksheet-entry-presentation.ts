import type { InputToken } from '../../domain/worksheet-input';
import {
  englishTextForToken, isValidChineseMeaning, isValidEnglishText, isValidPinyinSegments
} from '../../domain/worksheet-processing';

/** 自动结果的展示状态；结构有效的机器建议可直接用于练习。 */
export interface WorksheetEntryPresentation {
  type: 'default' | 'warning' | 'success';
  label: string;
  missing: boolean;
  automatic: boolean;
}

/** 取得可编辑的英文值，未翻译的中文原文绝不显示为英文。 */
export function englishInputValue(token: InputToken): string {
  return englishTextForToken(token);
}

/** 根据真实字段完整性显示提示，不把机器建议标成必需人工确认。 */
export function worksheetEntryPresentation(token: InputToken, subject: 'chinese' | 'english'): WorksheetEntryPresentation {
  if (subject === 'chinese') {
    if (token.pinyinState === 'missing' || !isValidPinyinSegments(token.text, token.pinyinSegments)) {
      return { type: 'warning', label: '拼音待补全', missing: true, automatic: false };
    }
    if (token.pinyinState === 'suggested') return { type: 'default', label: '自动生成，可修改', missing: false, automatic: true };
    return { type: 'success', label: '可修改', missing: false, automatic: false };
  }
  const english = englishInputValue(token);
  if (!isValidEnglishText(english) || token.englishState === 'missing') {
    return { type: 'warning', label: '英文待补全', missing: true, automatic: false };
  }
  if (!isValidChineseMeaning(token.meaning) || token.meaningState === 'missing') {
    return { type: 'warning', label: '中文待补全', missing: true, automatic: false };
  }
  if (token.englishState === 'suggested' || token.meaningState === 'suggested') {
    return { type: 'default', label: '自动生成，可修改', missing: false, automatic: true };
  }
  return { type: 'success', label: '可修改', missing: false, automatic: false };
}
