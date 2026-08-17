import { textToSpeech } from '@kit.CoreSpeechKit';

/** 阅读朗读状态回调。 */
export interface ReaderSpeechCallbacks {
  onStatus: (status: string, active: boolean) => void;
}

/** 管理系统离线TTS引擎及串行文本片段生命周期。 */
export class ReaderSpeechController {
  private engine?: textToSpeech.TextToSpeechEngine = undefined;
  private chunks: string[] = [];
  private index: number = 0;
  private revision: number = 0;
  private callbacks?: ReaderSpeechCallbacks = undefined;

  /** 初始化离线中文引擎并开始朗读。 */
  async start(chunks: string[], callbacks: ReaderSpeechCallbacks): Promise<void> {
    this.stop();
    if (chunks.length === 0) throw new Error('当前页面没有可朗读文本');
    const revision = ++this.revision;
    this.callbacks = callbacks;
    this.chunks = chunks.slice(0, 400);
    this.index = 0;
    callbacks.onStatus('正在准备系统朗读…', true);
    try {
      const engine = await textToSpeech.createEngine({ language: 'zh-CN', person: 0, online: 1 });
      if (revision !== this.revision) {
        engine.shutdown();
        return;
      }
      this.engine = engine;
      engine.setListener({
        onStart: (_requestId: string, _response: textToSpeech.StartResponse): void => {
          if (revision === this.revision) this.callbacks?.onStatus(
            `正在朗读 ${this.index + 1}/${this.chunks.length}`, true);
        },
        onComplete: (_requestId: string, _response: textToSpeech.CompleteResponse): void => {
          if (revision !== this.revision) return;
          this.index++;
          if (this.index < this.chunks.length) this.speakCurrent(revision);
          else this.finish('朗读完成');
        },
        onStop: (_requestId: string, _response: textToSpeech.StopResponse): void => {
          if (revision === this.revision) this.finish('朗读已停止');
        },
        onError: (_requestId: string, _errorCode: number, errorMessage: string): void => {
          if (revision === this.revision) this.finish(errorMessage.length > 0 ? `朗读失败：${errorMessage}` : '朗读失败');
        }
      });
      this.speakCurrent(revision);
    } catch (error) {
      if (revision === this.revision) this.finish(error instanceof Error ? `朗读不可用：${error.message}` : '朗读不可用');
    }
  }

  /** 停止朗读并释放引擎资源。 */
  stop(): void {
    this.revision++;
    const engine = this.engine;
    this.engine = undefined;
    this.chunks = [];
    this.index = 0;
    if (engine !== undefined) {
      try {
        engine.stop();
      } catch (_) {
      }
      engine.shutdown();
    }
    this.callbacks?.onStatus('朗读已停止', false);
    this.callbacks = undefined;
  }

  private speakCurrent(revision: number): void {
    if (revision !== this.revision || this.engine === undefined || this.index >= this.chunks.length) return;
    try {
      this.engine.speak(this.chunks[this.index], { requestId: `reader-${revision}-${this.index}` });
    } catch (error) {
      this.finish(error instanceof Error ? `朗读失败：${error.message}` : '朗读失败');
    }
  }

  private finish(status: string): void {
    const engine = this.engine;
    this.engine = undefined;
    this.chunks = [];
    this.index = 0;
    if (engine !== undefined) engine.shutdown();
    this.callbacks?.onStatus(status, false);
    this.callbacks = undefined;
  }
}
