import { media } from '@kit.MediaKit';
import common from '@ohos.app.ability.common';
import wantAgent from '@ohos.app.ability.wantAgent';
import backgroundTaskManager from '@ohos.resourceschedule.backgroundTaskManager';
import avSession from '@ohos.multimedia.avsession';
import { MediaSeekPolicy } from './MediaSeekPolicy';
import { MediaOperationRevisionPolicy } from './MediaOperationRevisionPolicy';

/**
 * 封装远程音视频播放器生命周期。
 */
export class RemoteMediaPlayer {
  private player?: media.AVPlayer;
  private state: media.AVPlayerState = 'idle';
  onStateChange: (state: media.AVPlayerState) => void = (_state: media.AVPlayerState): void => {};
  onTimeUpdate: (timeMs: number) => void = (_timeMs: number): void => {};
  onDurationUpdate: (durationMs: number) => void = (_durationMs: number): void => {};
  onOutputDeviceChange: (name: string) => void = (_name: string): void => {};
  onError: (message: string) => void = (_message: string): void => {};
  onPlayNext: () => void = (): void => {};
  onPlayPrevious: () => void = (): void => {};
  private pendingStartMs: number = 0;
  private playbackSpeed: media.PlaybackSpeed = media.PlaybackSpeed.SPEED_FORWARD_1_00_X;
  private loopEnabled: boolean = false;
  private session?: avSession.AVSession;
  private backgroundContext?: common.UIAbilityContext;
  private backgroundStarted: boolean = false;
  private sessionRevision: number = 0;
  private backgroundRevision: number = 0;
  private lastTimeMs: number = 0;
  private durationMs: number = 0;
  private speedValue: number = 1;
  private lastStatePublishedAt: number = 0;
  private operationRevision: number = 0;
  private readonly seekPolicy: MediaSeekPolicy = new MediaSeekPolicy();
  private readonly revisionPolicy: MediaOperationRevisionPolicy = new MediaOperationRevisionPolicy();

  /**
   * 创建播放器并开始播放 HTTPS 远程媒体。
   */
  async open(url: string, startMs: number = 0, context?: common.UIAbilityContext,
    title: string = 'MyTools远程音频'): Promise<void> {
    if (!url.startsWith('https://')) {
      throw new Error('仅允许播放 HTTPS 远程媒体');
    }
    const revision = ++this.operationRevision;
    await this.releaseResources();
    if (revision !== this.operationRevision) throw new Error('远程音频打开已取消');
    this.pendingStartMs = this.seekPolicy.absolute(startMs, 0) ?? 0;
    this.lastTimeMs = this.pendingStartMs;
    if (context !== undefined) {
      await this.startSystemSession(context, title, `remote-audio-${Date.now()}`, revision);
      if (revision !== this.operationRevision) {
        await this.stopSystemSession(revision);
        throw new Error('远程音频打开已取消');
      }
    }
    try {
      const player = await media.createAVPlayer();
      if (revision !== this.operationRevision) {
        await player.release();
        await this.stopSystemSession(revision);
        throw new Error('远程音频打开已取消');
      }
      this.player = player;
      player.on('stateChange', (state: media.AVPlayerState) => {
        if (!this.currentPlayerEvent(player, revision)) return;
        this.state = state;
        this.onStateChange(state);
        if (state === 'initialized') {
          this.prepareCurrentPlayer(player, revision);
        } else if (state === 'prepared') {
          this.playCurrentPlayer(player, revision);
        }
        this.publishPlaybackState();
      });
      player.on('timeUpdate', (timeMs: number) => {
        if (!this.currentPlayerEvent(player, revision)) return;
        this.lastTimeMs = timeMs;
        this.onTimeUpdate(timeMs);
        if (Date.now() - this.lastStatePublishedAt >= 1000) this.publishPlaybackState();
      });
      player.on('durationUpdate', (durationMs: number) => {
        if (!this.currentPlayerEvent(player, revision)) return;
        this.durationMs = durationMs;
        this.onDurationUpdate(durationMs);
      });
      player.on('error', (_error) => {
        if (!this.currentPlayerEvent(player, revision)) return;
        console.error('Remote media playback failed');
        this.onError('远程音频播放失败，请检查数据源或网络');
      });
      player.url = url;
    } catch (error) {
      if (revision === this.operationRevision) await this.releaseResources();
      const message = error instanceof Error ? error.message : '无法创建远程播放器';
      throw new Error(message);
    }
  }

  /**
   * 设置远程音频倍速，仅接受系统播放器稳定支持的档位。
   */
  setPlaybackSpeed(value: number): void {
    const mapping = new Map<number, media.PlaybackSpeed>([
      [0.75, media.PlaybackSpeed.SPEED_FORWARD_0_75_X],
      [1, media.PlaybackSpeed.SPEED_FORWARD_1_00_X],
      [1.25, media.PlaybackSpeed.SPEED_FORWARD_1_25_X],
      [1.5, media.PlaybackSpeed.SPEED_FORWARD_1_50_X],
      [1.75, media.PlaybackSpeed.SPEED_FORWARD_1_75_X],
      [2, media.PlaybackSpeed.SPEED_FORWARD_2_00_X]
    ]);
    const speed = mapping.get(value);
    if (speed === undefined) throw new Error('不支持该播放倍速');
    this.playbackSpeed = speed;
    this.speedValue = value;
    if (this.player !== undefined && (this.state === 'prepared' || this.state === 'playing' ||
      this.state === 'paused' || this.state === 'completed')) this.player.setSpeed(speed);
    this.publishPlaybackState();
  }

  /**
   * 开启或关闭单曲循环。
   */
  setLoop(enabled: boolean): void {
    this.loopEnabled = enabled;
    if (this.player !== undefined && (this.state === 'prepared' || this.state === 'playing' ||
      this.state === 'paused' || this.state === 'completed')) this.player.loop = enabled;
    this.publishPlaybackState();
  }

  /**
   * 在播放与暂停状态间切换。
   */
  async toggle(): Promise<void> {
    const player = this.player;
    if (player === undefined) {
      return;
    }
    try {
      if (this.state === 'playing') {
        await player.pause();
      } else if (this.state === 'paused' || this.state === 'prepared' || this.state === 'completed') {
        await player.play();
      }
    } catch (error) {
      throw new Error(error instanceof Error ? error.message : '播放状态切换失败');
    }
  }

  /**
   * 跳转到指定毫秒位置。
   */
  seek(timeMs: number): void {
    const target = this.seekPolicy.absolute(timeMs, this.durationMs);
    if (this.player !== undefined && target !== undefined) this.player.seek(target, media.SeekMode.SEEK_CLOSEST);
  }

  /**
   * 释放底层播放器资源。
   */
  async release(): Promise<void> {
    this.operationRevision++;
    await this.releaseResources();
  }

  private async releaseResources(): Promise<void> {
    const player = this.player;
    this.player = undefined;
    this.state = 'idle';
    this.onStateChange('idle');
    this.pendingStartMs = 0;
    this.lastTimeMs = 0;
    this.durationMs = 0;
    this.lastStatePublishedAt = 0;
    if (player !== undefined && player.state !== 'released') {
      try {
        await player.release();
      } catch (error) {
        console.error('Failed to release remote player');
      }
    }
    await this.stopSystemSession();
  }

  private async startSystemSession(context: common.UIAbilityContext, title: string, assetId: string,
    revision: number): Promise<void> {
    let session: avSession.AVSession | undefined = undefined;
    try {
      const launchAgent = await wantAgent.getWantAgent({
        wants: [{ bundleName: 'com.yuyutian.mytools', abilityName: 'EntryAbility' }],
        actionType: wantAgent.OperationType.START_ABILITY,
        requestCode: 1001,
        actionFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG]
      });
      if (revision !== this.operationRevision) throw new Error('远程音频打开已取消');
      await backgroundTaskManager.startBackgroundRunning(context,
        backgroundTaskManager.BackgroundMode.AUDIO_PLAYBACK, launchAgent);
      if (revision !== this.operationRevision) {
        if (this.backgroundRevision <= revision) await backgroundTaskManager.stopBackgroundRunning(context);
        throw new Error('远程音频打开已取消');
      }
      this.backgroundContext = context;
      this.backgroundStarted = true;
      this.backgroundRevision = revision;
      const ownedSession = await avSession.createAVSession(context, 'MyToolsRemoteAudio', 'audio');
      session = ownedSession;
      if (revision !== this.operationRevision) {
        await ownedSession.destroy();
        session = undefined;
        await this.stopSystemSession(revision);
        throw new Error('远程音频打开已取消');
      }
      this.session = ownedSession;
      this.sessionRevision = revision;
      ownedSession.on('play', () => {
        if (this.currentSystemSession(ownedSession, revision)) this.playFromSystem();
      });
      ownedSession.on('pause', () => {
        if (this.currentSystemSession(ownedSession, revision)) this.pauseFromSystem();
      });
      ownedSession.on('stop', () => {
        if (this.currentSystemSession(ownedSession, revision)) this.release();
      });
      ownedSession.on('playNext', () => {
        if (this.currentSystemSession(ownedSession, revision)) this.onPlayNext();
      });
      ownedSession.on('playPrevious', () => {
        if (this.currentSystemSession(ownedSession, revision)) this.onPlayPrevious();
      });
      ownedSession.on('seek', (time: number) => {
        if (this.currentSystemSession(ownedSession, revision)) this.seek(time);
      });
      ownedSession.on('fastForward', (time?: number) => {
        if (this.currentSystemSession(ownedSession, revision)) this.seekOffset(time ?? 10000);
      });
      ownedSession.on('rewind', (time?: number) => {
        if (this.currentSystemSession(ownedSession, revision)) this.seekOffset(-(time ?? 10000));
      });
      ownedSession.on('setSpeed', (speed: number) => {
        if (!this.currentSystemSession(ownedSession, revision)) return;
        try {
          this.setPlaybackSpeed(speed);
        } catch (_) {
        }
      });
      await ownedSession.setAVMetadata({ assetId: assetId, title: title, avQueueName: 'MyTools远程音频' });
      if (!this.currentSystemSession(ownedSession, revision)) throw new Error('远程音频打开已取消');
      await ownedSession.activate();
      if (!this.currentSystemSession(ownedSession, revision)) throw new Error('远程音频打开已取消');
      const output = await ownedSession.getOutputDevice();
      if (this.currentSystemSession(ownedSession, revision)) this.notifyOutputDevice(output);
      ownedSession.on('outputDeviceChange', (_state: avSession.ConnectionState, device: avSession.OutputDeviceInfo) => {
        if (this.currentSystemSession(ownedSession, revision)) this.notifyOutputDevice(device);
      });
    } catch (error) {
      if (session !== undefined && this.session !== session) {
        try {
          await session.destroy();
        } catch (_) {
        }
      }
      await this.stopSystemSession(revision);
      if (error instanceof Error && error.message === '远程音频打开已取消') throw error;
      throw new Error('无法启动后台音频会话');
    }
  }

  private notifyOutputDevice(output: avSession.OutputDeviceInfo): void {
    const names = output.devices.map((value: avSession.DeviceInfo) => value.deviceName)
      .filter((value: string) => value.length > 0);
    this.onOutputDeviceChange(names.length === 0 ? '系统默认输出' : names.join('、'));
  }

  private seekOffset(offsetMs: number): void {
    const target = this.seekPolicy.offset(this.lastTimeMs, offsetMs, this.durationMs);
    if (target !== undefined) this.seek(target);
  }

  private currentPlayerEvent(player: media.AVPlayer, revision: number): boolean {
    return this.revisionPolicy.current(revision, this.operationRevision, this.player === player);
  }

  private currentSystemSession(session: avSession.AVSession, revision: number): boolean {
    return this.revisionPolicy.current(revision, this.operationRevision,
      this.session === session && this.sessionRevision === revision);
  }

  private playFromSystem(): void {
    const player = this.player;
    if (player === undefined) return;
    player.play().catch(() => console.error('AVSession play command failed'));
  }

  private pauseFromSystem(): void {
    const player = this.player;
    if (player === undefined || this.state !== 'playing') return;
    player.pause().catch(() => console.error('AVSession pause command failed'));
  }

  private publishPlaybackState(): void {
    const session = this.session;
    if (session === undefined) return;
    this.lastStatePublishedAt = Date.now();
    let state = avSession.PlaybackState.PLAYBACK_STATE_INITIAL;
    if (this.state === 'playing') state = avSession.PlaybackState.PLAYBACK_STATE_PLAY;
    else if (this.state === 'paused') state = avSession.PlaybackState.PLAYBACK_STATE_PAUSE;
    else if (this.state === 'prepared') state = avSession.PlaybackState.PLAYBACK_STATE_PREPARE;
    else if (this.state === 'completed') state = avSession.PlaybackState.PLAYBACK_STATE_COMPLETED;
    session.setAVPlaybackState({
      state: state,
      speed: this.speedValue,
      position: { elapsedTime: this.lastTimeMs, updateTime: Date.now() },
      bufferedTime: this.durationMs,
      loopMode: this.loopEnabled ? avSession.LoopMode.LOOP_MODE_SINGLE : avSession.LoopMode.LOOP_MODE_SEQUENCE
    }).catch(() => console.error('Failed to publish AVSession state'));
  }

  private async stopSystemSession(expectedRevision?: number): Promise<void> {
    if (expectedRevision !== undefined && this.sessionRevision !== expectedRevision &&
      this.backgroundRevision !== expectedRevision) return;
    const ownsSession = expectedRevision === undefined || this.sessionRevision === expectedRevision;
    const session = ownsSession ? this.session : undefined;
    if (ownsSession) {
      this.session = undefined;
      this.sessionRevision = 0;
    }
    if (session !== undefined) {
      try {
        await session.deactivate();
        await session.destroy();
      } catch (_) {
      }
    }
    const context = this.backgroundContext;
    const ownsBackground = expectedRevision === undefined || this.backgroundRevision === expectedRevision;
    if (ownsBackground) {
      this.backgroundContext = undefined;
      this.backgroundRevision = 0;
    }
    if (ownsBackground && this.backgroundStarted && context !== undefined) {
      this.backgroundStarted = false;
      try {
        await backgroundTaskManager.stopBackgroundRunning(context);
      } catch (_) {
      }
    }
  }

  private async prepareCurrentPlayer(player: media.AVPlayer, revision: number): Promise<void> {
    try {
      if (!this.currentPlayerEvent(player, revision)) return;
      await player.prepare();
    } catch (error) {
      if (!this.currentPlayerEvent(player, revision)) return;
      console.error('Failed to prepare remote player');
      this.onError('远程音频准备失败，请重试');
    }
  }

  private async playCurrentPlayer(player: media.AVPlayer, revision: number): Promise<void> {
    try {
      if (!this.currentPlayerEvent(player, revision)) return;
      player.loop = this.loopEnabled;
      player.setSpeed(this.playbackSpeed);
      if (this.pendingStartMs > 0) {
        player.seek(this.pendingStartMs, media.SeekMode.SEEK_CLOSEST);
        this.pendingStartMs = 0;
      }
      await player.play();
    } catch (error) {
      if (!this.currentPlayerEvent(player, revision)) return;
      console.error('Failed to start remote player');
      this.onError('远程音频启动失败，请重试');
    }
  }
}
