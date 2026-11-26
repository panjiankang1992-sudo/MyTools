export interface QrEngine {
  encode(text: string): Promise<ArrayBuffer>;
  decode(pixels: ArrayBuffer, width: number, height: number): Promise<string[]>;
}
declare const qrEngine: QrEngine;
export default qrEngine;
