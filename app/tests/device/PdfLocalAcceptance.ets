import fs from '@ohos.file.fs';
import { pdfService } from '@kit.PDFKit';
import { PdfLocalEngine, LocalPdfRequest, LocalPdfInput, LocalPdfJob } from '../features/tools/pdf/PdfLocalEngine';

/** 独立验收页面，仅由验收脚本临时纳入测试包。 */
@Entry
@Component
struct PdfLocalAcceptance {
  @State state:string='RUNNING';
  private log:string[]=[];
  /** 自动运行纯本机引擎验收。 */
  aboutToAppear():void {this.run();}
  private assert(value:boolean,message:string):void {if(!value)throw new Error(message);this.log.push(`PASS ${message}`);}
  private async run():Promise<void>{
    const context=this.getUIContext().getHostContext();if(!context)return;
    const root=`${context.filesDir}/pdf-acceptance`;try{fs.mkdirSync(root,true);}catch(_){}
    const engine=new PdfLocalEngine(`${root}/cache`,root);
    const r:LocalPdfRequest={operation:'TEXT_TO_PDF',inputs:[],text:'PDF local verification\nChinese: 中文测试\nSecond line',name:'text.pdf',ranges:'',groups:'',perFile:1,vertical:true,ratio:.5,grouped:false,dpi:96,jpeg:false,a5:false,margin:10,pageNumbers:true};
    const execute=async():Promise<LocalPdfJob>=>engine.run(r,()=>true,(_n:number)=>{});
    try{
      const text=await execute();this.assert(text.artifacts[0].pages===1,'text-one-page');
      const input=await engine.importFile(text.artifacts[0].path);r.inputs=[input];r.operation='PDF_TO_TEXT';
      const extracted=await execute();this.assert(extracted.text.includes('Chinese'),'extract-english');this.assert(extracted.text.includes('\u4e2d\u6587'),'extract-chinese');
      r.operation='MARKDOWN_TO_PDF';r.text='# Heading\n\n- First\n- Second';r.inputs=[];r.name='markdown.pdf';const md=await execute();this.assert(md.artifacts[0].pages===1,'markdown');
      const source=new pdfService.PdfDocument();source.createDocument(600,400);const initial=source.getPageCount();
      const style=new pdfService.TextStyle();style.textSize=30;const font=new pdfService.FontInfo();font.fontPath='/system/fonts/HarmonyOS_Sans_SC.ttf';font.fontName='HarmonyOS Sans';style.fontInfo=font;style.textColor=0x000000;
      for(let i=0;i<2;i++){const page=source.insertBlankPage(source.getPageCount(),600,400);page.addTextObject(`LEFT-${i+1}`,40,280,style);page.addTextObject(`RIGHT-${i+1}`,340,100,style);page.release();}
      if(initial)source.deletePage(0,initial);source.saveDocument(`${root}/source.pdf`);source.releaseDocument();
      const two=await engine.importFile(`${root}/source.pdf`);this.assert(two.pages===2,'source-two-pages');r.inputs=[two];r.operation='PDF_PAGE_CUT';r.name='cut.pdf';
      const cut=await execute();this.assert(cut.artifacts[0].pages===4,'cut-four-pages');const cutDoc=new pdfService.PdfDocument();cutDoc.loadDocument(cut.artifacts[0].path);
      for(let i=0;i<4;i++){const page=cutDoc.getPage(i);const box=page.getBox(pdfService.BoxType.BOX_CROP);this.log.push(`CUT ${i} ${JSON.stringify(box)} ${page.getWidth()}x${page.getHeight()}`);this.assert(Math.abs(page.getWidth()-300)<1,'cut-width-'+i);this.assert(page.getGraphicsObjects().some((o:pdfService.GraphicsObject)=>o.type===pdfService.GraphicsObjectType.OBJECT_TEXT),'text-layer-'+i);page.release();}cutDoc.releaseDocument();
      r.name='cut-horizontal.pdf';r.vertical=false;const horizontal=await execute();this.assert(horizontal.artifacts[0].pages===4,'horizontal');
      r.vertical=true;r.ranges='1';r.name='cut-subset.pdf';this.assert((await execute()).artifacts[0].pages===3,'cut-retain-unselected');r.ranges='';
      r.operation='PDF_SPLIT';r.name='split.pdf';const split=await execute();this.assert(split.artifacts.length===2&&split.artifacts.every(a=>a.pages===1),'split-two-files');
      r.operation='PDF_MERGE';r.name='merged.pdf';r.inputs=[await engine.importFile(split.artifacts[0].path),await engine.importFile(split.artifacts[1].path)];this.assert((await execute()).artifacts[0].pages===2,'merge-two-pages');
      r.operation='PDF_TO_IMAGES';r.name='export.pdf';r.inputs=[two];const images=await execute();this.assert(images.artifacts.length===2&&images.artifacts[0].bytes>100,'export-png');
      r.jpeg=true;r.name='export-jpg.pdf';this.assert((await execute()).artifacts[0].mime==='image/jpeg','export-jpeg');
      r.operation='IMAGES_TO_PDF';r.name='images.pdf';r.inputs=[await engine.importFile(images.artifacts[0].path),await engine.importFile(images.artifacts[1].path)];this.assert((await execute()).artifacts[0].pages===2,'images-to-pdf');
      r.operation='PDF_PAGE_CUT';r.inputs=[two];r.ranges='99';let rejected=false;try{await execute();}catch(_){rejected=true;}this.assert(rejected,'invalid-range');
      r.ranges='';let cancelled=false;try{await engine.run(r,()=>false,(_n:number)=>{});}catch(_){cancelled=true;}this.assert(cancelled,'cancel-no-result');
      const path=await engine.thumbnail(two,0);this.assert(fs.statSync(path).size>100,'source-thumbnail');
      await engine.copy(cut.artifacts[0].path,`${root}/saved-copy.pdf`);this.assert(fs.statSync(`${root}/saved-copy.pdf`).size===cut.artifacts[0].bytes,'saved-file-exact-size');
      for(const rotation of [0,90,180,270]) {
        const fixture=`pdf-acceptance-${rotation}.pdf`;const data=await context.resourceManager.getRawFileContent(fixture);const f=fs.openSync(`${root}/${fixture}`,fs.OpenMode.CREATE|fs.OpenMode.WRITE_ONLY|fs.OpenMode.TRUNC);fs.writeSync(f.fd,data.buffer);fs.closeSync(f);
        r.inputs=[await engine.importFile(`${root}/${fixture}`)];r.operation='PDF_PAGE_CUT';r.ratio=.25;
        for(const vertical of [true,false]) {r.vertical=vertical;r.name=`rotated-${rotation}-${vertical?'v':'h'}.pdf`;const j=await execute();this.assert(j.artifacts[0].pages===2,`rotated-${rotation}-${vertical}`);}
      }
      const encrypted='pdf-acceptance-encrypted.pdf';const data=await context.resourceManager.getRawFileContent(encrypted);const f=fs.openSync(`${root}/${encrypted}`,fs.OpenMode.CREATE|fs.OpenMode.WRITE_ONLY|fs.OpenMode.TRUNC);fs.writeSync(f.fd,data.buffer);fs.closeSync(f);
      let wrong=false;try{await engine.importFile(`${root}/${encrypted}`,'wrong');}catch(_){wrong=true;}this.assert(wrong,'password-rejected');
      const unlocked=await engine.importFile(`${root}/${encrypted}`,'test-only-pdf-password');this.assert(unlocked.pages===1,'password-unlocked');
      r.inputs=[unlocked];r.name='unlocked-cut.pdf';this.assert((await execute()).artifacts[0].pages===2,'encrypted-cut');
      r.operation='TEXT_TO_PDF';r.inputs=[];r.text='Long text line\n'.repeat(160);r.name='multi-text.pdf';this.assert((await execute()).artifacts[0].pages>1,'text-pagination');
      this.assert(engine.history().length>=10,'persistent-history');this.state='PASSED';
    }catch(error){this.state='FAILED';this.log.push(`FAIL ${(error as Error).message}`);}
    try{const file=fs.openSync(`${root}/report.txt`,fs.OpenMode.CREATE|fs.OpenMode.WRITE_ONLY|fs.OpenMode.TRUNC);fs.writeSync(file.fd,this.state+'\n'+this.log.join('\n'));fs.closeSync(file);}catch(_){}
  }
  build(){Column(){Text(this.state).fontSize(30);Text('PDF LOCAL ACCEPTANCE').fontSize(20)}.width('100%').height('100%').justifyContent(FlexAlign.Center)}
}
