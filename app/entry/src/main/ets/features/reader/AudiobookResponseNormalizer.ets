import { AudiobookChapter, AudiobookExport, AudiobookExportDownloadDescriptor, AudiobookExportStatus,
  AudiobookGeneration, AudiobookGenerationMode, AudiobookGenerationStatus,
  AudiobookAnalysisEvidence, AudiobookBookAnalysis, AudiobookCharacterAlias, AudiobookCharacterProfile,
  AudiobookCharacterRelationship, AudiobookSpeechSegment,
  AudiobookPlaybackDescriptor, AudiobookPlaybackManifest, AudiobookVoicePreviewDescriptor, ManagedEbookImport,
  ManagedEbookImportStatus, AudiobookVoiceBinding, AudiobookVoicePlan, AudiobookVoiceCatalog,
  AudiobookVoiceCandidate, AudiobookPronunciationDictionary, AudiobookPronunciationEntry } from './AudiobookModels';

/**
 * 严格校验 Gateway 返回的有声书契约，禁止未经校验的播放 URL 进入系统播放器。
 */
export class AudiobookResponseNormalizer {
  private static readonly MAX_CHAPTERS: number = 20000;

  /** 解析有声书生成进度。 */
  generation(value: Object | undefined): AudiobookGeneration {
    const record = this.requiredRecord(value, '有声书生成响应无效');
    const status = this.status(record['status']);
    const mode = this.mode(record['mode']);
    const errorCode = this.optionalText(record['errorCode'], 64);
    const result: AudiobookGeneration = {
      id: this.uuid(record['id'], '有声书生成响应无效'), status: status,
      currentStage: this.text(record['currentStage'], 64, false, '有声书生成响应无效'), mode: mode,
      generationVersion: this.integer(record['generationVersion'], 1000000, '有声书生成响应无效'),
      requestedChapterCount: this.integer(record['requestedChapterCount'], AudiobookResponseNormalizer.MAX_CHAPTERS,
        '有声书生成响应无效'),
      completedChapterCount: this.integer(record['completedChapterCount'], AudiobookResponseNormalizer.MAX_CHAPTERS,
        '有声书生成响应无效'),
      failedChapterCount: this.integer(record['failedChapterCount'], AudiobookResponseNormalizer.MAX_CHAPTERS,
        '有声书生成响应无效'),
      createdAt: this.instant(record['createdAt'], '有声书生成响应无效'),
      updatedAt: this.instant(record['updatedAt'], '有声书生成响应无效')
    };
    if (result.completedChapterCount + result.failedChapterCount > result.requestedChapterCount ||
      result.generationVersion < 1) throw new Error('有声书生成响应无效');
    if (errorCode !== undefined) result.errorCode = errorCode;
    return result;
  }

  /** 解析受管媒体到 Reader 电子书资产的导入进度。 */
  managedImport(value: Object | undefined): ManagedEbookImport {
    const record = this.requiredRecord(value, '受管电子书导入响应无效');
    const status = this.managedImportStatus(record['status']);
    const assetId = this.optionalUuid(record['ebookAssetId']);
    if (status === 'SUCCEEDED' && assetId === undefined) throw new Error('受管电子书导入响应无效');
    const result: ManagedEbookImport = {
      id: this.uuid(record['id'], '受管电子书导入响应无效'), status: status,
      title: this.text(record['title'], 300, false, '受管电子书导入响应无效'),
      createdAt: this.instant(record['createdAt'], '受管电子书导入响应无效'),
      updatedAt: this.instant(record['updatedAt'], '受管电子书导入响应无效')
    };
    if (assetId !== undefined) result.ebookAssetId = assetId;
    return result;
  }

  /** 解析不含存储位置的固定版本播放清单。 */
  manifest(value: Object | undefined): AudiobookPlaybackManifest {
    const record = this.requiredRecord(value, '有声书播放清单无效');
    const rawChapters = record['chapters'];
    if (!Array.isArray(rawChapters) || rawChapters.length > AudiobookResponseNormalizer.MAX_CHAPTERS) {
      throw new Error('有声书播放清单无效');
    }
    const chapters: AudiobookChapter[] = [];
    rawChapters.forEach((value: Object, position: number): void => {
      const chapter = this.chapter(value, position);
      if (chapter.index !== position) throw new Error('有声书播放清单无效');
      chapters.push(chapter);
    });
    return { generationId: this.uuid(record['generationId'], '有声书播放清单无效'),
      generationVersion: this.integer(record['generationVersion'], 1000000, '有声书播放清单无效'),
      status: this.status(record['status']), chapters: chapters };
  }

  /** 解析可在审核页展示的全书人物与关系摘要。 */
  bookAnalysis(value: Object | undefined): AudiobookBookAnalysis {
    const record = this.requiredRecord(value, '有声书人物分析响应无效');
    const rawCharacters = record['characters'];
    const rawRelationships = record['relationships'];
    const rawSpeechSegments = record['speechSegments'];
    if (!Array.isArray(rawCharacters) || rawCharacters.length > 5000 ||
      !Array.isArray(rawRelationships) || rawRelationships.length > 20000 ||
      !Array.isArray(rawSpeechSegments) || rawSpeechSegments.length > 200000) {
      throw new Error('有声书人物分析响应无效');
    }
    const characters: AudiobookCharacterProfile[] = [];
    const names: string[] = [];
    rawCharacters.forEach((value: Object): void => {
      const character = this.characterProfile(value);
      if (names.includes(character.canonicalName)) throw new Error('有声书人物分析响应无效');
      names.push(character.canonicalName);
      characters.push(character);
    });
    const relationships: AudiobookCharacterRelationship[] = [];
    rawRelationships.forEach((value: Object): void => {
      relationships.push(this.characterRelationship(value));
    });
    const speechSegments: AudiobookSpeechSegment[] = [];
    rawSpeechSegments.forEach((value: Object): void => {
      speechSegments.push(this.speechSegment(value));
    });
    return { generationId: this.uuid(record['generationId'], '有声书人物分析响应无效'),
      analysisModelVersion: this.text(record['analysisModelVersion'], 128, false, '有声书人物分析响应无效'),
      analysisRuleVersion: this.text(record['analysisRuleVersion'], 64, false, '有声书人物分析响应无效'),
      characters: characters, relationships: relationships, speechSegmentCount: rawSpeechSegments.length,
      speechSegments: speechSegments };
  }

  /** 将票据回执转换为只允许本有声书章节使用的 HTTPS 播放描述。 */
  playbackTicket(value: Object | undefined, baseUrl: string, generationId: string,
    chapterIndex: number, now: number = Date.now()): AudiobookPlaybackDescriptor {
    const record = this.requiredRecord(value, '有声书播放票据无效');
    const ticket = this.text(record['ticket'], 32, false, '有声书播放票据无效');
    const streamPath = this.text(record['streamPath'], 128, false, '有声书播放票据无效');
    const expiresAt = this.instant(record['expiresAt'], '有声书播放票据无效');
    const expectedPath = `/api/app/v1/audiobook-playback/tickets/${ticket}`;
    if (!/^[a-f0-9]{32}$/.test(ticket) || streamPath !== expectedPath || !baseUrl.startsWith('https://') ||
      expiresAt < now - 5 * 60 * 1000 || expiresAt > now + 24 * 60 * 60 * 1000 ||
      !Number.isSafeInteger(chapterIndex) || chapterIndex < 0 || !/^[0-9a-f-]{36}$/i.test(generationId)) {
      throw new Error('有声书播放票据无效');
    }
    return { url: `${baseUrl}${streamPath}`, ticket: ticket, expiresAt: expiresAt,
      generationId: generationId.toLowerCase(), chapterIndex: chapterIndex };
  }

  /** 将票据回执转换为只允许当前音色试听使用的 HTTPS 播放描述。 */
  voicePreviewTicket(value: Object | undefined, baseUrl: string, generationId: string, provider: string,
    voiceType: string, now: number = Date.now()): AudiobookVoicePreviewDescriptor {
    const record = this.requiredRecord(value, '音色试听票据无效');
    const ticket = this.text(record['ticket'], 32, false, '音色试听票据无效');
    const streamPath = this.text(record['streamPath'], 160, false, '音色试听票据无效');
    const expiresAt = this.instant(record['expiresAt'], '音色试听票据无效');
    const expectedPath = `/api/app/v1/audiobook-voice-preview/tickets/${ticket}`;
    if (!/^[a-f0-9]{32}$/.test(ticket) || streamPath !== expectedPath || !baseUrl.startsWith('https://') ||
      expiresAt < now - 5 * 60 * 1000 || expiresAt > now + 30 * 60 * 1000 ||
      !/^[0-9a-f-]{36}$/i.test(generationId)) {
      throw new Error('音色试听票据无效');
    }
    return { url: `${baseUrl}${streamPath}`, ticket: ticket, expiresAt: expiresAt,
      generationId: generationId.toLowerCase(), provider: provider, voiceType: voiceType };
  }

  /** 解析不包含归档存储位置的异步 ZIP 导出任务。 */
  audiobookExport(value: Object | undefined): AudiobookExport {
    const record = this.requiredRecord(value, '有声书导出响应无效');
    const status = this.exportStatus(record['status']);
    const sizeBytes = this.optionalPositiveInteger(record['sizeBytes']);
    const errorCode = this.optionalText(record['errorCode'], 64);
    if ((status === 'COMPLETED' && sizeBytes === undefined) ||
      (status !== 'COMPLETED' && sizeBytes !== undefined) || record['format'] !== 'ZIP') {
      throw new Error('有声书导出响应无效');
    }
    const result: AudiobookExport = {
      id: this.uuid(record['id'], '有声书导出响应无效'),
      generationId: this.uuid(record['generationId'], '有声书导出响应无效'),
      format: 'ZIP', status: status,
      currentStage: this.text(record['currentStage'], 64, false, '有声书导出响应无效'),
      chapterCount: this.integer(record['chapterCount'], AudiobookResponseNormalizer.MAX_CHAPTERS,
        '有声书导出响应无效'),
      createdAt: this.instant(record['createdAt'], '有声书导出响应无效'),
      updatedAt: this.instant(record['updatedAt'], '有声书导出响应无效')
    };
    if (result.chapterCount < 1) throw new Error('有声书导出响应无效');
    if (sizeBytes !== undefined) result.sizeBytes = sizeBytes;
    if (errorCode !== undefined) result.errorCode = errorCode;
    return result;
  }

  /** 解析只可用于当前已完成 ZIP 的短期系统下载票据。 */
  exportDownloadTicket(value: Object | undefined, baseUrl: string, generationId: string,
    exportId: string, now: number = Date.now()): AudiobookExportDownloadDescriptor {
    const record = this.requiredRecord(value, '有声书导出票据无效');
    const ticket = this.text(record['ticket'], 32, false, '有声书导出票据无效');
    const downloadPath = this.text(record['downloadPath'], 128, false, '有声书导出票据无效');
    const expiresAt = this.instant(record['expiresAt'], '有声书导出票据无效');
    const expectedPath = `/api/app/v1/audiobook-export/tickets/${ticket}`;
    if (!/^[a-f0-9]{32}$/.test(ticket) || downloadPath !== expectedPath || !baseUrl.startsWith('https://') ||
      expiresAt < now - 5 * 60 * 1000 || expiresAt > now + 12 * 60 * 60 * 1000 ||
      !/^[0-9a-f-]{36}$/i.test(generationId) || !/^[0-9a-f-]{36}$/i.test(exportId)) {
      throw new Error('有声书导出票据无效');
    }
    return { url: `${baseUrl}${downloadPath}`, ticket: ticket, expiresAt: expiresAt,
      generationId: generationId.toLowerCase(), exportId: exportId.toLowerCase() };
  }

  /** 解析不含密钥的冻结旁白与角色音色计划。 */
  voicePlan(value: Object | undefined): AudiobookVoicePlan {
    const record = this.requiredRecord(value, '有声书音色计划响应无效');
    const values = record['bindings'];
    if (!Array.isArray(values) || values.length < 1 || values.length > 5001) {
      throw new Error('有声书音色计划响应无效');
    }
    const bindings: AudiobookVoiceBinding[] = [];
    const roles: string[] = [];
    values.forEach((item: Object): void => {
      const binding = this.voiceBinding(item);
      if (roles.includes(binding.roleKey)) throw new Error('有声书音色计划响应无效');
      roles.push(binding.roleKey);
      bindings.push(binding);
    });
    if (!roles.includes('NARRATOR')) throw new Error('有声书音色计划响应无效');
    const rawAttestation = record['qualityGateAttestationSha256'];
    let qualityGateAttestationSha256: string | undefined = undefined;
    if (rawAttestation !== undefined && rawAttestation !== null) {
      const value = this.text(rawAttestation, 64, false, '有声书音色计划响应无效');
      if (!/^[a-f0-9]{64}$/i.test(value)) throw new Error('有声书音色计划响应无效');
      qualityGateAttestationSha256 = value.toLowerCase();
    }
    return { generationId: this.uuid(record['generationId'], '有声书音色计划响应无效'),
      qualityGateAttestationSha256: qualityGateAttestationSha256, bindings: bindings };
  }

  /** 解析供审核页选择的受控音色目录。 */
  voiceCatalog(value: Object | undefined): AudiobookVoiceCatalog {
    const record = this.requiredRecord(value, '有声书音色目录响应无效');
    const values = record['voices'];
    if (!Array.isArray(values) || values.length > 500) throw new Error('有声书音色目录响应无效');
    const voices: AudiobookVoiceCandidate[] = [];
    const keys: string[] = [];
    values.forEach((item: Object): void => {
      const voice = this.voiceCandidate(item);
      const key = `${voice.provider}\u0000${voice.voiceType}`;
      if (keys.includes(key)) throw new Error('有声书音色目录响应无效');
      keys.push(key);
      voices.push(voice);
    });
    return { generationId: this.uuid(record['generationId'], '有声书音色目录响应无效'), voices: voices };
  }

  /** 解析不包含正文、地址或认证信息的冻结读音词典。 */
  pronunciationDictionary(value: Object | undefined): AudiobookPronunciationDictionary {
    const record = this.requiredRecord(value, '有声书读音词典响应无效');
    const values = record['entries'];
    const fingerprint = this.text(record['fingerprintSha256'], 64, false, '有声书读音词典响应无效');
    if (!/^[a-f0-9]{64}$/i.test(fingerprint) || !Array.isArray(values) || values.length > 10000) {
      throw new Error('有声书读音词典响应无效');
    }
    const entries: AudiobookPronunciationEntry[] = [];
    const terms: string[] = [];
    values.forEach((item: Object): void => {
      const entry = this.pronunciationEntry(item);
      if (terms.includes(entry.term)) throw new Error('有声书读音词典响应无效');
      terms.push(entry.term);
      entries.push(entry);
    });
    return { generationId: this.uuid(record['generationId'], '有声书读音词典响应无效'),
      fingerprintSha256: fingerprint.toLowerCase(), entries: entries };
  }

  private chapter(value: Object, position: number): AudiobookChapter {
    const record = this.requiredRecord(value, '有声书播放清单无效');
    const availability = record['availability'];
    const audioAsset = this.optionalUuid(record['audioAssetId']);
    const duration = this.optionalPositiveInteger(record['durationMs']);
    if ((availability !== 'PENDING' && availability !== 'READY' && availability !== 'FAILED') ||
      (availability === 'READY' && (audioAsset === undefined || duration === undefined)) ||
      (availability !== 'READY' && (audioAsset !== undefined || duration !== undefined))) {
      throw new Error('有声书播放清单无效');
    }
    const chapter: AudiobookChapter = { index: this.integer(record['index'], AudiobookResponseNormalizer.MAX_CHAPTERS,
      '有声书播放清单无效'), title: this.text(record['title'], 500, false, '有声书播放清单无效'),
      availability: availability as 'PENDING' | 'READY' | 'FAILED' };
    if (audioAsset !== undefined) chapter.audioAssetId = audioAsset;
    if (duration !== undefined) chapter.durationMs = duration;
    return chapter;
  }

  private characterProfile(value: Object): AudiobookCharacterProfile {
    const record = this.requiredRecord(value, '有声书人物分析响应无效');
    const traits = this.tags(record['traits'], 32, '有声书人物分析响应无效');
    const aliasesValue = record['aliases'];
    if (!Array.isArray(aliasesValue) || aliasesValue.length > 200) {
      throw new Error('有声书人物分析响应无效');
    }
    const aliases: AudiobookCharacterAlias[] = [];
    aliasesValue.forEach((item: Object): void => {
      aliases.push(this.characterAlias(item));
    });
    return { canonicalName: this.text(record['canonicalName'], 256, false, '有声书人物分析响应无效'),
      displayName: this.text(record['displayName'], 256, false, '有声书人物分析响应无效'),
      presentation: this.presentation(record['presentation'], '有声书人物分析响应无效'),
      characterType: this.text(record['characterType'], 64, false, '有声书人物分析响应无效'), traits: traits,
      firstChapterIndex: this.integer(record['firstChapterIndex'], AudiobookResponseNormalizer.MAX_CHAPTERS,
        '有声书人物分析响应无效'), occurrenceCount: this.integer(record['occurrenceCount'], 100000000,
        '有声书人物分析响应无效'), confidence: this.confidence(record['confidence'], '有声书人物分析响应无效'),
      reviewStatus: this.text(record['reviewStatus'], 32, false, '有声书人物分析响应无效'), aliases: aliases };
  }

  private characterAlias(value: Object): AudiobookCharacterAlias {
    const record = this.requiredRecord(value, '有声书人物分析响应无效');
    const evidence = this.evidence(record, '有声书人物分析响应无效');
    const result: AudiobookCharacterAlias = { alias: this.text(record['alias'], 256, false,
      '有声书人物分析响应无效'), aliasType: this.text(record['aliasType'], 64, false,
      '有声书人物分析响应无效'), confidence: this.confidence(record['confidence'],
      '有声书人物分析响应无效') };
    if (evidence.chapterIndex !== undefined) result.evidenceChapterIndex = evidence.chapterIndex;
    if (evidence.startCodepoint !== undefined) result.evidenceStartCodepoint = evidence.startCodepoint;
    if (evidence.endCodepoint !== undefined) result.evidenceEndCodepoint = evidence.endCodepoint;
    return result;
  }

  private characterRelationship(value: Object): AudiobookCharacterRelationship {
    const record = this.requiredRecord(value, '有声书人物分析响应无效');
    const evidence = this.evidence(record, '有声书人物分析响应无效');
    const result: AudiobookCharacterRelationship = {
      sourceCanonicalName: this.text(record['sourceCanonicalName'], 256, false, '有声书人物分析响应无效'),
      targetCanonicalName: this.text(record['targetCanonicalName'], 256, false, '有声书人物分析响应无效'),
      relationshipType: this.text(record['relationshipType'], 64, false, '有声书人物分析响应无效'),
      direction: this.text(record['direction'], 32, false, '有声书人物分析响应无效'),
      confidence: this.confidence(record['confidence'], '有声书人物分析响应无效'),
      reviewStatus: this.text(record['reviewStatus'], 32, false, '有声书人物分析响应无效') };
    if (evidence.chapterIndex !== undefined) result.evidenceChapterIndex = evidence.chapterIndex;
    if (evidence.startCodepoint !== undefined) result.evidenceStartCodepoint = evidence.startCodepoint;
    if (evidence.endCodepoint !== undefined) result.evidenceEndCodepoint = evidence.endCodepoint;
    return result;
  }

  private speechSegment(value: Object): AudiobookSpeechSegment {
    const record = this.requiredRecord(value, '有声书人物分析响应无效');
    const speakerKind = record['speakerKind'];
    const character = this.optionalText(record['speakerCanonicalName'], 256);
    if ((speakerKind !== 'CHARACTER' && speakerKind !== 'NARRATOR' && speakerKind !== 'UNKNOWN') ||
      (speakerKind === 'CHARACTER' && character === undefined) ||
      (speakerKind !== 'CHARACTER' && character !== undefined)) {
      throw new Error('有声书人物分析响应无效');
    }
    const start = this.integer(record['textStartCodepoint'], 100000000, '有声书人物分析响应无效');
    const end = this.integer(record['textEndCodepoint'], 100000000, '有声书人物分析响应无效');
    const evidenceExcerpt = this.optionalText(record['evidenceExcerpt'], 768);
    if (record['evidenceExcerpt'] !== undefined && record['evidenceExcerpt'] !== null &&
      (evidenceExcerpt === undefined || evidenceExcerpt.length === 0)) {
      throw new Error('有声书人物分析响应无效');
    }
    if (start >= end) throw new Error('有声书人物分析响应无效');
    const result: AudiobookSpeechSegment = {
      chapterIndex: this.integer(record['chapterIndex'], AudiobookResponseNormalizer.MAX_CHAPTERS,
        '有声书人物分析响应无效'),
      sequenceNumber: this.integer(record['sequenceNumber'], 200000, '有声书人物分析响应无效'),
      textStartCodepoint: start, textEndCodepoint: end,
      speakerKind: speakerKind as 'CHARACTER' | 'NARRATOR' | 'UNKNOWN',
      deliveryTags: this.tags(record['deliveryTags'], 16, '有声书人物分析响应无效'),
      confidence: this.confidence(record['confidence'], '有声书人物分析响应无效'),
      reviewStatus: this.text(record['reviewStatus'], 32, false, '有声书人物分析响应无效') };
    if (character !== undefined) result.speakerCanonicalName = character;
    if (evidenceExcerpt !== undefined) result.evidenceExcerpt = evidenceExcerpt;
    return result;
  }

  private evidence(record: Record<string, Object>, message: string): AudiobookAnalysisEvidence {
    const chapterIndex = this.optionalInteger(record['evidenceChapterIndex'], AudiobookResponseNormalizer.MAX_CHAPTERS);
    const startCodepoint = this.optionalInteger(record['evidenceStartCodepoint'], 100000000);
    const endCodepoint = this.optionalInteger(record['evidenceEndCodepoint'], 100000000);
    if ((chapterIndex === undefined) !== (startCodepoint === undefined) ||
      (chapterIndex === undefined) !== (endCodepoint === undefined) ||
      (startCodepoint !== undefined && endCodepoint !== undefined && startCodepoint > endCodepoint)) {
      throw new Error(message);
    }
    const result: AudiobookAnalysisEvidence = {};
    if (chapterIndex !== undefined) result.chapterIndex = chapterIndex;
    if (startCodepoint !== undefined) result.startCodepoint = startCodepoint;
    if (endCodepoint !== undefined) result.endCodepoint = endCodepoint;
    return result;
  }

  private voiceBinding(value: Object): AudiobookVoiceBinding {
    const record = this.requiredRecord(value, '有声书音色计划响应无效');
    const roleKey = this.text(record['roleKey'], 320, false, '有声书音色计划响应无效');
    const character = this.optionalText(record['characterCanonicalName'], 256);
    const score = record['matchScore'];
    const rawTags = record['rationaleTags'];
    if ((!/^NARRATOR$|^CHARACTER:.{1,256}$/.test(roleKey)) ||
      (roleKey === 'NARRATOR' && character !== undefined) ||
      (roleKey !== 'NARRATOR' && (character === undefined || roleKey !== `CHARACTER:${character}`)) ||
      typeof score !== 'number' || !Number.isFinite(score) || score < 0 || score > 1 ||
      !Array.isArray(rawTags) || rawTags.length > 16 || typeof record['locked'] !== 'boolean') {
      throw new Error('有声书音色计划响应无效');
    }
    const rationaleTags: string[] = [];
    rawTags.forEach((tag: Object): void => {
      const normalized = this.text(tag, 64, false, '有声书音色计划响应无效');
      if (rationaleTags.includes(normalized)) throw new Error('有声书音色计划响应无效');
      rationaleTags.push(normalized);
    });
    const result: AudiobookVoiceBinding = { roleKey: roleKey,
      provider: this.text(record['provider'], 64, false, '有声书音色计划响应无效'),
      voiceType: this.text(record['voiceType'], 256, false, '有声书音色计划响应无效'), matchScore: score,
      matchMethod: this.text(record['matchMethod'], 64, false, '有声书音色计划响应无效'),
      rationaleTags: rationaleTags, locked: record['locked'] as boolean,
      reviewStatus: this.text(record['reviewStatus'], 32, false, '有声书音色计划响应无效') };
    if (character !== undefined) result.characterCanonicalName = character;
    return result;
  }

  private voiceCandidate(value: Object): AudiobookVoiceCandidate {
    const record = this.requiredRecord(value, '有声书音色目录响应无效');
    const rawTags = record['styleTags'];
    const presentation = record['presentation'];
    if (!Array.isArray(rawTags) || rawTags.length > 32 || typeof record['narratorEligible'] !== 'boolean' ||
      typeof record['ssmlSupported'] !== 'boolean' ||
      typeof record['previewAvailable'] !== 'boolean' ||
      (presentation !== 'FEMININE' && presentation !== 'MASCULINE' && presentation !== 'NON_BINARY' &&
        presentation !== 'NEUTRAL' && presentation !== 'UNKNOWN')) {
      throw new Error('有声书音色目录响应无效');
    }
    const styleTags: string[] = [];
    rawTags.forEach((tag: Object): void => {
      const normalized = this.text(tag, 64, false, '有声书音色目录响应无效');
      if (styleTags.includes(normalized)) throw new Error('有声书音色目录响应无效');
      styleTags.push(normalized);
    });
    return { provider: this.text(record['provider'], 64, false, '有声书音色目录响应无效'),
      voiceType: this.text(record['voiceType'], 256, false, '有声书音色目录响应无效'),
      language: this.text(record['language'], 32, false, '有声书音色目录响应无效'),
      presentation: presentation as string, ageGroup: this.text(record['ageGroup'], 32, false,
        '有声书音色目录响应无效'), styleTags: styleTags,
      narratorEligible: record['narratorEligible'] as boolean,
      catalogVersion: this.text(record['catalogVersion'], 64, false, '有声书音色目录响应无效'),
      ssmlSupported: record['ssmlSupported'] as boolean,
      previewAvailable: record['previewAvailable'] as boolean };
  }

  private pronunciationEntry(value: Object): AudiobookPronunciationEntry {
    const record = this.requiredRecord(value, '有声书读音词典响应无效');
    const term = this.text(record['term'], 128, false, '有声书读音词典响应无效');
    const pinyin = this.text(record['pinyin'], 512, false, '有声书读音词典响应无效');
    if (!/^[\u3400-\u9FFF\uF900-\uFAFF]{1,128}$/.test(term) ||
      !/^(?:[a-zvü]+[1-5])(?:\s+[a-zvü]+[1-5]){0,127}$/.test(pinyin)) {
      throw new Error('有声书读音词典响应无效');
    }
    return { term: term, pinyin: pinyin };
  }

  private tags(value: Object | undefined, maximum: number, message: string): string[] {
    if (!Array.isArray(value) || value.length > maximum) throw new Error(message);
    const tags: string[] = [];
    value.forEach((item: Object): void => {
      const tag = this.text(item, 64, false, message);
      if (tags.includes(tag)) throw new Error(message);
      tags.push(tag);
    });
    return tags;
  }

  private presentation(value: Object | undefined, message: string): string {
    const presentation = this.text(value, 32, false, message);
    if (presentation !== 'FEMININE' && presentation !== 'MASCULINE' && presentation !== 'NON_BINARY' &&
      presentation !== 'NEUTRAL' && presentation !== 'UNKNOWN') throw new Error(message);
    return presentation;
  }

  private confidence(value: Object | undefined, message: string): number {
    if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 1) throw new Error(message);
    return value;
  }

  private status(value: Object | undefined): AudiobookGenerationStatus {
    if (value === 'ACCEPTED' || value === 'QUEUED' || value === 'TEXT_READY' || value === 'COMPLETED' ||
      value === 'FAILED' || value === 'TIMED_OUT' || value === 'CANCELLED') {
      return value as AudiobookGenerationStatus;
    }
    throw new Error('有声书生成响应无效');
  }

  private mode(value: Object | undefined): AudiobookGenerationMode {
    if (value === 'FULL' || value === 'INCREMENTAL' || value === 'REPAIR') return value as AudiobookGenerationMode;
    throw new Error('有声书生成响应无效');
  }

  private managedImportStatus(value: Object | undefined): ManagedEbookImportStatus {
    if (value === 'ACCEPTED' || value === 'QUEUED' || value === 'SUCCEEDED' || value === 'FAILED' ||
      value === 'TIMED_OUT' || value === 'CANCELLED') return value as ManagedEbookImportStatus;
    throw new Error('受管电子书导入响应无效');
  }

  private exportStatus(value: Object | undefined): AudiobookExportStatus {
    if (value === 'ACCEPTED' || value === 'QUEUED' || value === 'COMPLETED' || value === 'FAILED' ||
      value === 'TIMED_OUT' || value === 'CANCELLED') return value as AudiobookExportStatus;
    throw new Error('有声书导出响应无效');
  }

  private requiredRecord(value: Object | undefined, message: string): Record<string, Object> {
    if (value === undefined || value === null || typeof value !== 'object' || Array.isArray(value)) {
      throw new Error(message);
    }
    return value as Record<string, Object>;
  }

  private uuid(value: Object | undefined, message: string): string {
    const result = this.optionalUuid(value);
    if (result === undefined) throw new Error(message);
    return result;
  }

  private optionalUuid(value: Object | undefined): string | undefined {
    return typeof value === 'string' && /^[0-9a-f-]{36}$/i.test(value) ? value.toLowerCase() : undefined;
  }

  private text(value: Object | undefined, maximum: number, allowEmpty: boolean, message: string): string {
    const result = this.optionalText(value, maximum);
    if (result === undefined || (!allowEmpty && result.length === 0)) throw new Error(message);
    return result;
  }

  private optionalText(value: Object | undefined, maximum: number): string | undefined {
    return typeof value === 'string' && value.length <= maximum && !/[\u0000-\u001F\u007F]/.test(value) ? value : undefined;
  }

  private integer(value: Object | undefined, maximum: number, message: string): number {
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0 || value > maximum) {
      throw new Error(message);
    }
    return value;
  }

  private optionalPositiveInteger(value: Object | undefined): number | undefined {
    return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 &&
      value <= Number.MAX_SAFE_INTEGER ? value : undefined;
  }

  private optionalInteger(value: Object | undefined, maximum: number): number | undefined {
    return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= maximum
      ? value : undefined;
  }

  private instant(value: Object | undefined, message: string): number {
    if (typeof value !== 'string' || value.length > 64) throw new Error(message);
    const result = Date.parse(value);
    if (!Number.isFinite(result)) throw new Error(message);
    return result;
  }
}
