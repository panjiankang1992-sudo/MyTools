import type { Subject, WordEntry } from '../types';

// 以下为独立整理的常见字词，仅用于验证排版，不对应任何教材或年级词表。
const chinese: WordEntry[] = [
  { id: 'zh-01', text: '\u6625\u98ce', pinyin: 'ch\u016bn f\u0113ng', meaning: '', unit: 1, kind: 'word' },
  { id: 'zh-02', text: '\u767d\u4e91', pinyin: 'b\u00e1i y\u00fan', meaning: '', unit: 1, kind: 'word' },
  { id: 'zh-03', text: '\u84dd\u5929', pinyin: 'l\u00e1n ti\u0101n', meaning: '', unit: 1, kind: 'word' },
  { id: 'zh-04', text: '\u82b1\u6735', pinyin: 'hu\u0101 du\u01d2', meaning: '', unit: 1, kind: 'word' },
  { id: 'zh-05', text: '\u7eff\u53f6', pinyin: 'l\u01dc y\u00e8', meaning: '', unit: 1, kind: 'word' },
  { id: 'zh-06', text: '\u9633\u5149', pinyin: 'y\u00e1ng gu\u0101ng', meaning: '', unit: 1, kind: 'word' },
  { id: 'zh-07', text: '\u6e05\u6668', pinyin: 'q\u012bng ch\u00e9n', meaning: '', unit: 1, kind: 'word' },
  { id: 'zh-08', text: '\u96e8\u6c34', pinyin: 'y\u01d4 shu\u01d0', meaning: '', unit: 1, kind: 'word' },
  { id: 'zh-09', text: '\u8bfe\u672c', pinyin: 'k\u00e8 b\u011bn', meaning: '', unit: 2, kind: 'word' },
  { id: 'zh-10', text: '\u94c5\u7b14', pinyin: 'qi\u0101n b\u01d0', meaning: '', unit: 2, kind: 'word' },
  { id: 'zh-11', text: '\u540c\u5b66', pinyin: 't\u00f3ng xu\u00e9', meaning: '', unit: 2, kind: 'word' },
  { id: 'zh-12', text: '\u6559\u5ba4', pinyin: 'ji\u00e0o sh\u00ec', meaning: '', unit: 2, kind: 'word' },
  { id: 'zh-13', text: '\u8ba4\u771f', pinyin: 'r\u00e8n zh\u0113n', meaning: '', unit: 2, kind: 'word' },
  { id: 'zh-14', text: '\u4e66\u5305', pinyin: 'sh\u016b b\u0101o', meaning: '', unit: 2, kind: 'word' },
  { id: 'zh-15', text: '\u8bfb\u4e66', pinyin: 'd\u00fa sh\u016b', meaning: '', unit: 2, kind: 'word' },
  { id: 'zh-16', text: '\u7ec3\u4e60', pinyin: 'li\u00e0n x\u00ed', meaning: '', unit: 2, kind: 'word' },
  { id: 'zh-17', text: '\u5c71', pinyin: 'sh\u0101n', meaning: '', unit: 3, kind: 'character' },
  { id: 'zh-18', text: '\u6c34', pinyin: 'shu\u01d0', meaning: '', unit: 3, kind: 'character' },
  { id: 'zh-19', text: '\u65e5', pinyin: 'r\u00ec', meaning: '', unit: 3, kind: 'character' },
  { id: 'zh-20', text: '\u6708', pinyin: 'yu\u00e8', meaning: '', unit: 3, kind: 'character' },
  { id: 'zh-21', text: '\u98ce', pinyin: 'f\u0113ng', meaning: '', unit: 3, kind: 'character' },
  { id: 'zh-22', text: '\u4e91', pinyin: 'y\u00fan', meaning: '', unit: 3, kind: 'character' },
  { id: 'zh-23', text: '\u706b', pinyin: 'hu\u01d2', meaning: '', unit: 3, kind: 'character' },
  { id: 'zh-24', text: '\u6728', pinyin: 'm\u00f9', meaning: '', unit: 3, kind: 'character' },
];

const english: WordEntry[] = [
  { id: 'en-01', text: 'apple', pinyin: '', meaning: '\u82f9\u679c', unit: 1, kind: 'word' },
  { id: 'en-02', text: 'pear', pinyin: '', meaning: '\u68a8', unit: 1, kind: 'word' },
  { id: 'en-03', text: 'orange', pinyin: '', meaning: '\u6a59\u5b50', unit: 1, kind: 'word' },
  { id: 'en-04', text: 'banana', pinyin: '', meaning: '\u9999\u8549', unit: 1, kind: 'word' },
  { id: 'en-05', text: 'peach', pinyin: '', meaning: '\u6843\u5b50', unit: 1, kind: 'word' },
  { id: 'en-06', text: 'grape', pinyin: '', meaning: '\u8461\u8404', unit: 1, kind: 'word' },
  { id: 'en-07', text: 'watermelon', pinyin: '', meaning: '\u897f\u74dc', unit: 1, kind: 'word' },
  { id: 'en-08', text: 'strawberry', pinyin: '', meaning: '\u8349\u8393', unit: 1, kind: 'word' },
  { id: 'en-09', text: 'book', pinyin: '', meaning: '\u4e66\u672c', unit: 2, kind: 'word' },
  { id: 'en-10', text: 'pencil', pinyin: '', meaning: '\u94c5\u7b14', unit: 2, kind: 'word' },
  { id: 'en-11', text: 'ruler', pinyin: '', meaning: '\u5c3a\u5b50', unit: 2, kind: 'word' },
  { id: 'en-12', text: 'school', pinyin: '', meaning: '\u5b66\u6821', unit: 2, kind: 'word' },
  { id: 'en-13', text: 'desk', pinyin: '', meaning: '\u4e66\u684c', unit: 2, kind: 'word' },
  { id: 'en-14', text: 'chair', pinyin: '', meaning: '\u6905\u5b50', unit: 2, kind: 'word' },
  { id: 'en-15', text: 'teacher', pinyin: '', meaning: '\u8001\u5e08', unit: 2, kind: 'word' },
  { id: 'en-16', text: 'student', pinyin: '', meaning: '\u5b66\u751f', unit: 2, kind: 'word' },
  { id: 'en-17', text: 'good morning', pinyin: '', meaning: '\u65e9\u4e0a\u597d', unit: 3, kind: 'phrase' },
  { id: 'en-18', text: 'thank you', pinyin: '', meaning: '\u8c22\u8c22\u4f60', unit: 3, kind: 'phrase' },
  { id: 'en-19', text: 'blue', pinyin: '', meaning: '\u84dd\u8272', unit: 3, kind: 'word' },
  { id: 'en-20', text: 'green', pinyin: '', meaning: '\u7eff\u8272', unit: 3, kind: 'word' },
  { id: 'en-21', text: 'family', pinyin: '', meaning: '\u5bb6\u5ead', unit: 3, kind: 'word' },
  { id: 'en-22', text: 'mother', pinyin: '', meaning: '\u5988\u5988', unit: 3, kind: 'word' },
  { id: 'en-23', text: 'father', pinyin: '', meaning: '\u7238\u7238', unit: 3, kind: 'word' },
  { id: 'en-24', text: 'friend', pinyin: '', meaning: '\u670b\u53cb', unit: 3, kind: 'word' },
];

/** 返回原创排版示例的副本，不将年级选择伪装为已核验教材覆盖。 */
export function sampleEntries(subject: Subject): WordEntry[] {
  return (subject === 'chinese' ? chinese : english).map((entry) => ({ ...entry }));
}
