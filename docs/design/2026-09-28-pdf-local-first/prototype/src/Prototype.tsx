import { useState, useEffect, type CSSProperties } from 'react';
import { ArrowLeftIcon, DotsHorizontalIcon, ChevronRightIcon, ZoomInIcon, ZoomOutIcon, EnterFullScreenIcon, InfoCircledIcon, ColumnsIcon, RowsIcon, ImageIcon, FileTextIcon, ScissorsIcon, CopyIcon, CheckCircledIcon, DownloadIcon } from '@radix-ui/react-icons';
import { MobileScroll, BottomSheet, Carousel, KeyboardInput, useKeyboard, useKeyboardInsets, useScreenPortal } from './mobile';

type Piece = { page: number; side: number };
const asset = '/assets/pdf/spread.png';
export default function Prototype() {
  const [screen, setScreen] = useState('editor');
  const [sheet, setSheet] = useState('');
  const [axis, setAxis] = useState('vertical');
  const [ratio, setRatio] = useState(50);
  const [ratioDraft, setRatioDraft] = useState('50');
  const [page, setPage] = useState(1);
  const [scope, setScope] = useState('all');
  const [order, setOrder] = useState('page');
  const [zoom, setZoom] = useState(1);
  const [name, setName] = useState('第一单元_切分.pdf');
  const [saved, setSaved] = useState(false);
  const [selected, setSelected] = useState<Piece | null>(null);
  const keyboard = useKeyboard();
  const { screenRef } = useScreenPortal();
  const { bottomInset } = useKeyboardInsets();
  // 关闭弹层后，阻止焦点恢复再次唤起键盘。
  useEffect(() => { if (!sheet && keyboard.visible) keyboard.hide(); if (!keyboard.visible && screenRef.current) screenRef.current.scrollTop = 0; }, [sheet, keyboard.visible]);
  const pages = scope === 'all' ? [1, 2] : [Number(scope)];
  const parts: Piece[] = order === 'page' ? [1, 2].flatMap(p => pages.includes(p) ? [{ page:p,side:0 },{ page:p,side:1 }] : [{page:p,side:-1}]) : [...pages.map(p=>({page:p,side:0})), ...pages.map(p=>({page:p,side:1})), ...[1,2].filter(p=>!pages.includes(p)).map(p=>({page:p,side:-1}))];
  const directions = axis === 'vertical' ? ['左','右'] : ['上','下'];
  const navigate = (s:string) => { keyboard.hide(); setScreen(s); setSaved(false); };
  const reset = () => { setRatio(50); setAxis('vertical'); setScope('all'); setOrder('page'); setZoom(1); };
  const crop = (piece:Piece):CSSProperties => {
    if(piece.side < 0) return {backgroundImage:`url(${asset})`};
    const fraction = (piece.side === 0 ? ratio : 100-ratio) / 100;
    return { backgroundImage:`url(${asset})`, backgroundSize: axis === 'vertical' ? `${100/fraction}% 100%` : `100% ${100/fraction}%`, backgroundPosition: piece.side === 0 ? 'left top' : 'right bottom', aspectRatio: axis === 'vertical' ? `${1.44*fraction}` : `${1.44/fraction}` };
  };
  const thumb = (piece:Piece, index:number) => <button className="piece" key={`${piece.page}-${piece.side}`} onClick={()=>{setSelected(piece);setSheet('preview');}} aria-label={`预览输出第 ${index+1} 页`}><div className="paper-crop" style={crop(piece)}/><span>{index+1}</span><small>{piece.page} · {piece.side < 0 ? '原页' : directions[piece.side]}</small></button>;
  return <div className="pdf-app">
    <header className="toolbar"><button aria-label="返回 PDF 工具" onClick={()=>navigate(screen==='result'?'editor':'home')}><ArrowLeftIcon/></button><div><strong>{screen==='home'?'PDF 工具':screen==='result'?'结果预览':'第一单元.pdf'}</strong><small>{screen==='home'?'文档处理，就在本机':screen==='result'?`${parts.length} 页 · 演示结果`:'2 页 · 本机文件 · 演示'}</small></div><button aria-label="文件选项" onClick={()=>setSheet('file')}><DotsHorizontalIcon/></button></header>
    <MobileScroll className="pdf-scroll"><main>
      {screen==='home' ? <div className="home"><h1>让文档，顺手一点。</h1><p>生成、转换与页面整理</p>{['生成 PDF','PDF 转出','整理 PDF'].map((group,i)=><section key={group}><h2>{group}</h2><div className="tool-grid">{(i===0?['图片生成','文本生成','Markdown']:i===1?['转图片','提取文本']:['页面对半切','拆分文件','合并文件']).map((label,j)=><button key={label} onClick={()=>{if(label==='页面对半切')setSheet('import');else setSheet('planned');}}>{i===0?(j===0?<ImageIcon/>:<FileTextIcon/>):i===1?<CopyIcon/>:j===0?<ScissorsIcon/>:<FileTextIcon/>}<span>{label}</span></button>)}</div></section>)}<p className="hint"><InfoCircledIcon/> 原型演示：先体验「页面对半切」</p></div> : screen==='editor' ? <>
      <section className="workspace"><div className="canvas" data-scroll-drag="ignore" onPointerDown={e=>{e.currentTarget.setPointerCapture(e.pointerId);const b=e.currentTarget.getBoundingClientRect();setRatio(Math.max(10,Math.min(90,Math.round((axis==='vertical'?(e.clientX-b.left)/b.width:(e.clientY-b.top)/b.height)*100))));}} onPointerMove={e=>{if(!e.currentTarget.hasPointerCapture(e.pointerId))return;const b=e.currentTarget.getBoundingClientRect();setRatio(Math.max(10,Math.min(90,Math.round((axis==='vertical'?(e.clientX-b.left)/b.width:(e.clientY-b.top)/b.height)*100))));}}><img src={asset} alt={`示例文档第 ${page} 页：自然与四季`} style={{transform:`scale(${zoom})`}}/><span className="page-stamp">{page}</span><div className={`guide ${axis}`} style={axis==='vertical'?{left:`${ratio}%`}:{top:`${ratio}%`}}><span>{axis==='vertical'?'↔':'↕'}</span></div></div><div className="canvas-tools"><div><button aria-label="缩小" onClick={()=>setZoom(Math.max(1,zoom-.25))}><ZoomOutIcon/></button><button aria-label="放大" onClick={()=>setZoom(Math.min(2,zoom+.25))}><ZoomInIcon/></button></div><span>{page} / 2</span><button aria-label="适合页面" onClick={()=>setZoom(1)}><EnterFullScreenIcon/></button></div><Carousel ariaLabel="原始页面" contentClassName="source-track">{[1,2].map(p=><button className={`source ${p===page?'active':''}`} key={p} onClick={()=>setPage(p)}><img src={asset} alt={`原始第 ${p} 页`}/><span>{p}</span></button>)}</Carousel></section>
      <section className="settings"><div className="grab"/><h1>页面对半切</h1><p>将每页按所选方向切分为两页</p><div className="segmented">{['vertical','horizontal'].map((a,i)=><button className={axis===a?'active':''} key={a} onClick={()=>setAxis(a)}>{i===0?<ColumnsIcon/>:<RowsIcon/>}{i===0?'垂直':'水平'}</button>)}</div><div className="ratio"><label htmlFor="ratio">切分位置</label><input id="ratio" aria-label="切分位置" data-scroll-drag="ignore" type="range" min="10" max="90" value={ratio} onChange={e=>setRatio(Number(e.target.value))}/><button onClick={()=>{setRatioDraft(String(ratio));setSheet('ratio');}}>{ratio}%</button></div><button className="row" onClick={()=>setSheet('scope')}><span>应用到</span><span>{scope==='all'?'全部 2 页':`第 ${scope} 页`}<ChevronRightIcon/></span></button><button className="row" onClick={()=>setSheet('order')}><span>输出顺序</span><span>{order==='page'?'逐页':'分组'}：{directions.join(' → ')}<ChevronRightIcon/></span></button><div className="preview-title"><strong>预览：2 页 → {parts.length} 页</strong><small>点击放大</small></div><Carousel ariaLabel="切分结果预览" contentClassName="output-track">{parts.map(thumb)}</Carousel><p className="hint"><InfoCircledIcon/> 保留文字与矢量内容</p></section>
      </> : <section className="result"><div className="success"><CheckCircledIcon/><h1>切分预览已就绪</h1><p>2 页 → {parts.length} 页 · 原文件保持不变</p></div><div className="result-grid">{parts.map(thumb)}</div><p className="demo-note">这是交互演示。两张源页使用同一示例素材；未读取、上传或处理你的真实 PDF。</p></section>}
    </main></MobileScroll>
    {screen!=='home'&&<footer className="actions" style={{bottom:bottomInset}}>{screen==='editor'?<><button className="secondary" onClick={reset}>重置</button><button className="primary" onClick={()=>navigate('result')}>生成 {parts.length} 页 PDF</button></>:<><button className="secondary" onClick={()=>navigate('editor')}>调整</button><button className="primary" onClick={()=>setSheet('save')}><DownloadIcon/> 保存到本机</button></>}</footer>}
    <BottomSheet open={sheet!==''} onOpenChange={open=>{if(!open){keyboard.hide();setSheet('');}}} title={({scope:'应用到哪些页',order:'输出顺序',ratio:'精确切分位置',file:'文件选项',import:'选择 PDF',save:'保存结果',preview:'页面预览',planned:'功能设计范围'} as Record<string,string>)[sheet]||''} snap={sheet==='preview'?.8:.53}>
    <div className="sheet-body" tabIndex={0}>
      {sheet==='scope'&&<><p>未选中的页面保留完整原页。</p>{[['all','全部 2 页'],['1','仅第 1 页'],['2','仅第 2 页']].map(([v,l])=><button className={`option ${scope===v?'chosen':''}`} key={v} onClick={()=>{setScope(v);setSheet('');}}>{l}{scope===v&&<CheckCircledIcon/>}</button>)}</>}
      {sheet==='order'&&<>{[['page',`逐页：第 1 页${directions[0]}、${directions[1]}，再第 2 页`],['group',`分组：先全部${directions[0]}页，再全部${directions[1]}页`]].map(([v,l])=><button className={`option ${order===v?'chosen':''}`} key={v} onClick={()=>{setOrder(v);setSheet('');}}>{l}</button>)}<p>分组时，未切分的原页放在末尾。</p></>}
      {sheet==='ratio'&&<><p>输入 10–90 之间的比例（%）</p><KeyboardInput aria-label="切分百分比" type="number" min={10} max={90} value={ratioDraft} onChange={e=>setRatioDraft(e.target.value)}/><button onPointerDown={e=>e.preventDefault()} className="primary full" disabled={!ratioDraft||Number(ratioDraft)<10||Number(ratioDraft)>90} onClick={()=>{setRatio(Number(ratioDraft));keyboard.hide();setSheet('');}}>完成</button></>}
      {(sheet==='file'||sheet==='import')&&<><p>演示文档 · 第一单元.pdf · 2 页</p><button className="option chosen" onClick={()=>{reset();navigate('editor');setSheet('');}}><FileTextIcon/> 使用示例 PDF<ChevronRightIcon/></button><p className="demo-note">正式 APP 将通过系统文件选择器导入本地 PDF。本原型只验证界面与操作流程，不读取本机文件。</p></>}
      {sheet==='save'&&<>{saved?<div className="success"><CheckCircledIcon/><h2>保存流程演示完成</h2><p>{name}</p><p>未生成文件，也未写入手机存储。</p><button onPointerDown={e=>e.preventDefault()} className="primary full" onClick={()=>setSheet('')}>完成</button></div>:<><label>文件名</label><KeyboardInput aria-label="文件名" value={name} onChange={e=>setName(e.target.value)}/><p>目标位置：本机文档 / MyTools</p><p className="demo-note">正式 APP 将打开系统保存窗口。本次只演示命名与确认。</p><button onPointerDown={e=>e.preventDefault()} className="primary full" disabled={!name.trim()} onClick={()=>{keyboard.hide();setSaved(true);}}>确认保存（演示）</button></> }</>}
      {sheet==='preview'&&selected&&<><div className="large-paper" style={crop(selected)}/><p>源第 {selected.page} 页 · {selected.side<0?'保留原页':directions[selected.side]} · {ratio}%</p></>}
      {sheet==='planned'&&<><p>当前交互原型重点展示「页面对半切」。其余功能的本机实现路径见 PDF 重设计方案。</p><button onPointerDown={e=>e.preventDefault()} className="primary full" onClick={()=>setSheet('import')}>体验页面对半切</button></>}
    </div></BottomSheet>
  </div>;
}
