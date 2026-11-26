export type NewsEvidenceStatus = 'AVAILABLE' | 'RETENTION_EXPIRED' | 'UNAVAILABLE';

export interface NewsEvidenceItem {
  articleId: string;
  status: NewsEvidenceStatus;
  expiresAt: string | null;
}

export interface NewsEvidenceSnapshot {
  editionId: string;
  checkedAt: string;
  items: NewsEvidenceItem[];
}

export interface NewsEvidenceApi {
  inspect: (id: string) => Promise<NewsEvidenceSnapshot>;
}
