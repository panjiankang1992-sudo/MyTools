import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { AudiobookBookAnalysis, AudiobookExport, AudiobookExportDownloadDescriptor, AudiobookGeneration,
  AudiobookPlaybackDescriptor, AudiobookPlaybackManifest, AudiobookVoicePreviewDescriptor,
  CreateAudiobookGeneration, CreateAudiobookVoiceRevision, CreateManagedEbookImport, ManagedEbookImport,
  AudiobookVoicePlan, AudiobookVoiceCatalog, AudiobookVoiceCandidate, CreateAudiobookExport,
  CreateAudiobookSpeakerRevision, AudiobookPronunciationDictionary,
  CreateAudiobookPronunciationRevision } from './AudiobookModels';
import { AudiobookResponseNormalizer } from './AudiobookResponseNormalizer';

/**
 * 访问有声书生成、版本化清单与短期章节播放票据的 Gateway 客户端。
 */
export class AudiobookApi {
  private readonly baseUrl: string;
  private readonly client: AuthorizedApiClient;
  private readonly normalizer: AudiobookResponseNormalizer = new AudiobookResponseNormalizer();

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.baseUrl = authManager.normalizeBusinessBaseUrl(baseUrl);
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /** 创建或复用一次有声书整书生成。 */
  async create(request: CreateAudiobookGeneration): Promise<AudiobookGeneration> {
    this.validateCreate(request);
    const envelope = await this.client.postJson('/api/app/v1/reader/audiobook-generations', request);
    return this.normalizer.generation(envelope.data);
  }

  /** 将当前用户拥有的受管 TXT 或 EPUB 媒体异步映射为 Reader 电子书资产。 */
  async createManagedImport(request: CreateManagedEbookImport): Promise<ManagedEbookImport> {
    this.validateManagedImport(request);
    const envelope = await this.client.postJson('/api/app/v1/reader/managed-ebook-imports', request);
    return this.normalizer.managedImport(envelope.data);
  }

  /** 查询受管媒体电子书导入进度，成功后返回可用于整书生成的 ebookAssetId。 */
  async managedImport(importId: string): Promise<ManagedEbookImport> {
    const id = this.id(importId);
    const envelope = await this.client.get(`/api/app/v1/reader/ebook-imports/${id}`);
    return this.normalizer.managedImport(envelope.data);
  }

  /** 查询有声书生成进度。 */
  async generation(generationId: string): Promise<AudiobookGeneration> {
    const id = this.id(generationId);
    const envelope = await this.client.get(`/api/app/v1/reader/audiobook-generations/${id}`);
    return this.normalizer.generation(envelope.data);
  }

  /** 请求取消当前尚未完成的有声书后台处理。 */
  async cancel(generationId: string): Promise<AudiobookGeneration> {
    const id = this.id(generationId);
    const envelope = await this.client.post(`/api/app/v1/reader/audiobook-generations/${id}/cancel`);
    return this.normalizer.generation(envelope.data);
  }

  /** 从最后一个失败、超时或取消阶段重新提交有声书后台处理。 */
  async retry(generationId: string): Promise<AudiobookGeneration> {
    const id = this.id(generationId);
    const envelope = await this.client.post(`/api/app/v1/reader/audiobook-generations/${id}/retry`);
    return this.normalizer.generation(envelope.data);
  }

  /** 查询固定版本的播放清单。 */
  async manifest(generationId: string): Promise<AudiobookPlaybackManifest> {
    const id = this.id(generationId);
    const envelope = await this.client.get(`/api/app/v1/reader/audiobook-generations/${id}/playback-manifest`);
    return this.normalizer.manifest(envelope.data);
  }

  /** 查询本版已冻结的人物、关系和说话人分析摘要。 */
  async bookAnalysis(generationId: string): Promise<AudiobookBookAnalysis> {
    const id = this.id(generationId);
    const envelope = await this.client.get(`/api/app/v1/reader/audiobook-generations/${id}/book-analysis`);
    return this.normalizer.bookAnalysis(envelope.data);
  }

  /** 查询冻结的旁白与角色音色计划，供审核页展示。 */
  async voicePlan(generationId: string): Promise<AudiobookVoicePlan> {
    const id = this.id(generationId);
    const envelope = await this.client.get(`/api/app/v1/reader/audiobook-generations/${id}/voice-plan`);
    return this.normalizer.voicePlan(envelope.data);
  }

  /** 查询人工审核可选择的已启用音色目录。 */
  async voiceCatalog(generationId: string): Promise<AudiobookVoiceCatalog> {
    const id = this.id(generationId);
    const envelope = await this.client.get(`/api/app/v1/reader/audiobook-generations/${id}/voice-catalog`);
    return this.normalizer.voiceCatalog(envelope.data);
  }

  /** 查询当前版本冻结的读音词典，供听书介绍页审核与修订。 */
  async pronunciationDictionary(generationId: string): Promise<AudiobookPronunciationDictionary> {
    const id = this.id(generationId);
    const envelope = await this.client.get(`/api/app/v1/reader/audiobook-generations/${id}/pronunciations`);
    return this.normalizer.pronunciationDictionary(envelope.data);
  }

  /** 以目录内已启用音色创建新版本，并只重新合成受影响章节。 */
  async reviseVoice(generationId: string, request: CreateAudiobookVoiceRevision): Promise<AudiobookGeneration> {
    const id = this.id(generationId);
    this.validateVoiceRevision(request);
    const envelope = await this.client.postJson(
      `/api/app/v1/reader/audiobook-generations/${id}/voice-revisions`, request);
    return this.normalizer.generation(envelope.data);
  }

  /** 以人工确认的低置信度说话人归因创建新版本，并只重新合成其所属章节。 */
  async reviseSpeaker(generationId: string, request: CreateAudiobookSpeakerRevision): Promise<AudiobookGeneration> {
    const id = this.id(generationId);
    this.validateSpeakerRevision(request);
    const envelope = await this.client.postJson(
      `/api/app/v1/reader/audiobook-generations/${id}/speaker-revisions`, request);
    return this.normalizer.generation(envelope.data);
  }

  /** 以人工确认的中文读音创建新版本；服务端只重新合成冻结正文精确命中的章节。 */
  async revisePronunciation(generationId: string,
    request: CreateAudiobookPronunciationRevision): Promise<AudiobookGeneration> {
    const id = this.id(generationId);
    this.validatePronunciationRevision(request);
    const envelope = await this.client.postJson(
      `/api/app/v1/reader/audiobook-generations/${id}/pronunciation-revisions`, request);
    return this.normalizer.generation(envelope.data);
  }

  /** 为已完成版本创建或复用一个异步 ZIP 整书归档任务。 */
  async createExport(generationId: string, request: CreateAudiobookExport): Promise<AudiobookExport> {
    const id = this.id(generationId);
    this.validateExport(request);
    const envelope = await this.client.postJson(`/api/app/v1/reader/audiobook-generations/${id}/exports`, request);
    return this.normalizer.audiobookExport(envelope.data);
  }

  /** 查询当前有声书版本的 ZIP 归档进度。 */
  async export(generationId: string, exportId: string): Promise<AudiobookExport> {
    const id = this.id(generationId);
    const exportIdValue = this.id(exportId);
    const envelope = await this.client.get(`/api/app/v1/reader/audiobook-generations/${id}/exports/${exportIdValue}`);
    return this.normalizer.audiobookExport(envelope.data);
  }

  /** 仅在 ZIP 完成时申请一次可交给系统下载器的短期下载路径。 */
  async exportDownload(generationId: string, exportId: string): Promise<AudiobookExportDownloadDescriptor> {
    const id = this.id(generationId);
    const exportIdValue = this.id(exportId);
    const envelope = await this.client.post(
      `/api/app/v1/reader/audiobook-generations/${id}/exports/${exportIdValue}/download-ticket`);
    return this.normalizer.exportDownloadTicket(envelope.data, this.baseUrl, id, exportIdValue);
  }

  /** 为一节 READY 章节申请可交给系统播放器的短期 URL。 */
  async playback(generationId: string, chapterIndex: number): Promise<AudiobookPlaybackDescriptor> {
    const id = this.id(generationId);
    if (!Number.isSafeInteger(chapterIndex) || chapterIndex < 0 || chapterIndex > 20000) {
      throw new Error('有声书章节序号无效');
    }
    const envelope = await this.client.post(
      `/api/app/v1/reader/audiobook-generations/${id}/chapters/${chapterIndex}/play-ticket`);
    return this.normalizer.playbackTicket(envelope.data, this.baseUrl, id, chapterIndex);
  }

  /** 为一条已审核音色样音申请可交给系统播放器的短期 URL。 */
  async voicePreview(generationId: string, voice: AudiobookVoiceCandidate): Promise<AudiobookVoicePreviewDescriptor> {
    const id = this.id(generationId);
    if (!voice.previewAvailable || !this.validRequestText(voice.provider, 64) ||
      !this.validRequestText(voice.voiceType, 256)) throw new Error('该音色暂未配置试听样音');
    const provider = encodeURIComponent(voice.provider);
    const voiceType = encodeURIComponent(voice.voiceType);
    const envelope = await this.client.post(`/api/app/v1/reader/audiobook-generations/${id}` +
      `/voice-previews/play-ticket?provider=${provider}&voiceType=${voiceType}`);
    return this.normalizer.voicePreviewTicket(envelope.data, this.baseUrl, id, voice.provider, voice.voiceType);
  }

  private validateCreate(request: CreateAudiobookGeneration): void {
    this.id(request.ebookAssetId);
    if (typeof request.idempotencyKey !== 'string' || request.idempotencyKey.length < 1 ||
      request.idempotencyKey.length > 255 || /[\u0000-\u001F\u007F]/.test(request.idempotencyKey) ||
      (request.mode !== 'FULL' && request.mode !== 'INCREMENTAL' && request.mode !== 'REPAIR') ||
      request.rightsConfirmed !== true) throw new Error('有声书生成请求无效');
  }

  private validateManagedImport(request: CreateManagedEbookImport): void {
    this.id(request.mediaItemId);
    if (typeof request.idempotencyKey !== 'string' || request.idempotencyKey.length < 1 ||
      request.idempotencyKey.length > 255 || /[\u0000-\u001F\u007F]/.test(request.idempotencyKey) ||
      request.rightsConfirmed !== true) throw new Error('受管电子书导入请求无效');
  }

  private validateVoiceRevision(request: CreateAudiobookVoiceRevision): void {
    if (!this.validRequestText(request.idempotencyKey, 255) || !this.validRequestText(request.roleKey, 320) ||
      !/^NARRATOR$|^CHARACTER:.{1,256}$/.test(request.roleKey) ||
      !this.validRequestText(request.provider, 64) || !this.validRequestText(request.voiceType, 256)) {
      throw new Error('有声书音色修订请求无效');
    }
  }

  private validateSpeakerRevision(request: CreateAudiobookSpeakerRevision): void {
    const validKind = request.speakerKind === 'CHARACTER' || request.speakerKind === 'NARRATOR' ||
      request.speakerKind === 'UNKNOWN';
    const hasCharacter = request.speakerCanonicalName !== undefined;
    if (!this.validRequestText(request.idempotencyKey, 255) || !Number.isSafeInteger(request.chapterIndex) ||
      request.chapterIndex < 0 || request.chapterIndex > 20000 || !Number.isSafeInteger(request.sequenceNumber) ||
      request.sequenceNumber < 0 || request.sequenceNumber > 200000 || !validKind ||
      (hasCharacter && !this.validRequestText(request.speakerCanonicalName!, 256)) ||
      (request.speakerKind === 'CHARACTER' && !hasCharacter) ||
      (request.speakerKind !== 'CHARACTER' && hasCharacter)) {
      throw new Error('有声书说话人归因修订请求无效');
    }
  }

  private validatePronunciationRevision(request: CreateAudiobookPronunciationRevision): void {
    const term = request.term.trim();
    const pinyin = request.pinyin.trim().toLowerCase();
    if (!this.validRequestText(request.idempotencyKey, 255) ||
      term !== request.term || pinyin !== request.pinyin ||
      !/^[\u3400-\u9FFF\uF900-\uFAFF]{1,128}$/.test(term) ||
      !/^(?:[a-zvü]+[1-5])(?:\s+[a-zvü]+[1-5]){0,127}$/i.test(pinyin)) {
      throw new Error('有声书读音修订请求无效');
    }
  }

  private validateExport(request: CreateAudiobookExport): void {
    if (typeof request.idempotencyKey !== 'string' || !/^[A-Za-z0-9._:-]{1,255}$/.test(request.idempotencyKey) ||
      request.format !== 'ZIP') throw new Error('有声书导出请求无效');
  }

  private validRequestText(value: string, limit: number): boolean {
    return typeof value === 'string' && value.length > 0 && value.length <= limit &&
      !/[\u0000-\u001F\u007F]/.test(value);
  }

  private id(value: string): string {
    if (typeof value !== 'string' || !/^[0-9a-f-]{36}$/i.test(value)) {
      throw new Error('有声书标识无效');
    }
    return value.toLowerCase();
  }
}
