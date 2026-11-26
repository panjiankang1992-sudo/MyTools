export interface ArchiveEngine {
  run(id: string, source: string, output: string, password: string, extract: boolean, selected: number[], version: number): Promise<string>;
  status(id: string, cancel: boolean): string;
}
declare const archiveEngine: ArchiveEngine;
export default archiveEngine;
