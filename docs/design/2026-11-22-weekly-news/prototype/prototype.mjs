// 此文件只演示交互与排版，不连接任何生产接口。
const paths = {
  back:'<path d="m14 5-7 7 7 7"/>', right:'<path d="m9 5 7 7-7 7"/>', close:'<path d="m6 6 12 12M18 6 6 18"/>',
  news:'<rect x="4" y="3" width="16" height="18" rx="2"/><path d="M8 7h8M8 11h3v4H8zM14 11h2M14 15h2M8 18h8"/>',
  grid:'<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  search:'<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>', check:'<path d="m5 12 4 4 10-10"/>',
  clock:'<circle cx="12" cy="12" r="9"/><path d="M12 6v6l4 2"/>', location:'<path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Z"/><circle cx="12" cy="10" r="2"/>',
  image:'<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="8" cy="9" r="1.5"/><path d="m3 17 6-5 4 3 4-5 4 5"/>',
  link:'<path d="M14 3h7v7M21 3 11 13M10 5H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5"/>',
  download:'<path d="M12 3v12m-5-5 5 5 5-5M4 16v4h16v-4"/>', file:'<path d="M5 3h9l5 5v13H5ZM14 3v6h5M8 13h8M8 17h6"/>',
  car:'<path d="m5 9 2-5h10l2 5M3 17V9h18v8ZM5 17v3M19 17v3M6 12h2M16 12h2"/>', transfer:'<path d="M3 7h18m-4-4 4 4-4 4M21 17H3m4-4-4 4 4 4"/>',
  archive:'<path d="M3 4h18v5H3ZM5 9v12h14V9M10 12h4"/>', video:'<rect x="3" y="5" width="14" height="14" rx="2"/><path d="m17 10 4-3v10l-4-3"/>',
  book:'<path d="M12 5c-3-2-6-2-9-1v15c3-1 6-1 9 1 3-2 6-2 9-1V4c-3-1-6-1-9 1ZM12 5v15"/>', chat:'<path d="M21 11a8 8 0 0 1-8 8H7l-4 3V7a4 4 0 0 1 4-4h6a8 8 0 0 1 8 8Z"/><path d="M7 8h8M7 12h6"/>', user:'<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>'
};
const icon = name => `<svg viewBox="0 0 24 24" aria-hidden="true">${paths[name] || paths.news}</svg>`;
const categories = ['技术','国际','国内','本地','流行','军事','财经','社会','体育','娱乐'];
const counts = [20,20,20,16,20,20,20,20,20,20];
const stateLabels = [['tools','工具入口'],['empty','首次获取'],['running','后台生成'],['ready','分类结果'],['detail','新闻详情'],['partial','部分完成'],['history','历史记录']];
const headlines = [
  ['开源模型发布新版本，社区关注部署成本与性能表现','开发者工具更新：从代码检索到自动化测试','开源数据库优化向量检索，公布性能评估方法'],
  ['多边会议公布联合声明，后续议题进入协商','跨境基础设施项目公布新阶段安排','国际机构发布观察报告，梳理近期变化'],
  ['公共服务新举措落地，办理流程进一步简化','重点工程更新建设进度，配套设施同步推进','科研团队公布阶段性成果与后续验证计划'],
  ['城市公共交通调整方案公布，新增便民接驳','周末文化活动开放预约，场馆服务时间延长','社区服务设施更新，居民可就近办理业务'],
  ['一周网络热点：新消费方式引发讨论','户外休闲话题升温，平台公布趋势观察','内容创作者分享新工具，社区形成使用指南'],
  ['公开军事发布会介绍近期训练安排','国防技术研究公布阶段性试验信息','国际防务展展示装备与配套技术'],
  ['产业数据更新，市场关注供需变化','企业公布经营进展，披露重点业务数据','金融服务政策调整，说明适用范围'],
  ['便民服务覆盖扩大，多地优化线上办理','公益项目公布进展，志愿者服务持续开展','公共安全提示发布，提醒关注日常防护'],
  ['本周赛事迎来关键轮次，赛程安排公布','运动员完成阶段备战，分享训练计划','全民健身活动启动，开放公众参与'],
  ['新片公布制作特辑，主创分享创作过程','文化演出发布新档期，场馆开放预约','音乐节公布节目安排与观演提示']
];
const summaries = [
  '整理版本变化、已公开的评测依据和使用条件，并把多篇重复报道合并为一条，便于了解实际进展。',
  '提炼公开信息中的关键变化、相关时间与适用范围，保留来源，避免把讨论或推测写成已确认事实。',
  '汇总不同来源的已知信息，对尚未确认的细节保留边界，具体安排以原始发布内容为准。'
];
let screen='empty', selected=0, detailIndex=0, job=null, hasReport=false, partial=false, expanded=false, sheet=false, tick=null, noticeTimer=null;
const app=document.querySelector('#app');
const isFailed = i => partial && [5,9].includes(i);
const count = i => isFailed(i) ? 0 : counts[i];
const total = () => counts.reduce((sum,_,i)=>sum+count(i),0);
const fixedRange = '11/15 14:30 — 11/22 14:30';
const titleFor = (i,n) => headlines[i][n%3] + (n>2 ? ` · 议题 ${n+1}` : '');
function toolbar(title,back='tools',right=true){return `<header class="toolbar"><button class="icon-button" data-action="navigate" data-value="${back}" aria-label="返回">${icon('back')}</button><h2>${title}</h2>${right?'<button class="text-button" data-action="navigate" data-value="history">历史</button>':''}</header>`;}
function sourceImage(size){return `<div class="source-image ${size}" role="img" aria-label="设计稿原图位置，不是真实新闻图片">${icon('image')}<span>新闻原图${size==='large'?'位置':''}</span></div>`;}
function actionBar(label,action,caption){return `<div class="bottom-action"><button class="primary" data-action="${action}">${icon(action==='start'?'news':'clock')}${label}</button><p>${caption}</p></div>`;}
function toolsView(){const tools=[['download','下载工具','下载与管理',''],['file','PDF 工具','处理 PDF','peach'],['news','每日新闻','最近七天要点','new'],['car','雅迪车辆','车辆与出行',''],['grid','二维码','扫描与生成',''],['transfer','文件传输','设备间传送',''],['archive','解压归档','压缩与解压','peach']];return `<header class="toolbar" style="padding-left:20px"><h2>工具</h2></header><div class="scroll pad"><div class="search">${icon('search')}搜索工具</div><p class="tools-hint">常用能力，随手打开。</p>${job?'<button class="secondary" data-action="navigate" data-value="running">每日新闻正在后台整理 · 查看进度</button>':''}<div class="section-title">日常工具</div><div class="tool-grid">${tools.map(([id,title,sub,color])=>`<button class="tool-card ${id==='news'?'new':''}" data-action="${id==='news'?'open-news':'demo'}"><span class="tool-icon ${color}">${icon(id)}${id==='news'?'<i class="new-dot"></i>':''}</span><strong>${title}</strong><small>${sub}</small></button>`).join('')}</div><div class="section-title">创作工具</div><div class="tool-grid">${[['image','图片生成','purple'],['video','视频生成','blue']].map(([id,title,color])=>`<button class="tool-card" data-action="demo"><span class="tool-icon ${color}">${icon(id)}</span><strong>${title}</strong><small>灵感变成作品</small></button>`).join('')}</div></div><nav class="bottom-tabs" aria-label="应用底部导航">${[['book','书架'],['grid','工具'],['chat','助手'],['user','我的']].map(([id,title])=>`<button class="bottom-tab ${id==='grid'?'selected':''}" data-action="demo">${icon(id)}${title}</button>`).join('')}</nav>`;}
function emptyView(){return `${toolbar('每日新闻')}<div class="scroll pad"><div class="hero"><div class="hero-mark">${icon('news')}</div><h3>最近七天，<br>有哪些值得关注的事？</h3><p>跨来源收集，合并重复报道。<br>每个领域最多 20 条，逐条查看详情。</p><div class="range">本次获取范围 · 北京时间<strong>2026/11/15 14:30 — 11/22 14:30</strong></div></div><div class="section-title">覆盖领域</div><div class="tag-grid">${categories.map(c=>`<span class="tag">${c}</span>`).join('')}</div><div class="info-line">${icon('location')}本地：江苏 · 按执行服务器所在地</div><div class="info-line">${icon('clock')}点击后后台整理，可随时离开页面。</div></div>${actionBar('获取本周要点','start','最近 7 天 · 手动获取，不再每日自动更新')}`;}
function runningView(){const done=job?.done??6;return `${toolbar('每日新闻')}<div class="scroll pad"><div class="running-card"><span class="status-pill"><i class="spinner"></i>后台运行中</span><h3>正在获取本周要点</h3><p class="small">${job?.retry?'正在重试军事、娱乐':done<2?'正在获取原文与图片':done<9?`正在整理${categories[Math.min(done,9)]}要点`:'正在保存报告'}</p><div class="seg-progress" aria-label="${done} 个分类已完成">${categories.map((_,i)=>`<span class="${i<done?'done':''}"></span>`).join('')}</div><div class="progress-label"><span>${done} / 10 个领域已完成</span><span>最近 7 天</span></div></div><div class="info-line">${icon('clock')}可以离开页面，任务会在后台继续。</div><p class="small subtle" style="margin-top:12px">范围：${fixedRange}</p><div class="section-title">各领域进度</div><div class="status-list">${categories.map((c,i)=>`<div class="status-row">${(job?.retry?![5,9].includes(i):i<done)?icon('check'):(job?.retry?i===5:i===done)?'<i class="spinner"></i>':icon('clock')}<span>${c}${i===3?' · 江苏':''}</span><span class="status-label ${(job?.retry?![5,9].includes(i):i<done)?'good':''}">${(job?.retry?![5,9].includes(i):i<done)?`${counts[i]} 条已完成`:(job?.retry?i===5:i===done)?'整理中':'等待处理'}</span></div>`).join('')}</div><button class="load-more" data-action="finish">演示：立即查看生成结果</button></div><div class="bottom-action"><button class="secondary" data-action="navigate" data-value="tools">返回工具页</button><p>返回不会中断任务</p></div>`;}
function articleCard(n){return `<button class="news-card" data-action="article" data-value="${n}"><div class="news-top"><span class="rank">${String(n+1).padStart(2,'0')}</span><div class="story-main"><h3>${titleFor(selected,n)}</h3><p>${summaries[n%3]}</p></div>${n%3===0?sourceImage('thumb'):''}</div><div class="news-meta"><span>${n%2?'公开报道':'原始发布'} · ${n%3+2} 个来源</span><span>11/${22-Math.min(n,6)}</span><span>详情 ${icon('right').replace('<svg ','<svg style="width:10px;height:10px;vertical-align:middle" ')}</span></div></button>`;}
function resultView(){return `${toolbar('每日新闻')}<div class="scroll pad"><div class="result-heading"><div class="eyebrow">最近七天 · 要点报告</div><h3 style="margin-top:10px">一周要闻，分领域看。</h3><p>2026/${fixedRange} · 北京时间</p></div><div class="metrics"><div><strong>${partial?8:10}</strong><span>个领域已完成</span></div><div><strong>${total()}</strong><span>条去重新闻</span></div></div><p class="generated">11/22 14:52 生成 · 每类最多 20 条</p>${partial?'<div class="notice">军事、娱乐暂未完成，其余内容可以先看。<button data-action="retry">重试 2 个失败分类</button></div>':''}<div class="category-tools"><div class="category-tabs" role="tablist" aria-label="新闻分类">${categories.map((c,i)=>`<button role="tab" aria-selected="${i===selected}" class="${i===selected?'selected':''}" data-action="category" data-value="${i}">${c}</button>`).join('')}</div><button class="all-categories" data-action="sheet" aria-label="全部分类">${icon('grid')}</button></div><div class="list-label"><strong>${categories[selected]}${selected===3?' · 江苏':''} Top ${count(selected)}</strong><span>${isFailed(selected)?'暂未完成':'重复报道已合并'}</span></div>${selected===3?'<p class="small subtle">本期找到 16 条有完整详情的新闻。</p>':''}${isFailed(selected)?'<div class="no-results">'+icon('clock')+'<h3>这个领域暂未完成</h3><p>来源获取超时，其他领域不受影响。</p><button class="load-more" data-action="retry">重试失败分类</button></div>':Array.from({length:expanded?count(selected):3},(_,n)=>articleCard(n)).join('')}${!isFailed(selected)&&!expanded?`<button class="load-more" data-action="expand">展开其余 ${count(selected)-3} 条 ${icon('right').replace('<svg ','<svg style="width:12px;height:12px;vertical-align:middle" ')}</button>`:''}<p class="small subtle" style="text-align:center;margin-top:20px">根据已接入来源整理 · 请以原文为准</p></div>${actionBar('获取本周要点','start','重新获取将按当前时刻计算最近 7 天')}${sheet?categorySheet():''}`;}
function categorySheet(){return `<div class="overlay"><section class="sheet" role="dialog" aria-modal="true" aria-label="全部分类"><header><h3>全部分类</h3><button class="icon-button" data-action="close-sheet" aria-label="关闭分类">${icon('close')}</button></header><div class="sheet-grid">${categories.map((c,i)=>`<button class="sheet-category ${selected===i?'selected':''}" data-action="category" data-value="${i}">${c}${i===3?' · 江苏':''}<small>${isFailed(i)?'暂未完成':`${count(i)} 条新闻`}</small></button>`).join('')}</div><p class="small subtle" style="margin-top:18px">每个领域最多 20 条，重复事件合并到一个主分类。</p></section></div>`;}
function detailView(){return `${toolbar('新闻详情',partial?'partial':'ready',false)}<div class="scroll pad"><div class="detail-kicker"><span>${categories[selected]} · 排名 ${String(detailIndex+1).padStart(2,'0')}</span><span>已合并 ${detailIndex%3+2} 个来源</span></div><h3 class="detail-title">【${categories[selected]}】【2026/11/${22-Math.min(detailIndex,6)}】${titleFor(selected,detailIndex)}</h3><p class="detail-meta">原始发布时间 11/${22-Math.min(detailIndex,6)} 09:20<br>报告范围 ${fixedRange} · 北京时间</p>${detailIndex%3===0?`${sourceImage('large')}<div class="image-caption">原图位置示意 · 正式内容展示来源图注，点击可查看大图。</div>`:''}<div class="summary-box">${summaries[detailIndex%3]}这里展示经来源核验的新闻摘要，帮助快速了解事件经过。</div><div class="section-title">要点</div><ol class="facts"><li>先说明发生了什么、由谁发布、在什么时间发生，保留可核验的信息。</li><li>概括本次更新或进展，与此前报道区分。多篇报道中的重复内容只呈现一次。</li><li>列出已确认的范围和后续安排；材料未证实的影响或结论不写成事实。</li></ol><div class="section-title">合并来源</div>${['原始发布','补充报道'].map((s,i)=>`<button class="source-row" style="width:100%;text-align:left" data-action="source"><div>${s}<small>示例来源 · 2026/11/22 ${i?'11:05':'09:20'}</small></div>${icon('link')}</button>`).join('')}<p class="small subtle" style="margin-top:24px">以上为版式示例，不代表真实新闻。正式报告中的每条新闻均保留对应原文链接。</p></div>`;}
function historyView(){return `${toolbar('历史记录',job?'running':partial?'partial':'ready',false)}<div class="scroll pad"><p class="small subtle" style="margin:12px 0 20px">每次手动获取都会保留一份报告。</p><div class="section-title">2026 年 11 月</div>${[22,20,17].map((day,i)=>`<button class="history-card" data-action="history-item" data-value="${i}"><div class="title-line"><h3>11 月 ${day} 日 · 最近七天要点</h3>${icon('right')}</div><p>11/${day-7} 14:30 — 11/${day} 14:30</p><span class="badge ${i===1?'warn':''}">${i===1?'部分完成':'已完成'}</span><footer><span>${i===1?'8 个领域 · 156':'10 个领域 · 196'} 条新闻</span><span>14:52 生成</span></footer></button>`).join('')}<p class="small subtle" style="text-align:center;margin-top:24px">仅展示手动获取记录 · 保留 90 天</p></div>`;}
const notes={
  tools:['熟悉的工具页，一个新入口。','保持现有结构','加入「日常工具」三列网格，不增加底部主导航。','容易找到','名称「每日新闻」，副标题「最近七天要点」；工具搜索可命中。'],
  empty:['只需要一个决定：现在获取。','明确时间范围','按钮保持「获取本周要点」，但明确使用最近 168 小时，不按自然周。','默认十个领域','首期无需配置来源或模型。地区取执行服务器，已在页面说明。'],
  running:['离开页面，也会继续整理。','真实进度','展示阶段和已完成分类，不用动画时间伪造百分比。','任务可恢复','服务端持久化。重新进入查同一任务，重复点击不会重复启动。'],
  ready:['先看标题，再按需读详情。','一类最多二十条','每条都有摘要、来源和详情。跨分类去重，不用旧闻补足数量。','原图优先','缩略图和详情图来自新闻本身；无可用图片时自然采用文字排版。'],
  detail:['每条新闻，都有依据。','读得懂','摘要、事实要点和来源逐层展开；保留主要稿件的真实发布时间。','查得到','合并多个来源但不丢原文链接，原图附图注，不自动生成配图。'],
  partial:['先看已完成的，失败项再试。','局部失败可恢复','军事或娱乐失败不影响其他分类；重试复用已完成结果与原窗口。','少于二十不是失败','只有 16 条合格新闻时如实显示，不以重复或无正文条目凑数。'],
  history:['生成一次，留下一份记录。','保存范围与版本','历史按生成时刻排列，同一天可以有不同滚动时间窗。','保留上次结果','新任务执行期间仍可查看旧报告，不用等待全部重做。']
};
function render(){app.innerHTML=screen==='tools'?toolsView():screen==='empty'?emptyView():screen==='running'?runningView():screen==='detail'?detailView():screen==='history'?historyView():resultView();document.querySelector('#review-states').innerHTML=stateLabels.map(([id,label],i)=>`<button class="state-button ${screen===id?'active':''}" data-state="${id}" aria-pressed="${screen===id}"><span>0${i+1}</span>${label}</button>`).join('');const note=notes[screen]||notes.ready;document.querySelector('#review-notes').innerHTML=`<div class="notes-kicker">INTERACTION NOTES</div><h2>${note[0]}</h2><div class="note-row"><h3>${note[1]}</h3><p>${note[2]}</p></div><div class="note-row"><h3>${note[3]}</h3><p>${note[4]}</p></div><div class="tokens"><span style="background:#087E61"></span><span style="background:#E3F2EB"></span><span style="background:#F5F7F5"></span><span style="background:#FFFFFF"></span></div>`;if(sheet)app.querySelector('[data-action="close-sheet"]')?.focus();else app.querySelector('.category-tabs .selected')?.scrollIntoView({block:'nearest',inline:'nearest'});}
function toast(text){clearTimeout(noticeTimer);app.querySelector('.toast')?.remove();const el=document.createElement('div');el.className='toast';el.setAttribute('role','status');el.textContent=text;app.append(el);noticeTimer=setTimeout(()=>el.remove(),3500);}
function stopDemo(){clearInterval(tick);tick=null;}
function finish(){stopDemo();job=null;hasReport=true;partial=false;screen='ready';selected=0;expanded=false;render();}
function start(){
  // 已存在的任务沿用进度；演示计时器独立于当前浏览页面。
  if(!job)job={done:0};
  if(!tick)tick=setInterval(()=>{
    job.done=Math.min(10,job.done+2);
    if(job.done>=10){
      stopDemo();job=null;hasReport=true;partial=false;
      if(screen==='running')screen='ready';
      render();
      if(screen!=='ready')toast('本周要点已整理完成，可进入每日新闻查看。');
    }else if(screen==='running')render();
  },3200);
  screen='running';render();
}
function showState(value){stopDemo();job=null;screen=value;sheet=false;expanded=false;partial=value==='partial';hasReport=['ready','detail','partial','history'].includes(value);if(value==='running')job={done:6};if(value==='detail'){selected=0;detailIndex=0;}render();}
document.addEventListener('click',event=>{const control=event.target.closest('button');if(!control)return;if(control.dataset.state){showState(control.dataset.state);return;}const action=control.dataset.action,value=control.dataset.value;if(action==='navigate'){sheet=false;screen=value;render();}else if(action==='open-news'){screen=job?'running':hasReport?(partial?'partial':'ready'):'empty';render();}else if(action==='start')start();else if(action==='finish')finish();else if(action==='category'){selected=Number(value);sheet=false;expanded=false;render();}else if(action==='sheet'){sheet=true;render();}else if(action==='close-sheet'){sheet=false;render();app.querySelector('.all-categories')?.focus();}else if(action==='article'){detailIndex=Number(value);screen='detail';render();}else if(action==='expand'){expanded=true;const top=app.querySelector('.scroll').scrollTop;render();app.querySelector('.scroll').scrollTop=top;}else if(action==='retry'){job={done:8,retry:true};start();toast('演示：复用已完成结果，仅重试失败分类。');}else if(action==='history-item'){partial=Number(value)===1;screen=partial?'partial':'ready';render();toast('历史排版示例：打开演示报告。');}else if(action==='source')toast('设计稿未绑定真实原文；上线后打开对应来源。');else if(action==='demo')toast('此处保留现有工具，本原型演示每日新闻。');});
document.addEventListener('keydown',event=>{if(event.key==='Escape'&&sheet){sheet=false;render();app.querySelector('.all-categories')?.focus();}});
render();
