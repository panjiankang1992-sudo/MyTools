/** 判断异步播放器事件是否仍属于当前操作代次和当前实例。 */
export class MediaOperationRevisionPolicy {
  /** 只有代次一致且实例身份一致时允许事件修改当前播放状态。 */
  current(expectedRevision: number, activeRevision: number, sameInstance: boolean): boolean {
    return Number.isSafeInteger(expectedRevision) && expectedRevision > 0 && expectedRevision === activeRevision &&
      sameInstance;
  }
}
