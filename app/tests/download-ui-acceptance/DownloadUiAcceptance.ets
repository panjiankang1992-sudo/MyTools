import { DownloadReplyCard } from '../../features/tools/download/DownloadReplyCard';
import { DownloadMessage, DownloadItem } from '../../features/tools/download/DownloadToolsApi';

/** 离线验收入口，仅在临时副本构建；不创建真实下载任务。 */
@Entry
@Component
struct DownloadUiAcceptance {
  @State sample: DownloadMessage = this.fixture('SUCCEEDED');
  @State check: string = '';
  private fixture(status: string): DownloadMessage {
    const items: DownloadItem[] = [
      { fileName: 'City walk - evening light.mp4', sizeBytes: 89456640, storageUri: 'storage://acceptance/images/20261003/city-walk.mp4', tagStatus: status === 'FAILED' ? 'FAILED' : 'TAGGED', tags: [
        { name: 'City', type: 'scene', confidence: 0.93 }, { name: 'Evening', type: 'time', confidence: 0.87 },
        { name: 'Travel', type: 'theme', confidence: 0.94 }, { name: 'Street', type: 'scene', confidence: 0.9 },
        { name: 'Walk', type: 'action', confidence: 0.88 }, { name: 'Lights', type: 'scene', confidence: 0.8 },
        { name: 'Night', type: 'time', confidence: 0.85 }] },
      { fileName: 'Cover image.png', sizeBytes: 122880, storageUri: 'storage://acceptance/images/cover.png', tagStatus: 'TAGGED', tags: [{ name: 'Cover', type: 'theme', confidence: 0.98 }] },
      { fileName: 'Ambient soundtrack.flac', sizeBytes: 7890000, storageUri: 'storage://acceptance/audio/ambient.flac', tagStatus: 'SKIPPED', tags: [] },
      { fileName: 'Notes.pdf', sizeBytes: 42201, storageUri: 'storage://acceptance/documents/notes.pdf', tagStatus: 'SKIPPED', tags: [] }
    ];
    return { id: 'fixture', requestId: 'fixture', name: 'offline acceptance', kind: 'LINKS', status, createdAt: '2026-10-03T09:00:00Z', result: { itemCount: items.length, totalBytes: 97511601, progressPercent: 46, items } };
  }
  /** 构建独立测试控制和正式回复组件。 */
  build() {
    Column({ space: 8 }) {
      Text('OFFLINE UI ACCEPTANCE').fontSize(12)
      Row({ space: 8 }) {
        Button('Success').onClick(() => this.sample = this.fixture('SUCCEEDED'))
        Button('Partial').onClick(() => this.sample = this.fixture('FAILED'))
        Button('Progress').onClick(() => this.sample = this.fixture('RUNNING'))
      }
      Row({ space: 8 }) {
        Button('Empty').onClick(() => this.sample = { id: 'fixture', requestId: '', name: '', kind: 'LINKS', status: 'FAILED', createdAt: '' })
        Button('Update').onClick(() => { const next = this.fixture('SUCCEEDED'); this.sample = next; })
      }
      Text(this.check).fontSize(12)
      Scroll() {
        DownloadReplyCard({ row: this.sample, onCancel: () => { this.check = 'CANCEL_PASS'; this.sample = this.fixture('CANCELLING'); } })
      }.layoutWeight(1).width('100%').align(Alignment.TopStart)
    }.padding({ left: 14, right: 14, top: 42, bottom: 32 }).width('100%').height('100%').backgroundColor('#F4F6F5')
  }
}
