import { ChapterAdaptationInput, ChapterAdaptationKind } from './ChapterAdaptationModels';
import { ChapterAdaptationPolicy } from './ChapterAdaptationPolicy';

/** 安全存储端口不要求登录令牌，也不保存小说正文。 */
export interface AdaptationPendingStorage {
  load(): Promise<string | undefined>;
  save(value: string): Promise<void>;
}
export interface AdaptationPendingRecord {
  scope: string;
  shelf: string;
  chapter: string;
  kind: ChapterAdaptationKind;
  trigger: string;
  input: ChapterAdaptationInput;
}

/** 未知提交写前日志；相同章节记录只有相同 key 的确认才能删除，禁止静默覆盖。 */
export class AdaptationPendingJournal {
  private static tail: Promise<void> = Promise.resolve();
  private readonly storage: AdaptationPendingStorage;
  private readonly scope: string;
  private readonly policy: ChapterAdaptationPolicy = new ChapterAdaptationPolicy();
  constructor(scope: string, storage: AdaptationPendingStorage) {
    if (!/^account:v2:[a-f0-9]{64}$/.test(scope)) throw new Error('ADAPTATION_ACCOUNT_INVALID');
    this.scope = scope; this.storage = storage;
  }
  /** 恢复只读记录，不能自动发起创作操作。 */
  async read(shelf: string, chapter: string): Promise<AdaptationPendingRecord | undefined> {
    let result: AdaptationPendingRecord | undefined = undefined;
    await this.serial(async (): Promise<void> => {
      result = (await this.records()).find((item: AdaptationPendingRecord) => item.shelf === shelf && item.chapter === chapter);
    });
    return result;
  }
  /** 首次发送前保存精确输入，存储失败时调用者必须停止发送。 */
  async put(value: AdaptationPendingRecord): Promise<void> {
    const checked = this.checked(value);
    await this.serial(async (): Promise<void> => {
      const records = await this.records(); const existing = records.find((item: AdaptationPendingRecord) => item.chapter === checked.chapter);
      if (existing !== undefined) {
        if (JSON.stringify(existing) !== JSON.stringify(checked)) throw new Error('ADAPTATION_PENDING_CONFLICT');
        return;
      }
      if (records.length >= 8 || records.some((item: AdaptationPendingRecord) => item.input.idempotencyKey === checked.input.idempotencyKey))
        throw new Error('ADAPTATION_PENDING_CAPACITY');
      records.push(checked); await this.write(records);
    });
  }
  /** 明确 202 或入口拒绝后清理同一 key，迟到确认不能删除更新的记录。 */
  async remove(chapter: string, key: string): Promise<void> {
    await this.serial(async (): Promise<void> => {
      const records = await this.records();
      const retained = records.filter((item: AdaptationPendingRecord) => item.chapter !== chapter || item.input.idempotencyKey !== key);
      if (retained.length !== records.length) await this.write(retained);
    });
  }
  private async serial(action: () => Promise<void>): Promise<void> {
    const current = AdaptationPendingJournal.tail.then(action);
    AdaptationPendingJournal.tail = current.catch((): void => {});
    await current;
  }
  private async records(): Promise<AdaptationPendingRecord[]> {
    const raw = await this.storage.load(); if (raw === undefined) return [];
    if (raw.length > 64000) throw new Error('ADAPTATION_PENDING_INVALID');
    let values: AdaptationPendingRecord[];
    try { values = JSON.parse(raw) as AdaptationPendingRecord[]; } catch (_) { throw new Error('ADAPTATION_PENDING_INVALID'); }
    if (!Array.isArray(values) || values.length > 8) throw new Error('ADAPTATION_PENDING_INVALID');
    const records = values.map((value: AdaptationPendingRecord) => this.checked(value));
    const chapters: string[] = []; const keys: string[] = [];
    records.forEach((item: AdaptationPendingRecord) => {
      if (chapters.includes(item.chapter) || keys.includes(item.input.idempotencyKey)) throw new Error('ADAPTATION_PENDING_INVALID');
      chapters.push(item.chapter); keys.push(item.input.idempotencyKey);
    });
    return records;
  }
  private checked(value: AdaptationPendingRecord): AdaptationPendingRecord {
    if (value === null || typeof value !== 'object' || value.scope !== this.scope ||
      !['INITIAL', 'OPTIMIZE', 'REGENERATE'].includes(value.kind) || value.input === undefined) throw new Error('ADAPTATION_PENDING_INVALID');
    const trigger = value.kind === 'INITIAL' ? '' : this.policy.id(value.trigger);
    if (value.kind === 'INITIAL' && value.trigger !== '') throw new Error('ADAPTATION_PENDING_INVALID');
    return { scope: this.scope, shelf: this.policy.id(value.shelf), chapter: this.policy.id(value.chapter),
      kind: value.kind, trigger: trigger, input: this.policy.input(value.input) };
  }
  private async write(records: AdaptationPendingRecord[]): Promise<void> { await this.storage.save(JSON.stringify(records)); }
}
