import { BookFormat } from './ReaderModels';

export interface OpdsBookEntry {
  id: string;
  title: string;
  author: string;
  summary: string;
  coverUrl: string;
  remoteCoverUrl?: string;
  acquisitionUrl: string;
  format: BookFormat;
}

export interface OpdsNavigationEntry {
  title: string;
  url: string;
}

export interface OpdsCatalog {
  title: string;
  books: OpdsBookEntry[];
  navigation: OpdsNavigationEntry[];
}
