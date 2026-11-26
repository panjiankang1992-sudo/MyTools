import { pdfService } from '@kit.PDFKit';
import { image } from '@kit.ImageKit';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import { PdfLocalPolicy, PdfBounds } from './PdfLocalPolicy';
import { PdfErrorCode } from './common/PdfErrorCode';

export interface LocalPdfInput { id: string; path: string; name: string; bytes: number; pages: number; width: number; height: number; password: string; }
export interface LocalPdfArtifact { path: string; name: string; bytes: number; pages: number; mime: string; }
export interface LocalPdfJob { id: string; operation: string; createdAt: string; artifacts: LocalPdfArtifact[]; text: string; }
export interface LocalPdfRequest { operation: string; inputs: LocalPdfInput[]; text: string; name: string; ranges: string; groups: string; perFile: number; vertical: boolean; ratio: number; grouped: boolean; dpi: number; jpeg: boolean; a5: boolean; margin: number; pageNumbers: boolean; }

/** 本机 PDF 引擎。所有文件留在沙箱，不依赖登录、网络或服务端能力查询。 */
export class PdfLocalEngine {
  private work: string;
  private outputs: string;
  /** 初始化本地工作区和持久化结果目录。 */
  constructor(cacheDir: string, filesDir: string) {
    try {
    this.work = `${cacheDir}/pdf-local`; this.outputs = `${filesDir}/pdf-local`;
    if (!fs.accessSync(this.work)) fs.mkdirSync(this.work, true); if (!fs.accessSync(this.outputs)) fs.mkdirSync(this.outputs, true);
  
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
  /** 将授权文件复制到工作区并读取实际文件信息，密码仅保留在会话。 */
  async importFile(uri: string, password: string = ''): Promise<LocalPdfInput> {
    try {
    const id = util.generateRandomUUID();
    const name = decodeURIComponent(uri.substring(uri.lastIndexOf('/')+1));
    const extension = name.split('.').pop()?.toLowerCase() ?? '';
    if (!['pdf','png','jpg','jpeg','txt','md'].includes(extension)) throw new Error(PdfErrorCode.FILE);
    const path = `${this.work}/${id}.${extension}`;
    const input: LocalPdfInput = { id, path, name, bytes: 0, pages: 0, width: 0, height: 0, password };
    try {
      const source = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      try { const bytes = fs.statSync(source.fd).size; if (bytes < 1 || bytes > 100*1024*1024) throw new Error(PdfErrorCode.LIMIT); }
      finally { fs.closeSync(source); }
      await this.copy(uri, path);
      input.bytes = fs.statSync(path).size;
      // 当前版本采用保守内存边界，超限在本机提示，不自动上传。
      if (input.bytes < 1 || input.bytes > 100*1024*1024) throw new Error(PdfErrorCode.LIMIT);
      if (extension === 'pdf') {
        const doc = this.open(path, password);
        try {
          input.pages = doc.getPageCount();
          if (input.pages < 1 || input.pages > 500) throw new Error(PdfErrorCode.LIMIT);
          const page = doc.getPage(0);
          try { input.width = page.getWidth(); input.height = page.getHeight(); } finally { page.release(); }
        } finally { doc.releaseDocument(); }
      } else if (['png','jpg','jpeg'].includes(extension)) {
        const source = image.createImageSource(path);
        try { const info = await source.getImageInfo(); input.width = info.size.width; input.height = info.size.height; }
        finally { source.release(); }
      } else if (input.bytes > 256*1024) throw new Error(PdfErrorCode.LIMIT);
      return input;
    } catch (error) { this.remove(path); throw error as Error; }
  
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
  /** 读取本地 UTF-8 文本，拒绝无效编码。 */
  readText(input: LocalPdfInput): string {
    try {
    return fs.readTextSync(input.path);
  
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
  /** 只为当前可见页生成缩略图，不一次性渲染所有页面。 */
  async thumbnail(input: LocalPdfInput, index: number): Promise<string> {
    try {
    if (!Number.isInteger(index) || index < 0 || index >= input.pages) throw new Error(PdfErrorCode.INVALID);
    const path = `${this.work}/${input.id}-${index}.png`;
    if (fs.accessSync(path)) return path;
    const doc = this.open(input.path, input.password);
    try { await this.render(doc, index, path, 72, false, true); } finally { doc.releaseDocument(); }
    return path;
  
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
  /** 获取当前页的视觉比例，包含旋转和不同页面尺寸。 */
  aspect(input: LocalPdfInput, index: number): number {
    const doc = this.open(input.path, input.password);
    try { const page = doc.getPage(index); try { return page.getWidth() / Math.max(1,page.getHeight()); } finally { page.release(); } }
    finally { doc.releaseDocument(); }
  }
  /** 分页执行，并在安全边界响应取消；只有验证成功才发布持久化结果。 */
  async run(request: LocalPdfRequest, active: () => boolean, progress: (value: number) => void): Promise<LocalPdfJob> {
    try {
    const id = util.generateRandomUUID();
    const dir = `${this.outputs}/${id}`; fs.mkdirSync(dir);
    const job: LocalPdfJob = { id, operation: request.operation, createdAt: new Date().toISOString(), artifacts: [], text: '' };
    const tick = async (value: number): Promise<void> => {
      if (!active()) throw new Error(PdfErrorCode.CANCELLED);
      progress(value); await new Promise<void>((resolve) => setTimeout(resolve, 1));
      if (!active()) throw new Error(PdfErrorCode.CANCELLED);
    };
    try {
      await tick(0);
      if (request.operation === 'TEXT_TO_PDF' || request.operation === 'MARKDOWN_TO_PDF') await this.textPdf(request, dir, job, tick);
      else if (request.operation === 'IMAGES_TO_PDF') await this.imagesPdf(request, dir, job, tick);
      else {
        if (!request.inputs.length || request.inputs.some((v: LocalPdfInput) => v.pages < 1)) throw new Error(PdfErrorCode.INVALID);
        if (request.operation === 'PDF_MERGE') await this.merge(request, dir, job, tick);
        else {
          const doc = this.open(request.inputs[0].path, request.inputs[0].password);
          try {
            const selected = PdfLocalPolicy.pages(request.ranges, doc.getPageCount());
            if (request.operation === 'PDF_PAGE_CUT') {
              const out = this.blank(); const initial = out.getPageCount();
              try {
                const pieces = PdfLocalPolicy.pieces(doc.getPageCount(), selected, request.grouped);
                for (let i = 0; i < pieces.length; i++) {
                  const part = pieces[i];
                  const page = out.insertPageFromDocument(doc, part.page, 1, out.getPageCount());
                  try {
                    if (part.side >= 0) {
                      const media = page.getBox(pdfService.BoxType.BOX_MEDIA);
                      const crop = page.getBox(pdfService.BoxType.BOX_CROP);
                      const b: PdfBounds = { left: Math.max(media.left,crop.left), right: Math.min(media.right,crop.right), top: Math.min(media.top,crop.top), bottom: Math.max(media.bottom,crop.bottom) };
                      const cut = PdfLocalPolicy.cut(b, page.getRotation(), request.vertical, request.ratio, part.side);
                      const rect = new pdfService.PdfRect(); rect.left=cut.left; rect.right=cut.right; rect.top=cut.top; rect.bottom=cut.bottom;
                      page.setBox(pdfService.BoxType.BOX_CROP, rect);
                      page.setBox(pdfService.BoxType.BOX_MEDIA, rect);
                    }
                  } finally { page.release(); }
                  await tick((i+1)*90/pieces.length);
                }
                if (initial) out.deletePage(0,initial);
                job.artifacts.push(this.savePdf(out,dir,request.name,pieces.length));
              } finally { out.releaseDocument(); }
            } else if (request.operation === 'PDF_SPLIT') {
              let groups: number[][] = [];
              if (request.groups.trim()) groups = request.groups.split(';').map((g: string) => { if (!g.trim()) throw new Error(PdfErrorCode.INVALID); return PdfLocalPolicy.pages(g,doc.getPageCount()); });
              else {
                if (!Number.isInteger(request.perFile) || request.perFile < 1) throw new Error(PdfErrorCode.INVALID);
                for (let i=0;i<selected.length;i+=request.perFile) groups.push(selected.slice(i,i+request.perFile));
              }
              for (let i=0;i<groups.length;i++) {
                const out=this.blank(); const initial=out.getPageCount();
                try {
                  for (const p of groups[i]) { const page=out.insertPageFromDocument(doc,p,1,out.getPageCount());page.release(); await tick(i*90/groups.length); }
                  if(initial) out.deletePage(0,initial);
                  job.artifacts.push(this.savePdf(out,dir,`${PdfLocalPolicy.outputName(request.name,'pdf').slice(0,-4)}-${i+1}.pdf`,groups[i].length));
                } finally { out.releaseDocument(); }
              }
            } else if (request.operation === 'PDF_TO_IMAGES') {
              for(let i=0;i<selected.length;i++) {
                const name=`${PdfLocalPolicy.outputName(request.name,'pdf').slice(0,-4)}-${selected[i]+1}.${request.jpeg?'jpg':'png'}`;
                const path=`${dir}/${name}`;
                await this.render(doc,selected[i],path,request.dpi,request.jpeg,false);
                job.artifacts.push({path,name,bytes:fs.statSync(path).size,pages:1,mime:request.jpeg?'image/jpeg':'image/png'});
                await tick((i+1)*90/selected.length);
              }
            } else if (request.operation === 'PDF_TO_TEXT') {
              const lines:string[]=[];
              for(let i=0;i<selected.length;i++) {
                const page=doc.getPage(selected[i]);
                try { lines.push(page.getGraphicsObjects().filter((o:pdfService.GraphicsObject)=>o.type===pdfService.GraphicsObjectType.OBJECT_TEXT).map((o:pdfService.GraphicsObject)=>(o as pdfService.TextObject).text).join('\n')); }
                finally {page.release();}
                await tick((i+1)*90/selected.length);
              }
              const text=lines.join('\n\n'); if(!text.trim()) throw new Error(PdfErrorCode.NO_TEXT);
              const name=PdfLocalPolicy.outputName(request.name,'txt'); const path=`${dir}/${name}`;
              this.write(path,text); job.text=text.slice(0,3000); job.artifacts.push({path,name,bytes:fs.statSync(path).size,pages:0,mime:'text/plain'});
            } else throw new Error(PdfErrorCode.INVALID);
          } finally { doc.releaseDocument(); }
        }
      }
      await tick(100);
      this.write(`${dir}/job.json`,JSON.stringify(job));
      return job;
    } catch(error) { try { fs.rmdirSync(dir); } catch(_) {} throw error as Error; }
  
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
  /** 读取已验证的本机结果，不读取密码或原始输入正文。 */
  history(): LocalPdfJob[] {
    try {
    const jobs:LocalPdfJob[]=[];
    for(const name of fs.listFileSync(this.outputs)) {
      if(!/^[0-9a-f-]{36}$/.test(name)) continue;
      try { const job=JSON.parse(fs.readTextSync(`${this.outputs}/${name}/job.json`)) as LocalPdfJob;
        if(job.id===name && job.artifacts.every((a:LocalPdfArtifact)=>a.path.startsWith(`${this.outputs}/${name}/`)&&fs.accessSync(a.path))) jobs.push(job);
      } catch(_) {}
    }
    return jobs.sort((a:LocalPdfJob,b:LocalPdfJob)=>b.createdAt.localeCompare(a.createdAt));
  
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
  /** 保存到系统授权位置，处理短写入并关闭两侧句柄。 */
  async copy(source: string, target: string): Promise<void> {
    try {
    const input=fs.openSync(source,fs.OpenMode.READ_ONLY);
    let output:fs.File|undefined=undefined;
    try {
      output=fs.openSync(target,fs.OpenMode.CREATE|fs.OpenMode.WRITE_ONLY|fs.OpenMode.TRUNC);
      const buffer=new ArrayBuffer(1024*1024); let read=0;
      while((read=fs.readSync(input.fd,buffer))>0) {
        let written=0;
        while(written<read) { const n=fs.writeSync(output.fd,buffer.slice(written,read)); if(n<=0) throw new Error(PdfErrorCode.WRITE); written+=n; }
        await new Promise<void>((resolve)=>setTimeout(resolve,1));
      }
      fs.fsyncSync(output.fd);
    } finally { fs.closeSync(input); if(output) fs.closeSync(output); }
  
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
  /** 移除单次会话的导入副本与缩略图，不删除已完成结果。 */
  dispose(inputs:LocalPdfInput[]):void {
    try {
    for(const input of inputs) { this.remove(input.path); for(const file of fs.listFileSync(this.work)) if(file.startsWith(`${input.id}-`)) this.remove(`${this.work}/${file}`); }
  
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
  private open(path:string,password:string=''):pdfService.PdfDocument {
    try {
    const doc=new pdfService.PdfDocument(); const result=doc.loadDocument(path,password);
    if(result!==pdfService.ParseResult.PARSE_SUCCESS) {doc.releaseDocument();throw new Error(result===pdfService.ParseResult.PARSE_ERROR_PASSWORD?PdfErrorCode.PASSWORD:PdfErrorCode.FILE);}
    return doc;
  
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
  private blank():pdfService.PdfDocument {
    try {
    const doc=new pdfService.PdfDocument(); if(!doc.createDocument(595,842)) {doc.releaseDocument();throw new Error(PdfErrorCode.WRITE);} return doc;
  
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
  private savePdf(doc:pdfService.PdfDocument,dir:string,value:string,count:number):LocalPdfArtifact {
    try {
    const name=PdfLocalPolicy.outputName(value,'pdf'); const path=`${dir}/${name}`; const pending=`${path}.pending`;
    if(!doc.saveDocument(pending)) throw new Error(PdfErrorCode.WRITE);
    const check=this.open(pending);
    try {if(check.getPageCount()!==count || count<1) throw new Error(PdfErrorCode.WRITE);} finally {check.releaseDocument();}
    fs.renameSync(pending,path);return {path,name,bytes:fs.statSync(path).size,pages:count,mime:'application/pdf'};
  
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
  private async render(doc:pdfService.PdfDocument,index:number,path:string,dpi:number,jpeg:boolean,preview:boolean):Promise<void> {
    try {
    const page=doc.getPage(index);
    let pixels:image.PixelMap|undefined=undefined;const packer=image.createImagePacker();
    try {
      const w=page.getWidth(),h=page.getHeight(); let scale=dpi/72;
      if(preview) scale=Math.min(1,900/Math.max(w,h));
      if(w*h*scale*scale>12000000) throw new Error(PdfErrorCode.LIMIT);
      const matrix=new pdfService.PdfMatrix();matrix.x=0;matrix.y=0;matrix.width=Math.ceil(w*scale);matrix.height=Math.ceil(h*scale);matrix.rotate=0;
      pixels=page.getCustomPagePixelMap(matrix,false,true);
      const file=fs.openSync(path,fs.OpenMode.CREATE|fs.OpenMode.WRITE_ONLY|fs.OpenMode.TRUNC);
      try {await packer.packToFile(pixels,file.fd,{format:jpeg?'image/jpeg':'image/png',quality:95});} finally {fs.closeSync(file);}
    } finally {if(pixels) await pixels.release();await packer.release();page.release();}
  
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
  private async merge(r:LocalPdfRequest,dir:string,job:LocalPdfJob,tick:(n:number)=>Promise<void>):Promise<void> {
    try {
    if(r.inputs.length<2) throw new Error(PdfErrorCode.INVALID);
    const out=this.blank();const initial=out.getPageCount();let count=0;
    try {
      for(let i=0;i<r.inputs.length;i++) {
        const doc=this.open(r.inputs[i].path,r.inputs[i].password);
        try { for(let p=0;p<doc.getPageCount();p++) {const page=out.insertPageFromDocument(doc,p,1,out.getPageCount());page.release();count++;await tick(i*90/r.inputs.length);} }
        finally {doc.releaseDocument();}
      }
      if(initial)out.deletePage(0,initial);job.artifacts.push(this.savePdf(out,dir,r.name,count));
    } finally {out.releaseDocument();}
  
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
  private async imagesPdf(r:LocalPdfRequest,dir:string,job:LocalPdfJob,tick:(n:number)=>Promise<void>):Promise<void> {
    try {
    if(!r.inputs.length)throw new Error(PdfErrorCode.INVALID);
    const out=this.blank();const initial=out.getPageCount();const w=r.a5?420:595,h=r.a5?595:842,m=r.margin*72/25.4;
    try {
      for(let i=0;i<r.inputs.length;i++) {
        const input=r.inputs[i];if(input.pages || input.width<=0 || input.height<=0)throw new Error(PdfErrorCode.INVALID);
        const page=out.insertBlankPage(out.getPageCount(),w,h);
        try {const scale=Math.min((w-2*m)/input.width,(h-2*m)/input.height);const iw=input.width*scale,ih=input.height*scale;page.addImageObject(input.path,(w-iw)/2,(h-ih)/2,iw,ih);}
        finally {page.release();}
        await tick((i+1)*90/r.inputs.length);
      }
      if(initial)out.deletePage(0,initial);job.artifacts.push(this.savePdf(out,dir,r.name,r.inputs.length));
    } finally {out.releaseDocument();}
  
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
  private async textPdf(r:LocalPdfRequest,dir:string,job:LocalPdfJob,tick:(n:number)=>Promise<void>):Promise<void> {
    try {
    const text=r.text;if(!text.trim() || text.length>60000)throw new Error(PdfErrorCode.INVALID);
    // 基础 Markdown 保留段落、标题和列表；无法正确布局的语法明确拒绝。
    if(r.operation==='MARKDOWN_TO_PDF' && /!\[|<\/?[a-zA-Z]|^\s*\|/m.test(text))throw new Error(PdfErrorCode.MARKDOWN);
    const out=this.blank();const initial=out.getPageCount();const w=r.a5?420:595,h=r.a5?595:842,m=r.margin*72/25.4;
    const font=new pdfService.FontInfo();font.fontName='HarmonyOS Sans';font.fontPath='/system/fonts/HarmonyOS_Sans_SC.ttf';
    const style=new pdfService.TextStyle();style.fontInfo=font;style.textSize=12;style.textColor=0x1e2622;
    let page:pdfService.PdfPage|undefined=undefined;let y=0;let count=0;
    try {
      const lines=text.replace(/\r\n?/g,'\n').split('\n');
      for(let i=0;i<lines.length;i++) {
        let line=lines[i]; const heading=r.operation==='MARKDOWN_TO_PDF' && /^#{1,6}\s/.test(line);
        style.textSize=heading?18:12;style.isBold=heading;
        if(r.operation==='MARKDOWN_TO_PDF')line=line.replace(/^#{1,6}\s+/,'').replace(/\*\*(.*?)\*\*/g,'$1').replace(/`([^`]+)`/g,'$1').replace(/\[([^\]]+)\]\([^)]*\)/g,'$1');
        const segments:string[]=[];let segment='',width=0;
        for(const ch of line) {const advance=(ch.charCodeAt(0)>255?1:0.62)*(style.textSize??12);if(width+advance>w-2*m && segment){segments.push(segment);segment='';width=0;}segment+=ch;width+=advance;}
        segments.push(segment);
        for(const value of segments) {
          if(!page || y<m+30) {if(page)page.release();page=out.insertBlankPage(out.getPageCount(),w,h);count++;y=h-m-24;
            if(r.pageNumbers){const footer=new pdfService.TextStyle();footer.fontInfo=font;footer.textSize=10;page.addTextObject(`${count}`,w/2,m/2,footer);}}
          if(value)page.addTextObject(value,m,y,style);y-=(style.textSize??12)*1.6;
        }
        await tick((i+1)*90/lines.length);
      }
      if(page){page.release();page=undefined;}if(initial)out.deletePage(0,initial);job.artifacts.push(this.savePdf(out,dir,r.name,count));
    } finally {if(page)page.release();out.releaseDocument();}
  
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
  private write(path:string,text:string):void {
    try {const file=fs.openSync(path,fs.OpenMode.CREATE|fs.OpenMode.WRITE_ONLY|fs.OpenMode.TRUNC);try{fs.writeSync(file.fd,text);}finally{fs.closeSync(file);}
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
  private remove(path:string):void {
    try {try{fs.unlinkSync(path);}catch(_) {}
    } catch (error) { throw new Error((error as Error).message || PdfErrorCode.FILE); }
  }
}
