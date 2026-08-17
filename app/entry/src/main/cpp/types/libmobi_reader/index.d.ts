export interface MobiParseResult {
  title: string
  content: string
  version: number
  encoding: number
  sectionTitles: string[]
  resources: Record<string, string>
  coverUri: string
}

export interface MobiReaderNative {
  parse(path: string, resourceDirectory?: string): string
}

declare const mobiReader: MobiReaderNative
export default mobiReader
