export const start: (accepted: (id: number) => void) => number;
export const read: (id: number) => Promise<ArrayBuffer>;
export const write: (id: number, data: ArrayBuffer) => Promise<void>;
export const end: (id: number) => void;
export const stop: () => void;
