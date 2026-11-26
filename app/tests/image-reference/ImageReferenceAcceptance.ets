import fs from '@ohos.file.fs';
import http from '@ohos.net.http';
import { common } from '@kit.AbilityKit';
import { ImageReferencePreparation } from '../features/tools/ImageReferencePreparation';

@Entry
@Component
struct ImageReferenceAcceptance {
  @State status: string = 'Running native image preparation';
  aboutToAppear(): void { this.run(); }
  private async run(): Promise<void> {
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      for (const name of ['over-one.png', 'over-five.png', 'high-resolution.jpg']) {
        const data = await context.resourceManager.getRawFileContent(name);
        const path = `${context.cacheDir}/${name}`;
        const file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
        fs.writeSync(file.fd, data.buffer); fs.closeSync(file);
        const input = fs.openSync(path, fs.OpenMode.READ_ONLY);
        try {
          const result = await ImageReferencePreparation.prepare(input.fd);
          const request = http.createHttp();
          try {
            const reply = await request.request(`http://127.0.0.1:23111/${name}`, {
              method: http.RequestMethod.POST, header: {'Content-Type':'application/octet-stream'}, extraData:result.bytes });
            if (reply.responseCode !== 200) throw new Error('capture_failed');
          } finally { request.destroy(); }
          this.status += `\n${name}: ${data.byteLength} -> ${result.bytes.byteLength}, resized=${result.resized}`;
        } finally { fs.closeSync(input); }
      }
      this.status += '\nPASS';
    } catch (error) { this.status += `\nFAIL ${error}`; }
  }
  build() { Column() { Text(this.status).fontSize(18) }.width('100%').height('100%').padding(30) }
}
