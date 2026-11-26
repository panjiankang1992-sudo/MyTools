import { pdfService, pdfViewManager, PdfView } from '@kit.PDFKit';
import { common } from '@kit.AbilityKit';
import picker from '@ohos.file.picker';
import { AppTheme } from '../../../theme/AppTheme';
import { AppButton, AppGroupedButton, AppEqualActionRow, AppTextAction, AppActionGroup, AppIconButton, AppGlassTextInput, AppGlassTextArea, AppSegmentItem } from '../../../components/AppControls';
import { ToolEmptyState } from '../ToolUi';
import { AuthSessionManager } from '../../auth/AuthSessionManager';
import { PdfToolsApi, PdfRecord, PdfArtifact } from './PdfToolsApi';
import { PdfLocalEngine, LocalPdfInput, LocalPdfJob, LocalPdfArtifact, LocalPdfRequest } from './PdfLocalEngine';
import { PdfLocalPolicy, PdfPiece } from './PdfLocalPolicy';
import { PdfErrorCode } from './common/PdfErrorCode';

interface PdfTool { operation: string; title: Resource; detail: Resource; icon: Resource; group: number; }
const PDF_TOOLS: PdfTool[] = [
  {operation:'PDF_PAGE_CUT',title:$r('app.string.pdf_local_cut'),detail:$r('app.string.pdf_ux_tile_cut'),icon:$r('app.media.pdf_tool_cut'),group:0},
  {operation:'PDF_SPLIT',title:$r('app.string.pdf_split'),detail:$r('app.string.pdf_ux_tile_split'),icon:$r('app.media.pdf_tool_split'),group:0},
  {operation:'PDF_MERGE',title:$r('app.string.pdf_merge'),detail:$r('app.string.pdf_ux_tile_merge'),icon:$r('app.media.pdf_tool_merge'),group:0},
  {operation:'IMAGES_TO_PDF',title:$r('app.string.pdf_images'),detail:$r('app.string.pdf_ux_tile_images'),icon:$r('sys.symbol.picture'),group:1},
  {operation:'TEXT_TO_PDF',title:$r('app.string.pdf_text'),detail:$r('app.string.pdf_ux_tile_text'),icon:$r('sys.symbol.textformat'),group:1},
  {operation:'MARKDOWN_TO_PDF',title:$r('app.string.pdf_markdown'),detail:$r('app.string.pdf_ux_tile_markdown'),icon:$r('sys.symbol.doc_text'),group:1},
  {operation:'PDF_TO_IMAGES',title:$r('app.string.pdf_to_images'),detail:$r('app.string.pdf_ux_tile_export'),icon:$r('sys.symbol.picture'),group:2},
  {operation:'PDF_TO_TEXT',title:$r('app.string.pdf_to_text'),detail:$r('app.string.pdf_ux_tile_extract'),icon:$r('sys.symbol.doc_text'),group:2}
];

/** PDF 本机工作台，所有基础操作离线完成；云端记录独立按需读取。 */
@Component
export struct PdfToolsPage {
  @Prop baseUrl: string = '';
  @Prop accountKey: string = '';
  authManager: AuthSessionManager = new AuthSessionManager();
  onBack: () => void = () => {};
  @State screen: string = 'home';
  @State operation: string = 'PDF_PAGE_CUT';
  @State inputs: LocalPdfInput[] = [];
  @State job: LocalPdfJob | undefined = undefined;
  @State history: LocalPdfJob[] = [];
  @State cloud: PdfRecord[] = [];
  @State cloudJob: PdfRecord | undefined = undefined;
  @State busy: boolean = false;
  @State processing: boolean = false;
  @State scopeDraft: string = '';
  private resultOrigin: string = 'edit';
  private continuedArtifact: LocalPdfArtifact | undefined = undefined;
  private readonly scroller: Scroller = new Scroller();
  private draggingGuide: boolean = false;
  @State progress: number = 0;
  @State message: string = '';
  @State text: string = '';
  @State name: string = 'result.pdf';
  @State ranges: string = '';
  @State groups: string = '';
  @State perFile: string = '1';
  @State vertical: boolean = true;
  @State ratio: number = 50;
  @State ratioDraft: number = 50;
  @State groupedDraft: boolean = false;
  @State splitByGroups: boolean = false;
  @State grouped: boolean = false;
  @State dpi: number = 150;
  @State jpeg: boolean = false;
  @State a5: boolean = false;
  @State marginMm: number = 10;
  @State pageNumbers: boolean = true;
  @State sourcePage: number = 0;
  @State thumbnail: string = '';
  @State outputThumbnail: string = '';
  @State sheet: boolean = false;
  @State sheetKind: string = '';
  @State password: string = '';
  @State zoom: number = 1;
  @State aspect: number = 1.5;
  private engine: PdfLocalEngine | undefined = undefined;
  private imported: LocalPdfInput[] = [];
  private active: boolean = true;
  private cancelRequested: boolean = false;
  private pendingUri: string = '';
  private thumbnailRevision: number = 0;
  private canvasWidth: number = 1;
  private canvasHeight: number = 1;
  private readonly controller: pdfViewManager.PdfController = new pdfViewManager.PdfController();
  /** 创建本地引擎，不访问网络。 */
  aboutToAppear(): void { this.active=true;try{this.local();}catch(error){this.fail(error as Error);} }
  /** 取消未完成操作并释放预览句柄。 */
  aboutToDisappear(): void {
    this.active=false;this.cancelRequested=true;this.thumbnailRevision++;
    try{this.controller.releaseDocument();}catch(_){}
    if(!this.busy)this.engine?.dispose(this.imported);
  }
  private local():PdfLocalEngine {
    if(!this.engine){const context=this.getUIContext().getHostContext() as common.UIAbilityContext;this.engine=new PdfLocalEngine(context.cacheDir,context.filesDir);}
    return this.engine;
  }
  private label(value:Resource):string {try{return this.getUIContext().getHostContext()?.resourceManager.getStringSync(value.id)??'';}catch(_){return '';}}
  private title():Resource {return PDF_TOOLS.find((t:PdfTool)=>t.operation===this.operation)?.title??$r('app.string.pdf_tools');}
  private isText():boolean {return this.operation==='TEXT_TO_PDF'||this.operation==='MARKDOWN_TO_PDF';}
  private bytes(value:number):string {if(value<1024)return `${value} B`;return value>=1048576?`${(value/1048576).toFixed(1)} MB`:`${(value/1024).toFixed(1)} KB`;}
  private fail(error:Error):void {
    let value=$r('app.string.pdf_local_error_file');
    if(error.message===PdfErrorCode.INVALID)value=$r('app.string.pdf_error_range');
    else if(error.message===PdfErrorCode.PASSWORD)value=$r('app.string.pdf_local_password_error');
    else if(error.message===PdfErrorCode.LIMIT)value=$r('app.string.pdf_local_limit');
    else if(error.message===PdfErrorCode.WRITE)value=$r('app.string.pdf_local_write_error');
    else if(error.message===PdfErrorCode.CANCELLED)value=$r('app.string.pdf_status_cancelled');
    else if(error.message===PdfErrorCode.NO_TEXT)value=$r('app.string.pdf_error_ocr');
    else if(error.message===PdfErrorCode.MARKDOWN)value=$r('app.string.pdf_local_markdown_limit');
    this.message=this.label(value);
    if(this.screen==='edit'&&!this.sheet)this.scroller.scrollEdge(Edge.Top);
  }
  private choose(operation:string):void {
    if(this.busy)return;
    this.operation=operation;this.resultOrigin='edit';this.screen='edit';this.inputs=[];this.job=undefined;this.cloudJob=undefined;
    this.message='';this.text='';this.name='result.pdf';this.ranges='';this.groups='';this.perFile='1';
    this.vertical=true;this.ratio=50;this.grouped=false;this.sourcePage=0;this.thumbnail='';this.outputThumbnail='';this.zoom=1;
    this.a5=false;this.marginMm=10;this.pageNumbers=true;this.jpeg=false;this.dpi=150;this.splitByGroups=false;
    this.scroller.scrollEdge(Edge.Top);
  }
  private back():void {
    if(this.busy){this.message=this.label($r('app.string.pdf_local_cancel_first'));return;}
    if(this.screen==='home')this.onBack();
    else if(this.screen==='reader'){this.controller.releaseDocument();this.screen='result';}
    else if(this.screen==='result'){this.screen=this.resultOrigin;this.cloudJob=undefined;this.message='';}
    else{this.screen='home';this.message='';}
  }
  private async pick():Promise<void> {
    if(this.busy)return;this.busy=true;this.message='';
    try{
      const options=new picker.DocumentSelectOptions();
      options.maxSelectNumber=this.operation==='PDF_MERGE'||this.operation==='IMAGES_TO_PDF'?Math.max(1,50-this.inputs.length):1;
      options.fileSuffixFilters=[this.operation==='IMAGES_TO_PDF'?'Images|png,jpg,jpeg':this.isText()?'Text|txt,md':'PDF|pdf'];
      const uris=await new picker.DocumentViewPicker(this.getUIContext().getHostContext() as common.UIAbilityContext).select(options);
      for(const uri of uris){if(!this.active)return;await this.importUri(uri);if(this.pendingUri)break;}
    }catch(error){if(this.active)this.fail(error as Error);}
    finally{this.busy=false;if(!this.active)this.engine?.dispose(this.imported);}
  }
  private async importUri(uri:string,password:string=''):Promise<void> {
    try{
      const input=await this.local().importFile(uri,password);this.imported.push(input);
      if(!this.active)return;
      if(this.isText()){this.text=this.local().readText(input);this.inputs=[input];}
      else if(this.operation==='PDF_MERGE'||this.operation==='IMAGES_TO_PDF')this.inputs=this.inputs.concat([input]);
      else this.inputs=[input];
      this.sourcePage=0;this.name=PdfLocalPolicy.outputName(input.name,'pdf').replace(/\.pdf$/,'_result.pdf');
      this.pendingUri='';this.password='';this.message='';this.sheet=false;await this.showSource();
    }catch(error){
      if((error as Error).message===PdfErrorCode.PASSWORD){this.pendingUri=uri;this.sheetKind='password';this.sheet=true;if(password)this.message=this.label($r('app.string.pdf_local_password_error'));this.password='';}
      else throw error as Error;
    }
  }
  private async unlock():Promise<void>{
    if(!this.pendingUri||this.busy)return;this.busy=true;
    try{await this.importUri(this.pendingUri,this.password);}catch(error){this.fail(error as Error);}finally{this.busy=false;}
  }
  private async showSource():Promise<void>{
    const seq=++this.thumbnailRevision;
    if(!this.inputs.length){this.thumbnail='';return;}
    const input=this.inputs[0];
    if(!input.pages){if(!this.isText())this.thumbnail=`file://${input.path}`;return;}
    try{const aspect=this.local().aspect(input,this.sourcePage);const path=await this.local().thumbnail(input,this.sourcePage);if(this.active&&seq===this.thumbnailRevision){this.aspect=aspect;this.thumbnail=`file://${path}`;}}
    catch(error){if(this.active&&seq===this.thumbnailRevision)this.fail(error as Error);}
  }
  private selected():number[]{return PdfLocalPolicy.pages(this.ranges,this.inputs[0]?.pages??0);}
  private pieces():PdfPiece[]{try{return PdfLocalPolicy.pieces(this.inputs[0]?.pages??0,this.selected(),this.grouped);}catch(_){return [];}}
  private request():LocalPdfRequest{return {operation:this.operation,inputs:this.inputs.slice(),text:this.text,name:this.name,ranges:this.operation==='PDF_SPLIT'&&this.splitByGroups?'':this.ranges,groups:this.splitByGroups?this.groups:'',perFile:Number(this.perFile),vertical:this.vertical,ratio:this.ratio/100,grouped:this.grouped,dpi:this.dpi,jpeg:this.jpeg,a5:this.a5,margin:this.marginMm,pageNumbers:this.pageNumbers};}
  private async generate():Promise<void>{
    if(this.busy)return;this.busy=true;this.processing=true;this.resultOrigin='edit';this.cancelRequested=false;this.message='';this.progress=0;
    try{
      // 自定义分组必须明确填写，避免悄悄回退为按页数拆分。
      if(this.operation==='PDF_SPLIT'&&this.splitByGroups&&!this.groups.trim()){this.message=this.label($r('app.string.pdf_ux_groups_required'));this.scroller.scrollEdge(Edge.Top);return;}
      const request=this.request();
      const job=await this.local().run(request,()=>this.active&&!this.cancelRequested,(p:number)=>this.progress=Math.round(p));
      if(!this.active)return;this.job=job;this.cloudJob=undefined;this.screen='result';this.scroller.scrollEdge(Edge.Top);this.outputThumbnail='';
      const a=job.artifacts[0];
      if(a?.mime==='application/pdf'){
        const input:LocalPdfInput={id:job.id,path:a.path,name:a.name,bytes:a.bytes,pages:a.pages,width:0,height:0,password:''};
        try{this.outputThumbnail=`file://${await this.local().thumbnail(input,0)}`;}catch(_){this.message=this.label($r('app.string.pdf_local_preview_error'));}
      }else if(a?.mime.startsWith('image/'))this.outputThumbnail=`file://${a.path}`;
    }catch(error){if(this.active)this.fail(error as Error);}
    finally{this.busy=false;this.processing=false;if(!this.active)this.engine?.dispose(this.imported);}
  }
  private async read(a:LocalPdfArtifact):Promise<void>{
    if(this.busy)return;this.busy=true;
    try{const result=await this.controller.loadDocument(a.path);if(result!==pdfService.ParseResult.PARSE_SUCCESS)throw new Error(PdfErrorCode.FILE);if(this.active)this.screen='reader';}
    catch(error){this.fail(error as Error);}finally{this.busy=false;}
  }
  private async save(a:LocalPdfArtifact):Promise<void>{
    if(this.busy)return;this.busy=true;this.message='';
    try{const options=new picker.DocumentSaveOptions();options.newFileNames=[a.name];
      const uris=await new picker.DocumentViewPicker(this.getUIContext().getHostContext() as common.UIAbilityContext).save(options);
      if(uris.length&&this.active){await this.local().copy(a.path,uris[0]);this.message=this.label($r('app.string.pdf_saved'));}
    }catch(error){this.fail(error as Error);}finally{this.busy=false;}
  }
  private async cloudHistory():Promise<void>{
    if(this.busy)return;this.busy=true;this.message='';
    try{this.cloud=await new PdfToolsApi(this.baseUrl,this.authManager).history(0);this.screen='cloud';}
    catch(_){this.message=this.label($r('app.string.pdf_local_cloud_error'));}finally{this.busy=false;}
  }
  private async cloudArtifact(index:number,artifact:PdfArtifact,save:boolean):Promise<void>{
    if(!this.cloudJob||this.busy)return;this.busy=true;
    const context=this.getUIContext().getHostContext() as common.UIAbilityContext;
    const path=`${context.cacheDir}/pdf-cloud-preview.pdf`;
    try{
      if(save){const options=new picker.DocumentSaveOptions();options.newFileNames=[artifact.filename];const uris=await new picker.DocumentViewPicker(context).save(options);if(uris.length){await new PdfToolsApi(this.baseUrl,this.authManager).download(this.cloudJob.id,index,uris[0]);this.message=this.label($r('app.string.pdf_saved'));}}
      else{await new PdfToolsApi(this.baseUrl,this.authManager).download(this.cloudJob.id,index,path);const result=await this.controller.loadDocument(path);if(result!==pdfService.ParseResult.PARSE_SUCCESS)throw new Error(PdfErrorCode.FILE);this.screen='reader';}
    }catch(_){this.message=this.label($r('app.string.pdf_local_cloud_error'));}finally{this.busy=false;}
  }
  private move(index:number,delta:number):void{const other=index+delta;if(this.busy||other<0||other>=this.inputs.length)return;const items=this.inputs.slice();const input=items[index];items[index]=items[other];items[other]=input;this.inputs=items;this.showSource();}
  private sheetOpen(kind:string):void{if(this.busy)return;this.ratioDraft=this.ratio;this.groupedDraft=this.grouped;this.sheetKind=kind;if(kind==='scope')this.scopeDraft=this.ranges;this.sheet=true;}
  private page(delta:number):void{if(this.busy)return;const value=this.sourcePage+delta;if(value<0||value>=(this.inputs[0]?.pages??0))return;this.sourcePage=value;this.showSource();}
  private multiInput():boolean{return this.operation==='PDF_MERGE'||this.operation==='IMAGES_TO_PDF';}
  private ready():boolean{return this.isText()?this.text.trim().length>0:this.operation==='PDF_MERGE'?this.inputs.length>1:this.inputs.length>0;}
  private actionLabel():Resource {
    if(!this.isText()&&!this.inputs.length)return this.operation==='IMAGES_TO_PDF'?$r('app.string.pdf_ux_pick_images'):$r('app.string.pdf_ux_pick_pdf');
    if(this.operation==='PDF_MERGE'&&this.inputs.length<2)return $r('app.string.pdf_ux_add_pdf');
    if(this.operation==='PDF_PAGE_CUT')return $r('app.string.pdf_ux_cut_action');
    if(this.operation==='PDF_SPLIT')return $r('app.string.pdf_ux_split_action');
    if(this.operation==='PDF_MERGE')return $r('app.string.pdf_ux_merge_action');
    if(this.operation==='PDF_TO_IMAGES')return $r('app.string.pdf_ux_images_action');
    if(this.operation==='PDF_TO_TEXT')return $r('app.string.pdf_ux_text_action');
    return $r('app.string.pdf_ux_create_action');
  }
  private hint():Resource {
    if(this.operation==='PDF_PAGE_CUT')return $r('app.string.pdf_ux_cut_hint');
    if(this.operation==='PDF_SPLIT')return $r('app.string.pdf_ux_split_hint');
    if(this.operation==='PDF_MERGE')return $r('app.string.pdf_ux_merge_hint');
    if(this.operation==='IMAGES_TO_PDF')return $r('app.string.pdf_ux_images_hint');
    if(this.operation==='PDF_TO_TEXT')return $r('app.string.pdf_ux_extract_hint');
    return $r('app.string.pdf_ux_input_hint');
  }
  private openContinue(artifact:LocalPdfArtifact):void{if(this.busy)return;this.continuedArtifact=artifact;this.sheetOpen('continue');}
  private async continueWith(operation:string):Promise<void>{
    const artifact=this.continuedArtifact;
    if(!artifact||this.busy)return;
    this.sheet=false;this.choose(operation);this.busy=true;
    try{await this.importUri(`file://${artifact.path}`);}catch(error){this.fail(error as Error);}finally{this.busy=false;}
  }
  @Builder private SectionTitle(value:Resource){Text(value).fontSize(16).fontFamily(AppTheme.fontMedium).width('100%').fontColor(AppTheme.textPrimary)}
  @Builder private HomeGroup(title:Resource,group:number){
    Column({space:10}){
      Text(title).fontSize(18).fontFamily(AppTheme.fontSemibold).width('100%')
      Flex({wrap:FlexWrap.Wrap,alignItems:ItemAlign.Stretch}){
        ForEach(PDF_TOOLS.filter((t:PdfTool)=>t.group===group),(tool:PdfTool)=>{
          Button({type:ButtonType.Normal}){Column({space:8}){if(tool.group===0){Image(tool.icon).width(24).height(24)}else{SymbolGlyph(tool.icon).fontSize(24).fontColor([AppTheme.primary])}
            Text(tool.operation==='MARKDOWN_TO_PDF'?this.label(tool.title).replace(' ', '\n'):tool.title).fontFamily(AppTheme.fontFamily).fontSize(14).fontColor(AppTheme.textPrimary).width('100%').textAlign(TextAlign.Center).maxLines(2).wordBreak(WordBreak.NORMAL).textOverflow({overflow:TextOverflow.Ellipsis})
            Text(tool.detail).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).width('100%').textAlign(TextAlign.Center).maxLines(1).textOverflow({overflow:TextOverflow.Ellipsis})}.width('100%')}
            .constraintSize({minHeight:112}).padding({top:14,bottom:14,left:8,right:8}).width('calc(33.33% - 8vp)').margin(4).backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius).onClick(()=>this.choose(tool.operation)).accessibilityText(this.label(tool.title))
        },(tool:PdfTool)=>tool.operation)
      }
    }.width('100%')
  }
  @Builder private Files(){
    if(!this.isText()&&!this.inputs.length){
      Column({space:16}){
        SymbolGlyph(this.operation==='IMAGES_TO_PDF'?$r('sys.symbol.picture'):$r('sys.symbol.doc_text')).fontSize(36).fontColor([AppTheme.primary])
        Text(this.operation==='IMAGES_TO_PDF'?$r('app.string.pdf_ux_pick_images'):$r('app.string.pdf_ux_pick_pdf')).fontFamily(AppTheme.fontSemibold).fontSize(20)
        Text(this.hint()).fontFamily(AppTheme.fontFamily).fontSize(14).lineHeight(22).fontColor(AppTheme.textSecondary).textAlign(TextAlign.Center)
        Text($r('app.string.pdf_ux_steps')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary)
      }.width('100%').padding({top:40,bottom:40,left:24,right:24}).backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
    }else{
      Row(){Text(this.isText()?$r('app.string.pdf_ux_content'):$r('app.string.pdf_ux_source')).fontFamily(AppTheme.fontMedium).fontSize(15).layoutWeight(1)
        AppTextAction({label:this.isText()?$r('app.string.pdf_ux_import_text'):this.multiInput()?$r('app.string.pdf_local_add_file'):$r('app.string.pdf_ux_replace'),action:()=>this.pick(),available:!this.busy})
      }.width('100%')
    }
    ForEach(this.inputs,(input:LocalPdfInput,index:number)=>{
      Column({space:8}){
        Text(input.name).fontSize(16).fontFamily(AppTheme.fontMedium).maxLines(2).width('100%')
        Text(`${this.bytes(input.bytes)}${input.pages?` · ${input.pages} ${this.label($r('app.string.pdf_local_pages'))}`:''}`).fontFamily(AppTheme.fontFamily)
          .fontSize(12).fontColor(AppTheme.textSecondary)
        Flex({wrap:FlexWrap.Wrap,alignItems:ItemAlign.Center}){
          if(this.inputs.length>1){
            AppTextAction({label:$r('app.string.pdf_local_up'),action:()=>this.move(index,-1),available:!this.busy&&index>0}).margin({right:8})
            AppTextAction({label:$r('app.string.pdf_local_down'),action:()=>this.move(index,1),available:!this.busy&&index<this.inputs.length-1}).margin({right:8})
          }
          AppTextAction({label:$r('app.string.pdf_remove'),available:!this.busy,action:()=>{this.inputs=this.inputs.filter((v:LocalPdfInput)=>v.id!==input.id);this.sourcePage=0;this.showSource();}})
        }.width('100%')
      }.width('100%').padding(16).backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius).alignItems(HorizontalAlign.Start)
    },(input:LocalPdfInput)=>input.id)
  }
  @Builder private Canvas(){
    if(this.thumbnail){
      Stack({alignContent:Alignment.TopStart}){
        Image(this.thumbnail).width('100%').height('100%').objectFit(ImageFit.Fill).scale({x:this.zoom,y:this.zoom})
        if(this.vertical){Row().height('100%').width(2).backgroundColor(AppTheme.primary).position({x:`${50+(this.ratio-50)*this.zoom}%`,y:0})}
        else{Row().width('100%').height(2).backgroundColor(AppTheme.primary).position({x:0,y:`${50+(this.ratio-50)*this.zoom}%`})}
      }.width(`${Math.min(1,200*this.aspect/320)*100}%`).aspectRatio(this.aspect).constraintSize({maxHeight:200}).alignSelf(ItemAlign.Center).clip(true)
        .onAreaChange((_o:Area,a:Area)=>{this.canvasWidth=Number(a.width);this.canvasHeight=Number(a.height);})
        .onTouch((event:TouchEvent)=>{
          if(event.type===TouchType.Up||event.type===TouchType.Cancel){this.draggingGuide=false;return;}
          if(this.busy||!event.touches.length)return;
          const touch=event.touches[0];
          const length=this.vertical?this.canvasWidth:this.canvasHeight;
          const position=this.vertical?touch.x:touch.y;
          // 仅从切割线附近开始的触摸可修改比例，普通滚动不会改变设置。
          if(event.type===TouchType.Down){this.draggingGuide=Math.abs(position-length*(0.5+(this.ratio/100-0.5)*this.zoom))<=18;}
          else if(event.type===TouchType.Move&&this.draggingGuide){this.ratio=Math.round(Math.max(5,Math.min(95,((position/length-0.5)/this.zoom+0.5)*100)));}
        })
      AppEqualActionRow(){
        AppGroupedButton({label:$r('app.string.pdf_previous'),variant:'text',fullWidth:true,action:()=>this.page(-1),available:!this.busy&&this.sourcePage>0})
        Column(){Text(`${this.sourcePage+1} / ${this.inputs[0].pages}`).fontFamily(AppTheme.fontFamily).fontSize(14)
          .constraintSize({maxWidth:92}).maxLines(1).textOverflow({overflow:TextOverflow.Ellipsis}).textAlign(TextAlign.Center)
        }.justifyContent(FlexAlign.Center).alignItems(HorizontalAlign.Center)
        AppGroupedButton({label:$r('app.string.pdf_next'),variant:'text',fullWidth:true,action:()=>this.page(1),available:!this.busy&&this.sourcePage+1<this.inputs[0].pages})
      }
      Row({space:8}){
        AppButton({label:'−',fullWidth:true,minWidth:44,available:!this.busy&&this.zoom>1,action:()=>this.zoom=Math.max(1,this.zoom-0.25),variant:'secondary'}).width(44)
        Text(`${Math.round(this.zoom*100)}%`).fontFamily(AppTheme.fontFamily).fontSize(14).width(64).textAlign(TextAlign.Center)
        AppButton({label:'+',fullWidth:true,minWidth:44,available:!this.busy&&this.zoom<2,action:()=>this.zoom=Math.min(2,this.zoom+0.25),variant:'secondary'}).width(44)
      }.width('100%').justifyContent(FlexAlign.Center)
    }
  }
  @Builder private CutPanel(){
    Column({space:8}){
      Text($r('app.string.pdf_ux_cut_settings')).fontFamily(AppTheme.fontSemibold).fontSize(15).width('100%')
      Text($r('app.string.pdf_ux_drag_hint')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).width('100%')
      Row({space:0}){AppSegmentItem({label:$r('app.string.pdf_vertical'),selected:this.vertical,available:!this.busy,action:()=>this.vertical=true}).layoutWeight(1);AppSegmentItem({label:$r('app.string.pdf_horizontal'),selected:!this.vertical,available:!this.busy,action:()=>this.vertical=false}).layoutWeight(1)}.width('100%').backgroundColor(AppTheme.surfaceTint).borderRadius(12)
      Row({space:10}){Text($r('app.string.pdf_position')).fontFamily(AppTheme.fontFamily).fontSize(14);Slider({value:this.ratio,min:5,max:95,step:1}).layoutWeight(1).selectedColor(AppTheme.primary).enabled(!this.busy).onChange((v:number)=>this.ratio=v);AppButton({label:`${this.ratio}%`,available:!this.busy,action:()=>this.sheetOpen('ratio'),variant:'secondary'})}
      Button({type:ButtonType.Normal}){Row({space:8}){Text($r('app.string.pdf_local_scope')).fontFamily(AppTheme.fontFamily).fontSize(16).layoutWeight(1).maxLines(1).textOverflow({overflow:TextOverflow.Ellipsis});Text(this.ranges||this.label($r('app.string.pdf_local_all'))).fontFamily(AppTheme.fontFamily).fontSize(16).fontColor(AppTheme.textSecondary).layoutWeight(1).maxLines(1).textOverflow({overflow:TextOverflow.Ellipsis}).textAlign(TextAlign.End);SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(14).flexShrink(0)}.width('100%')}.width('100%').constraintSize({minHeight:64}).padding({top:8,bottom:8}).backgroundColor(Color.Transparent).fontColor(AppTheme.textPrimary).onClick(()=>this.sheetOpen('scope'))
      Button({type:ButtonType.Normal}){Row(){Text($r('app.string.pdf_local_order')).fontFamily(AppTheme.fontFamily).fontSize(16).layoutWeight(1);Text(this.grouped?$r('app.string.pdf_local_grouped'):$r('app.string.pdf_local_per_page')).fontFamily(AppTheme.fontFamily).fontSize(16).fontColor(AppTheme.textSecondary);SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(14)}}.width('100%').constraintSize({minHeight:64}).padding({top:8,bottom:8}).backgroundColor(Color.Transparent).fontColor(AppTheme.textPrimary).onClick(()=>this.sheetOpen('order'))
      Divider().color(AppTheme.divider)
      Text(`${this.inputs[0]?.pages??0} ${this.label($r('app.string.pdf_local_pages'))} → ${this.pieces().length} ${this.label($r('app.string.pdf_local_pages'))}`).fontFamily(AppTheme.fontMedium).fontSize(16).width('100%')
      Scroll(){Row({space:8}){ForEach(this.pieces().slice(0,30),(piece:PdfPiece,index:number)=>{
        Column({space:5}){SymbolGlyph($r('sys.symbol.doc_text')).fontSize(22).fontColor([AppTheme.primary]);Text(`${index+1}`).fontFamily(AppTheme.fontFamily).fontSize(12);Text(`${piece.page+1} · ${this.label(piece.side<0?$r('app.string.pdf_local_original'):this.vertical?(piece.side===0?$r('app.string.pdf_local_left'):$r('app.string.pdf_local_right')):(piece.side===0?$r('app.string.pdf_local_top'):$r('app.string.pdf_local_bottom')))}`).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary)}.width(65).padding(8).backgroundColor(AppTheme.surfaceTint).borderRadius(8)
      },(piece:PdfPiece)=>`${piece.page}-${piece.side}`)}}.scrollable(ScrollDirection.Horizontal).width('100%')
      Text($r('app.string.pdf_local_preview_hint')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).width('100%')
      Text($r('app.string.pdf_caution')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).width('100%')
    }.width('100%').padding(16).backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
  }
  @Builder private Editor(){
    this.Files()
    if(this.operation==='PDF_PAGE_CUT'&&this.inputs.length){this.Canvas();this.CutPanel()}
    if(this.inputs.length||this.isText()){
    if(this.isText()){
      AppGlassTextArea({value:this.text,placeholder:this.label($r('app.string.pdf_text_placeholder')),areaHeight:180,limit:60000,available:!this.busy,valueChanged:(v:string)=>this.text=v})
      if(this.operation==='MARKDOWN_TO_PDF')Text($r('app.string.pdf_local_markdown_hint')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary)
    }
    if(['PDF_SPLIT','PDF_TO_IMAGES','PDF_TO_TEXT'].includes(this.operation)&&!(this.operation==='PDF_SPLIT'&&this.splitByGroups)){
      this.SectionTitle($r('app.string.pdf_local_scope'))
      AppGlassTextInput({value:this.ranges,limit:60000,placeholder:this.label($r('app.string.pdf_ranges')),available:!this.busy,valueChanged:(v:string)=>this.ranges=v})
      Text($r('app.string.pdf_ux_range_hint')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary)
    }
    if(this.operation==='PDF_SPLIT'){
      Row({space:0}){AppSegmentItem({label:$r('app.string.pdf_ux_by_count'),selected:!this.splitByGroups,available:!this.busy,action:()=>this.splitByGroups=false}).layoutWeight(1);AppSegmentItem({label:$r('app.string.pdf_ux_by_groups'),selected:this.splitByGroups,available:!this.busy,action:()=>this.splitByGroups=true}).layoutWeight(1)}.width('100%').backgroundColor(AppTheme.surfaceTint).borderRadius(12)
      if(this.splitByGroups){AppGlassTextInput({value:this.groups,limit:60000,placeholder:this.label($r('app.string.pdf_groups')),available:!this.busy,valueChanged:(v:string)=>this.groups=v})}
      else{Text($r('app.string.pdf_count')).fontFamily(AppTheme.fontFamily).fontSize(16).width('100%');AppGlassTextInput({value:this.perFile,inputType:InputType.Number,available:!this.busy,valueChanged:(v:string)=>this.perFile=v})}
      Text(this.splitByGroups?$r('app.string.pdf_ux_groups_hint'):$r('app.string.pdf_ux_count_hint')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary)
    }
    if(this.isText()||this.operation==='IMAGES_TO_PDF'){
      Row({space:10}){Text($r('app.string.pdf_page_size')).fontFamily(AppTheme.fontFamily).fontSize(16).layoutWeight(1);AppSegmentItem({label:'A4',selected:!this.a5,available:!this.busy,action:()=>this.a5=false});AppSegmentItem({label:'A5',selected:this.a5,available:!this.busy,action:()=>this.a5=true})}
      Text(`${this.label($r('app.string.pdf_margin'))} ${this.marginMm} mm`).fontFamily(AppTheme.fontFamily).fontSize(14)
      Slider({value:this.marginMm,min:5,max:40,step:1}).selectedColor(AppTheme.primary).enabled(!this.busy).onChange((v:number)=>this.marginMm=v)
      if(this.isText())Row(){Text($r('app.string.pdf_page_numbers')).fontFamily(AppTheme.fontFamily).fontSize(16).layoutWeight(1);Toggle({type:ToggleType.Switch,isOn:this.pageNumbers}).enabled(!this.busy).onChange((v:boolean)=>this.pageNumbers=v)}
      else Text($r('app.string.pdf_local_image_fit')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary)
    }
    if(this.operation==='PDF_TO_IMAGES'){
      Row({space:10}){AppSegmentItem({label:'PNG',selected:!this.jpeg,available:!this.busy,action:()=>this.jpeg=false});AppSegmentItem({label:'JPG',selected:this.jpeg,available:!this.busy,action:()=>this.jpeg=true});Text(`${this.dpi} DPI`).fontFamily(AppTheme.fontFamily).fontSize(16)}
      Slider({value:this.dpi,min:72,max:300,step:1}).selectedColor(AppTheme.primary).enabled(!this.busy).onChange((v:number)=>this.dpi=v)
    }
    Text($r('app.string.pdf_output')).fontFamily(AppTheme.fontFamily).fontSize(14).width('100%')
    AppGlassTextInput({value:this.name,available:!this.busy,valueChanged:(v:string)=>this.name=v})
    }
  }
  /** 校验并提交当前参数面板草稿，失败时保持输入。 */
  private applySheet():void {
    try{
      if(this.sheetKind==='scope'){
        PdfLocalPolicy.pages(this.scopeDraft,this.inputs[0]?.pages??0);
        this.ranges=this.scopeDraft;
      }else if(this.sheetKind==='ratio'){
        this.ratio=this.ratioDraft;
      }else if(this.sheetKind==='order'){
        this.grouped=this.groupedDraft;
      }
      this.message='';this.sheet=false;
    }catch(error){this.fail(error as Error);}
  }
  /** 处理中只请求取消，闲置时恢复编辑参数。 */
  private resetEditor():void {
    if(this.processing){this.cancelRequested=true;return;}
    this.ratio=50;this.vertical=true;this.grouped=false;this.ranges='';this.groups='';this.perFile='1';this.zoom=1;
    this.dpi=150;this.jpeg=false;this.a5=false;this.marginMm=10;this.pageNumbers=true;this.splitByGroups=false;this.message='';
  }
  @Builder private Sheet(){
    // 半屏面板只滚动自身内容，长文案和放大字体下仍可到达最后一项操作。
    Scroll(){
    Column({space:18}){
      if(this.sheetKind==='continue'){
        Text($r('app.string.pdf_ux_continue')).fontFamily(AppTheme.fontSemibold).fontSize(20)
        Text(this.continuedArtifact?.name??'').fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).maxLines(1)
        ForEach(PDF_TOOLS.filter((t:PdfTool)=>t.group!==1),(tool:PdfTool)=>{AppButton({label:tool.title,fullWidth:true,action:()=>this.continueWith(tool.operation),variant:'secondary'})})
      }else if(this.sheetKind==='scope'){
        Text($r('app.string.pdf_local_scope')).fontFamily(AppTheme.fontSemibold).fontSize(20)
        Text($r('app.string.pdf_local_scope_hint')).fontFamily(AppTheme.fontFamily).fontSize(13)
        AppGlassTextInput({value:this.scopeDraft,limit:60000,placeholder:this.label($r('app.string.pdf_ranges')),valueChanged:(v:string)=>this.scopeDraft=v})
        AppButton({label:$r('app.string.pdf_local_all'),action:()=>this.scopeDraft='',variant:'secondary'})
      }else if(this.sheetKind==='ratio'){
        Text($r('app.string.pdf_position')).fontFamily(AppTheme.fontFamily).fontSize(20)
        Slider({value:this.ratioDraft,min:5,max:95,step:1}).selectedColor(AppTheme.primary).onChange((v:number)=>this.ratioDraft=v)
        Text(`${this.ratioDraft}%`).fontFamily(AppTheme.fontFamily).fontSize(22)
      }else if(this.sheetKind==='order'){
        Text($r('app.string.pdf_local_order')).fontFamily(AppTheme.fontFamily).fontSize(20)
        AppSegmentItem({label:$r('app.string.pdf_local_per_page'),selected:!this.groupedDraft,action:()=>this.groupedDraft=false})
        AppSegmentItem({label:$r('app.string.pdf_local_grouped'),selected:this.groupedDraft,action:()=>this.groupedDraft=true})
        Text($r('app.string.pdf_local_group_hint')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary)
      }else if(this.sheetKind==='password'){
        Text($r('app.string.pdf_local_password')).fontFamily(AppTheme.fontFamily).fontSize(20)
        AppGlassTextInput({value:this.password,placeholder:this.label($r('app.string.pdf_local_password_hint')),inputType:InputType.Password,valueChanged:(v:string)=>this.password=v})
        AppButton({label:$r('app.string.pdf_local_unlock'),available:!this.busy&&this.password.length>0,variant:'primary',action:()=>this.unlock()})
      }
      if(this.sheetKind!=='password'&&this.sheetKind!=='continue'){
        AppActionGroup({primaryLabel:$r('app.string.pdf_local_apply'),secondaryLabel:$r('app.string.pdf_cancel'),
          primaryAction:()=>this.applySheet(),secondaryAction:()=>this.sheet=false})
      }
      if(this.message)Text(this.message).fontFamily(AppTheme.fontFamily).fontSize(13).fontColor(AppTheme.danger)
    }.padding(24).width('100%')
    }.width('100%').height('100%').scrollBar(BarState.Auto).align(Alignment.TopStart)
  }
  /** 构建本地PDF工作台与统一操作区。 */
  build(){
    Column(){
      Row({space:8}){
        AppIconButton({icon:$r('sys.symbol.chevron_left'),description:this.label($r('app.string.pdf_local_back')),action:()=>this.back()})
        Column({space:3}){Text(this.screen==='home'?$r('app.string.pdf_tools'):this.screen==='history'?$r('app.string.pdf_local_history'):this.screen==='cloud'?$r('app.string.pdf_local_cloud'):this.title()).fontFamily(AppTheme.fontSemibold).fontSize(20).maxLines(1);Text($r('app.string.pdf_local_subtitle')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary)}.layoutWeight(1).alignItems(HorizontalAlign.Start)
        if(this.screen==='home')AppTextAction({label:$r('app.string.pdf_local_history'),action:()=>{this.history=this.local().history();this.screen='history';}})
      }.width('100%').padding({left:8,right:16,top:6,bottom:6})
      if(this.screen==='reader'){
        PdfView({controller:this.controller,pageLayout:pdfService.PageLayout.LAYOUT_SINGLE,isContinuous:true,showScroll:true,pageFit:pdfService.PageFit.FIT_WIDTH}).layoutWeight(1).width('100%')
      }else{
        Scroll(this.scroller){Column({space:14}){
          if(this.message)Text(this.message).fontFamily(AppTheme.fontFamily).fontSize(13).fontColor(this.message===this.label($r('app.string.pdf_saved'))?AppTheme.primary:AppTheme.danger).padding(12).backgroundColor(AppTheme.surface).borderRadius(12).width('100%')
          if(this.processing){Progress({value:this.progress,total:100,type:ProgressType.Linear}).width('100%');Text($r('app.string.pdf_local_processing')).fontFamily(AppTheme.fontFamily).fontSize(13)}
          if(this.screen==='home'){this.HomeGroup($r('app.string.pdf_organize'),0);this.HomeGroup($r('app.string.pdf_generate'),1);this.HomeGroup($r('app.string.pdf_export'),2)}
          else if(this.screen==='edit')this.Editor()
          else if(this.screen==='result'){
            Text($r('app.string.pdf_done')).fontFamily(AppTheme.fontFamily).fontSize(24).fontColor(AppTheme.primary)
            Text($r('app.string.pdf_local_result_hint')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary)
            if(this.outputThumbnail)Image(this.outputThumbnail).width('100%').height(220).objectFit(ImageFit.Contain)
            if(this.job?.text)Text(this.job.text).fontFamily(AppTheme.fontFamily).fontSize(14).copyOption(CopyOptions.LocalDevice)
            ForEach(this.job?.artifacts??[],(a:LocalPdfArtifact)=>{
              Column({space:12}){
                Text(a.name).fontFamily(AppTheme.fontMedium).fontSize(16).width('100%').maxLines(2)
                Text(`${this.bytes(a.bytes)}${a.pages?` · ${a.pages} ${this.label($r('app.string.pdf_local_pages'))}`:''}`)
                  .fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).width('100%')
                if(a.mime==='application/pdf'){
                  AppActionGroup({secondaryLabel:$r('app.string.pdf_read'),primaryLabel:$r('app.string.pdf_save'),
                    available:!this.busy,secondaryAvailable:!this.busy,secondaryAction:()=>this.read(a),primaryAction:()=>this.save(a)})
                }else{
                  AppButton({label:$r('app.string.pdf_save'),fullWidth:true,available:!this.busy,action:()=>this.save(a)})
                }
                if(a.mime==='application/pdf'&&(this.job?.artifacts.length??0)>1){
                  AppButton({label:$r('app.string.pdf_ux_continue'),fullWidth:true,available:!this.busy,action:()=>this.openContinue(a),variant:'secondary'})
                }
              }.padding(16).width('100%').backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
            },(a:LocalPdfArtifact)=>a.path)
            ForEach(this.cloudJob?.result.artifacts??[],(a:PdfArtifact,index:number)=>{
              Column({space:12}){
                Text(a.filename).fontFamily(AppTheme.fontMedium).fontSize(16).width('100%').maxLines(2)
                if(a.mediaType==='application/pdf'){
                  AppActionGroup({secondaryLabel:$r('app.string.pdf_read'),primaryLabel:$r('app.string.pdf_save'),
                    available:!this.busy,secondaryAvailable:!this.busy,secondaryAction:()=>this.cloudArtifact(index,a,false),primaryAction:()=>this.cloudArtifact(index,a,true)})
                }else{
                  AppButton({label:$r('app.string.pdf_save'),fullWidth:true,available:!this.busy,action:()=>this.cloudArtifact(index,a,true)})
                }
              }.padding(16).width('100%').backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
            })
          }else if(this.screen==='history'){
            if(!this.history.length)ToolEmptyState({title:$r('app.string.pdf_no_history'),description:$r('app.string.pdf_local_history')})
            ForEach(this.history,(row:LocalPdfJob)=>{Button({type:ButtonType.Normal}){Column({space:6}){Text(row.artifacts[0]?.name??'').fontFamily(AppTheme.fontFamily).fontSize(15);Text(row.createdAt.substring(0,10)).fontFamily(AppTheme.fontFamily).fontSize(12)}}.width('100%').constraintSize({minHeight:72}).padding(16).backgroundColor(AppTheme.surface).fontColor(AppTheme.textPrimary).borderRadius(12).onClick(()=>{this.job=row;this.operation=row.operation;this.resultOrigin='history';this.cloudJob=undefined;this.outputThumbnail='';this.screen='result';})},(row:LocalPdfJob)=>row.id)
            AppButton({label:$r('app.string.pdf_local_cloud'),available:!this.busy,action:()=>this.cloudHistory(),variant:'secondary'})
          }else if(this.screen==='cloud'){
            if(!this.cloud.length)ToolEmptyState({title:$r('app.string.pdf_no_history'),description:$r('app.string.pdf_local_cloud')})
            ForEach(this.cloud,(row:PdfRecord)=>{Button(row.name??row.operation??row.id,{type:ButtonType.Normal}).fontSize(16).fontFamily(AppTheme.fontMedium).width('100%').constraintSize({minHeight:64}).padding(14).backgroundColor(AppTheme.surface).fontColor(AppTheme.textPrimary).borderRadius(AppTheme.cardRadius).onClick(()=>{this.resultOrigin='cloud';this.job=undefined;this.cloudJob=row;this.screen='result';this.scroller.scrollEdge(Edge.Top);this.outputThumbnail='';})},(row:PdfRecord)=>row.id)
          }
        }.width('100%').padding(16).alignItems(HorizontalAlign.Start)}.layoutWeight(1).width('100%').align(Alignment.TopStart)
        if(this.screen==='result'){
          AppActionGroup({secondaryLabel:this.resultOrigin==='edit'?$r('app.string.pdf_ux_adjust'):$r('app.string.pdf_ux_back_history'),
            primaryLabel:this.job?.artifacts.length===1&&this.job.artifacts[0].mime==='application/pdf'?$r('app.string.pdf_ux_continue'):$r('app.string.pdf_ux_home'),
            available:!this.busy,secondaryAvailable:!this.busy,secondaryAction:()=>this.back(),primaryAction:()=>{
              if(this.job?.artifacts.length===1&&this.job.artifacts[0].mime==='application/pdf')this.openContinue(this.job.artifacts[0]);
              else this.screen='home';
            }}).padding(12).backgroundColor(AppTheme.surface)
        }
        if(this.screen==='edit'){
          Column({space:6}){
          if(!this.processing)Text(this.isText()?$r('app.string.pdf_ux_text_ready'):this.operation==='PDF_MERGE'&&this.inputs.length<2?$r('app.string.pdf_ux_merge_hint'):!this.inputs.length?this.hint():$r('app.string.pdf_ux_ready')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).width('100%')
          // 处理中保留取消可用，同时禁用再次执行，避免把两侧可用性绑定在一起。
          AppActionGroup({secondaryLabel:this.processing?$r('app.string.pdf_cancel'):$r('app.string.pdf_local_reset'),
              secondaryAvailable:this.processing||(!this.busy&&(this.inputs.length>0||this.isText())),
              secondaryAction:()=>this.resetEditor(),primaryLabel:this.actionLabel(),
              available:!this.busy&&(!this.isText()||this.ready()),primaryAction:()=>{
                if(!this.ready()&&!this.isText())this.pick();else this.generate();
              }})
          }.width('100%').padding(12).backgroundColor(AppTheme.surface)
        }
      }
    }.width('100%').height('100%').backgroundColor(AppTheme.background)
      .bindSheet($$this.sheet,this.Sheet(),{height:SheetSize.MEDIUM,showClose:true,onDisappear:()=>{this.password='';}})
  }
}
