export interface AppMarketItem {
  id: string;
  name: string;
  type: string;
  version: string;
  summary: string;
  status: string;
  author: string;
  updatedAt: string;
}

export interface AppMarketPage {
  items: AppMarketItem[];
  total: number;
  page: number;
  pageSize: number;
}

export interface AppMarketDetail extends AppMarketItem {
  content: string;
  fileName: string;
  fileSize: number;
  fileType: string;
}
