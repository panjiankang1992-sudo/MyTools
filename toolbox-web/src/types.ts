export type Subject = 'chinese' | 'english';
export type WorksheetMode = 'chinese-dictation' | 'chinese-pinyin' | 'english-dictation' | 'english-meaning';
export interface WordEntry {
  id: string;
  text: string;
  pinyin: string;
  meaning: string;
  unit: number;
  kind: 'character' | 'word' | 'phrase';
}
export interface WorksheetOptions {
  mode: WorksheetMode;
  title: string;
  grade: number;
  gridMm: number;
  repetitions: number;
  answer: boolean;
  /** 统一处理层已经完成选题与重复展开，渲染器只投影最终题序。 */
  expandedQuestions?: boolean;
}
export interface WorksheetArtifact {
  bytes: Uint8Array;
  pageCount: number;
  filename: string;
  url: string;
}
export type ToolCategory = 'pdf' | 'copybook' | 'words';
