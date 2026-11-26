import fs from '@ohos.file.fs';
import picker from '@ohos.file.picker';
import fileUri from '@ohos.file.fileuri';
import { common } from '@kit.AbilityKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { VideoGenerationPage } from '../../features/tools/VideoGenerationPage';
import { AuthSessionManager } from '../../features/auth/AuthSessionManager';

/** 仅由隔离构建脚本注入；不读取或修改真实登录会话。 */
class AcceptanceAuth extends AuthSessionManager {
  identity: string = 'acceptance-a';
  normalizeBusinessBaseUrl(value: string): string { return 'http://127.0.0.1:23110'; }
  async validAccessToken(): Promise<string> { return this.identity; }
  async forceRefresh(rejectedAccessToken?: string): Promise<string> { return this.identity; }
}

/** 只在临时副本编译的验收入口，复用正式页面和 HTTP 客户端。 */
@Entry
@Component
struct VideoAcceptancePage {
  @State account: string = 'acceptance-a';
  @State visible: boolean = true;
  auth: AcceptanceAuth = new AcceptanceAuth();
  @State preparation: string = '';
  /** 通过系统授权保存夹具，随后正式页面仍使用真实系统选择器。 */
  private async prepareFixture(image: boolean): Promise<void> {
    const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
    try {
      const name = image ? 'acceptance.png' : 'acceptance.mp4';
      const bytes = await context.resourceManager.getRawFileContent(name);
      const path = `${context.cacheDir}/${name}`;
      const file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
      fs.writeSync(file.fd,bytes.buffer); fs.closeSync(file);
      let uris: string[] = [];
      if (image) {
        const helper = photoAccessHelper.getPhotoAccessHelper(context);
        uris = await helper.showAssetsCreationDialog([fileUri.getUriFromPath(path)],
          [{title:'video-acceptance',fileNameExtension:'png',photoType:photoAccessHelper.PhotoType.IMAGE}]);
      } else {
        const options = new picker.DocumentSaveOptions(); options.newFileNames = [name];
        uris = await new picker.DocumentViewPicker(context).save(options);
      }
      if (uris.length === 0) { this.preparation = '准备已取消'; return; }
      const output = fs.openSync(uris[0],fs.OpenMode.READ_WRITE);
      fs.writeSync(output.fd,bytes.buffer); fs.closeSync(output);
      this.preparation = image ? '图片素材已保存' : '视频素材已保存';
    } catch (error) { this.preparation = `素材准备失败：${error}`; }
  }
  build() {
    Column() {
      Text('隔离验收 · 非真实生成').fontSize(16).fontColor(Color.Red)
      Row() {
        Button('测试账号 A').onClick(() => { this.auth.identity = 'acceptance-a'; this.account = 'acceptance-a'; })
        Button('测试账号 B').onClick(() => { this.auth.identity = 'acceptance-b'; this.account = 'acceptance-b'; })
        Button(this.visible ? '离开测试页' : '返回测试页').onClick(() => this.visible = !this.visible)
      }
      Row() {
        Button('准备相册图片').onClick(() => this.prepareFixture(true))
        Button('准备文件视频').onClick(() => this.prepareFixture(false))
      }
      if (this.preparation.length > 0) { Text(this.preparation).fontSize(12) }
      if (this.visible) {
        Scroll() {
          VideoGenerationPage({baseUrl:'http://127.0.0.1:23110',accountKey:this.account,authManager:this.auth,
            onBack:() => {this.visible=false;}})
        }.layoutWeight(1).width('100%')
      }
    }.width('100%').height('100%').padding({top:44,bottom:24,left:12,right:12})
  }
}
