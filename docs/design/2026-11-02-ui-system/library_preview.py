"""生成组件库样例与规范卡片，仅用于本地设计评审。"""
from pathlib import Path
from html import escape
import calendar
import json

ROOT = Path(__file__).resolve().parent


def ic(name):
    return f'<img class="ic" src="assets/{name}.svg" alt="">'


def btn(text, kind='secondary', extra='', symbol=''):
    return f'<button type="button" class="btn {kind}" {extra}>{ic(symbol) if symbol else ""}<span>{text}</span></button>'


def note(text, kind=''):
    return f'<p class="lib-note {kind}">{text}</p>'


def field(label, ident, value='', placeholder='', extra='', kind='text'):
    return f'<label class="lib-field-label" for="{ident}">{label}</label><input class="lib-input" id="{ident}" type="{kind}" value="{value}" placeholder="{placeholder}" {extra}>'


def options(items, name, kind='radio', checked=0):
    return '<div class="lib-options">'+''.join(f'<label><input type="{kind}" name="{name}" value="{i}" {"checked" if i==checked else ""}><span>{s}</span></label>' for i,s in enumerate(items))+'</div>'


def states(kinds=('default','error','disabled'), labels=None):
    labels=labels or {'default':'默认','error':'错误','disabled':'禁用','readonly':'只读','loading':'加载中','empty':'无结果'}
    return '<div class="lib-state-tools" aria-label="样例状态切换">'+''.join(f'<button type="button" data-lib-state="{k}" aria-pressed="{str(k=="default").lower()}">{labels[k]}</button>' for k in kinds)+'</div><div class="lib-state-message" role="status" hidden></div>'


def dropdown(ident, label='选择书源', value='综合书源', searchable=False, multiple=False, items=None):
    items=items or ['综合书源','公开图书馆','本地导入','暂不可用书源']
    opts=''.join(f'<button type="button" role="option" aria-selected="{str(i==0 and value==v).lower()}" data-option="{v}" {"disabled" if i==3 else ""}><span>{v}</span>{ic("check") if i==0 and value==v else ""}</button>' for i,v in enumerate(items))
    actions = ('<div class="action-group">'+btn("取消","secondary","data-select-cancel")+btn("完成选择","primary","data-select-done")+'</div>') if multiple else ""
    return f'<label class="lib-field-label" id="{ident}-label">{label}</label><div class="lib-select" data-multiple="{str(multiple).lower()}"><button type="button" class="lib-select-trigger" role="combobox" aria-labelledby="{ident}-label" aria-expanded="false" aria-controls="{ident}-options"><span class="lib-selected">{value}</span>{ic("chevron-right")}</button><div class="lib-select-panel" hidden>{f"<input class=lib-input aria-label=搜索选项 placeholder=搜索选项…>" if searchable else ""}<div id="{ident}-options" role="listbox" aria-multiselectable="{str(multiple).lower()}">{opts}</div><p class="lib-select-empty" hidden>没有匹配的选项</p>{actions}</div></div>'


def cal(ident, range_mode=False, hidden=False):
    cells=[]
    for week in calendar.Calendar(0).monthdayscalendar(2026,11):
        for day in week:
            cells.append(f'<button type="button" class="lib-day {"selected" if day==12 else ""}" data-date="2026-11-{day:02}" aria-label="2026年11月{day}日" {"disabled" if day and day<2 else ""}>{day}</button>' if day else '<span class="lib-day-blank"></span>')
    return f'<div class="lib-calendar-scroll" {"hidden" if hidden else ""}><div class="lib-calendar" id="{ident}" data-range="{str(range_mode).lower()}" data-month="11" data-year="2026"><div class="lib-calendar-head">{btn("","icon","data-month-step=-1 aria-label=上个月","chevron-left")}<b class="lib-month-label">2026年11月</b>{btn("","icon","data-month-step=1 aria-label=下个月","chevron-right")}</div><div class="lib-week">'+''.join(f'<span>{d}</span>' for d in ['一','二','三','四','五','六','日'])+'</div><div class="lib-days">'+''.join(cells)+'</div><p class="lib-note">日期格 44 × 44；窄卡片可横向滚动。</p></div></div>'


def search_demo(ident, standalone=False, suggestions=False):
    return f'<form class="lib-search-form" data-search-demo><label class="lib-field-label" for="{ident}">搜索图书</label><div class="lib-search-line"><div class="lib-search-field">{ic("search")}<input id="{ident}" aria-label="搜索图书关键词" placeholder="试试：山海 / 不存在"><button type="button" class="lib-clear" data-search-clear aria-label="清除关键词">{ic("x")}</button></div>{btn("搜索","primary","type=submit" if False else "data-search-submit")}</div><div class="lib-search-results" aria-live="polite">'+('<div class="lib-suggestions"><small>最近搜索</small><button type="button" data-search-word="山海">山海</button><button type="button" data-search-word="城市">城市</button><small>热门 · 阅读与旅行</small></div>' if suggestions else '<p class="lib-note">输入关键词后点击搜索，或按 Enter 提交。</p>')+'</div></form>'


def table_demo():
    return '<div class="lib-table-scroll"><table class="lib-table"><thead><tr><th><label><input type="checkbox" aria-label="选择全部文件" data-table-all></label></th><th><button type="button" data-table-sort>文件名 '+ic('arrow-down-up')+'</button></th><th>大小</th><th>状态</th></tr></thead><tbody><tr><td><input type="checkbox" aria-label="选择阅读笔记"></td><td>阅读笔记.pdf</td><td>2.4 MB</td><td><span class="lib-tag">已完成</span></td></tr><tr><td><input type="checkbox" aria-label="选择城市影像"></td><td>城市影像.mp4</td><td>18 MB</td><td><span class="lib-tag warning">处理中</span></td></tr></tbody></table></div>'


def progress(value=64, label='正在缓存'):
    return f'<div class="lib-progress-copy"><span>{label}</span><b data-progress-label>{value}%</b></div><progress max="100" value="{value}" aria-label="{label}"></progress>'


def demos():
    d={}
    d['a01']='<div class="lib-row wrap">'+btn('开始生成','primary','data-lib-toast="已触发示例操作"')+btn('查看目录')+btn('不可用','secondary','disabled')+'</div>'+states(('default','loading','disabled'))
    d['a02']='<div class="lib-row wrap">'+btn('刷新','quiet','data-lib-toast="列表已刷新"','refresh-cw')+btn('编辑','quiet','data-lib-toast="进入示例编辑"')+btn('移出','quiet danger-text','data-lib-toast="危险操作应先确认"')+'</div>'+note('行内文字操作保持同一基线；危险操作独立着色。')+states(('default','disabled'))
    d['a03']='<div class="lib-row wrap">'+btn('','icon','aria-label=搜索 data-lib-toast="搜索图标按钮"','search')+btn('','icon','aria-label=更多 data-lib-toast="更多图标按钮"','ellipsis')+btn('','icon','aria-label=关闭 disabled','x')+'</div>'+note('44 × 44 点击区，20图标；禁用示例保留可识别图形。')
    d['a04']='<div class="lib-row wrap action-group" role="group" aria-label="保存设置操作">'+btn('取消','secondary','data-lib-toast="已取消示例"')+btn('保存设置','primary','data-lib-toast="设置已保存"')+'</div>'+note('同组等宽、等高、同字重：最小宽96，可见高40，命中高44；颜色区分主次。')+states(('default','loading'))
    d['a05']='<p>阅读前请查看 <a href="FONT-SPEC.md">字体与显示说明 '+ic('external-link')+'</a></p><p class="lib-note">禁用链接：<span aria-disabled="true" class="lib-muted">下载报告（尚未生成）</span></p>'
    d['a06']='<div class="lib-fab-stage"><span>内容滚动区域</span>'+btn('添加','primary lib-fab','data-lib-toast="打开添加入口"','plus')+'</div>'+note('仅高频全局新增使用；避开底部手势区和已有主按钮。')
    d['i01']=field('文件名称','lib-text',placeholder='输入文件名称',extra='maxlength=50')+note('名称将用于本地保存。')+states(('default','error','disabled','readonly'))
    d['i02']='<label class="lib-field-label" for="lib-textarea">画面描述</label><textarea id="lib-textarea" class="lib-input" rows="3" maxlength="200" data-count>清晨的书房，自然光，安静温暖。</textarea><div class="lib-row spread"><span class="lib-note">描述主体、环境与光线</span><small data-count-output>20 / 200</small></div>'+states(('default','error','disabled','readonly'))
    d['i03']='<label class="lib-field-label" for="lib-password">压缩包密码</label><div class="lib-password"> <input class="lib-input" id="lib-password" type="password" value="example-only" autocomplete="off"><button type="button" data-password-toggle aria-label="显示密码">显示</button></div>'+note('本地示例密码；显隐切换保持内容与光标。')+states(('default','error','disabled'))
    d['i04']=field('每份页数','lib-number','4',extra='min=1 max=100 step=1',kind='number')+note('整数，范围1–100；超界在字段下说明。')+states(('default','error','disabled'))
    d['i05']=search_demo('lib-searchbar')+states(('default','loading','empty'))
    d['i06']='<div class="lib-row wrap">'+btn('搜索','primary','data-lib-toast="搜索按钮：提交当前关键词"','search')+btn('取消搜索','quiet','data-lib-toast="搜索已取消"')+btn('','icon','aria-label=打开搜索 data-lib-toast="展开搜索输入"','search')+'</div>'+note('完整提交按钮、取消搜索、仅图标入口三个独立变体。')+states(('default','loading','disabled'))
    d['i07']=search_demo('lib-suggestions',suggestions=True)+note('点击历史词会填入并搜索；没有匹配时保留关键词。')
    d['i08']='<form data-validation-form>'+field('订阅名称（必填）','lib-name',placeholder='如：每日阅读',extra='required')+field('订阅地址（必填）','lib-url',placeholder='https://example.com/feed.xml',extra='required',kind='url')+'<p class="lib-validation-message" role="status">所有必填项完成后可保存。</p>'+btn('保存订阅','primary','data-validate-form')+'</form>'+states(('default','disabled','readonly'))
    d['i09']='<label class="lib-field-label" for="lib-code">验证码</label><div class="lib-row"> <input class="lib-input" id="lib-code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="6位数字">'+btn('获取验证码','quiet','data-verification-code')+'</div>'+note('示例只演示60秒重发倒计时，不发送短信。')+states(('default','error','disabled'))
    d['s01']=dropdown('lib-select')+states(('default','loading','empty','error','disabled'))
    d['s02']=dropdown('lib-search-select','生成模型','请选择模型',True,items=['标准模型','快速模型','精细模型','维护中模型'])+states(('default','loading','empty','error','disabled'))
    d['s03']=dropdown('lib-multi-select','筛选标签','请选择标签',True,True,['在读','已缓存','最近更新','不可用标签'])+'<div class="lib-selection-summary" aria-live="polite">尚未选择标签</div>'+states(('default','error','disabled'))
    d['s04']='<div class="lib-cascade"><div>'+dropdown('lib-cascade-source','来源','远程网盘',items=['远程网盘','本地文件','公开图书馆','暂不可用'])+'</div><div>'+dropdown('lib-cascade-folder','目录','阅读资料',items=['阅读资料','工作文档','旅行相册','受限目录'])+'</div></div>'+note('切换来源先确认清除下级目录；取消保留原路径。')+states(('default','loading','empty','disabled'))
    d['s05']='<fieldset><legend>生成模式（单选）</legend>'+options(['快速 · 优先速度','标准 · 平衡质量','精细 · 更长时间'],'lib-radio')+'</fieldset>'+states(('default','disabled'))
    d['s06']='<fieldset><legend>导出内容（多选）</legend>'+options(['原文内容','章节目录','阅读标记'],'lib-checks','checkbox')+'</fieldset><label class="lib-option"><input type="checkbox" data-check-all>全选 / 取消全选</label>'+states(('default','error','disabled'))
    d['s07']='<div class="lib-setting"><span><b>自动缓存</b><small>连接可用时缓存书架内容</small></span><button type="button" class="lib-switch" role="switch" aria-checked="true" aria-label="自动缓存"><span></span></button></div><div class="lib-setting"><span>不可用设置</span><button type="button" class="lib-switch" role="switch" aria-checked="false" disabled aria-label="不可用设置"><span></span></button></div>'+states(('default','loading','error','disabled'))
    d['s08']='<div class="lib-chips" data-multi-chip>'+''.join(f'<button type="button" aria-pressed="{str(i==0).lower()}">{t}</button>' for i,t in enumerate(['全部','在读','已缓存','最近更新']))+'</div><p class="lib-note">已选筛选会保留文字与边框；选项为空时不给伪选中状态。</p>'+states(('default','disabled'))
    d['d01']='<label class="lib-field-label">选择日期</label><button type="button" class="lib-date-trigger" data-date-open aria-expanded="false"><span data-date-value>2026-11-12</span>'+ic('chevron-right')+'</button>'+cal('lib-date-field-calendar',hidden=True)+'<div class="lib-date-actions lib-row action-group" hidden>'+btn('取消','secondary','data-date-cancel')+btn('确定日期','primary','data-date-confirm')+'</div>'+note('默认格式YYYY-MM-DD；失效日期不可选。')+states(('default','error','disabled'))
    d['d02']=cal('lib-calendar')+'<p class="lib-date-selection" aria-live="polite">已提交：2026-11-12；草稿：2026-11-12</p><div class="lib-row wrap action-group">'+btn('取消更改','secondary','data-calendar-cancel')+btn('确定日期','primary','data-calendar-confirm')+'</div>'+note('2026-11-01为禁用示例；切月不提交，确认后更新值。')
    d['d03']='<div class="lib-range-copy"><div><small>开始</small><b data-range-start>2026-11-12</b></div><span>至</span><div><small>结束</small><b data-range-end>待选择</b></div></div>'+cal('lib-range-calendar',True)+'<div class="lib-row wrap">'+btn('清空范围','quiet','data-range-clear')+btn('演示范围错误','quiet','data-range-error')+'</div><p class="lib-range-status" role="status">先选开始，再选结束；再次点击可重新选择。</p>'
    d['d04']='<p class="lib-field-label">选择时间 · 24小时制</p><div class="lib-time-columns" data-time-picker><div><label>小时</label><div role="listbox" aria-label="选择小时">'+''.join(f'<button type="button" role="option" aria-selected="{str(i==9).lower()}" data-time-hour="{i:02}">{i:02}</button>' for i in range(24))+'</div></div><b>:</b><div><label>分钟</label><div role="listbox" aria-label="选择分钟">'+''.join(f'<button type="button" role="option" aria-selected="{str(i==0).lower()}" data-time-minute="{i:02}">{i:02}</button>' for i in range(0,60,5))+'</div></div></div><p class="lib-time-output" aria-live="polite">已提交09:00；草稿09:00</p><div class="lib-row wrap action-group">'+btn('取消更改','secondary','data-time-cancel')+btn('确定时间','primary','data-time-confirm')+'</div>'+note('可滚动的小时和分钟列；步长5分钟；当前值保持选中。')+states(('default','error','disabled'))
    d['d05']='<label class="lib-field-label">日期与时间</label><button type="button" class="lib-date-trigger" data-date-open aria-expanded="false"><span data-date-value>2026-11-12</span>'+ic('chevron-right')+'</button><input type="hidden" id="lib-datetime-date" data-combined-date value="2026-11-12">'+cal('lib-combined-calendar',hidden=True)+'<div class="lib-date-actions lib-row action-group" hidden>'+btn('取消','secondary','data-date-cancel')+btn('确定日期','primary','data-date-confirm')+'</div><input type="hidden" id="lib-datetime-time" value="09:30"><div class="lib-time-columns" data-time-picker data-initial-time="09:30"><div><label>小时</label><div role="listbox" aria-label="日期时间小时">'+''.join(f'<button type="button" role="option" aria-selected="{str(i==9).lower()}" data-time-hour="{i:02}">{i:02}</button>' for i in range(24))+'</div></div><b>:</b><div><label>分钟</label><div role="listbox" aria-label="日期时间分钟">'+''.join(f'<button type="button" role="option" aria-selected="{str(i==30).lower()}" data-time-minute="{i:02}">{i:02}</button>' for i in range(0,60,5))+'</div></div></div><p class="lib-time-output">已提交09:30；草稿09:30</p><div class="lib-row wrap action-group">'+btn('取消时间','secondary','data-time-cancel')+btn('确定时间','primary','data-time-confirm')+'</div>'+dropdown('lib-timezone','业务时区','Asia/Shanghai（UTC+08:00）',items=['Asia/Shanghai（UTC+08:00）','UTC（UTC+00:00）','Asia/Tokyo（UTC+09:00）','不可用时区'])+'<p class="lib-note">切换时区保留钟面时间，提交值明确携带所选偏移。</p><p class="lib-datetime-output" aria-live="polite">2026-11-12 09:30 · UTC+08:00</p>'+states(('default','error','disabled'))
    d['d06']='<div class="lib-chips" data-quick-date>'+''.join(f'<button type="button" aria-pressed="false" data-days="{n}">{t}</button>' for t,n in [('今天',0),('近7天',7),('近30天',30)])+'</div><div class="lib-quick-range" aria-live="polite">请选择快捷范围</div>'+note('按所选时区计算完整自然日，不用固定毫秒数跨越日期边界。')
    d['n01']='<label class="lib-field-label" for="lib-slider">阅读亮度 <output>70%</output></label><input class="lib-range" id="lib-slider" type="range" min="0" max="100" value="70" data-slider-unit="%">'+note('触摸轨道44高；支持方向键和离散步长。')+states(('default','disabled'))
    d['n02']='<div class="lib-range-copy"><b>页码范围</b><span data-range-number-output>2–8 页</span></div><div class="lib-dual-range"><label>起始页<input class="lib-range" type="range" min="1" max="12" value="2" data-range-low></label><label>结束页<input class="lib-range" type="range" min="1" max="12" value="8" data-range-high></label></div>'+note('双滑块各有独立名称；起点不允许越过终点。')+states(('default','disabled'))
    d['n03']='<label class="lib-field-label">字号</label><div class="lib-stepper" data-stepper data-min="12" data-max="32">'+btn('−','icon','data-step=-1 aria-label=减小字号')+'<output>18</output>'+btn('+','icon','data-step=1 aria-label=增大字号')+'</div>'+note('步长1；12–32，到边界禁用对应按钮。')+states(('default','disabled'))
    d['n04']='<p class="lib-field-label">体验评价</p><div class="lib-rating" role="radiogroup" aria-label="体验评价">'+''.join(f'<button type="button" role="radio" aria-checked="{str(i==4).lower()}" data-rating="{i}">{i}<span>分</span></button>' for i in range(1,6))+'</div><p class="lib-rating-output" aria-live="polite">已选择4分 · 清晰易用</p>'+states(('default','disabled'))
    d['n05']='<p class="lib-field-label">阅读背景</p><div class="lib-colors">'+''.join(f'<button type="button" style="--swatch:{color}" data-color="{color}" aria-label="{name}" aria-pressed="{str(i==0).lower()}">{ic("check") if i==0 else ""}</button>' for i,(name,color) in enumerate([('纸白','#F6F1E6'),('浅绿','#E3F2EB'),('夜间','#19231E')]))+'</div><label class="lib-color-custom">自定义 <input type="color" value="#f6f1e6" aria-label="自定义阅读背景"></label><div class="lib-color-preview">阅读背景示例 Aa 123</div>'+states(('default','disabled'))
    d['v01']='<div class="lib-demo-header">'+btn('','icon','aria-label=返回 data-lib-toast="返回上一页"','chevron-left')+'<h4>图书详情</h4>'+btn('编辑','quiet','data-lib-toast="编辑图书信息"')+'</div><div class="lib-demo-header"><h4>工具</h4>'+btn('','icon','aria-label=搜索工具 data-lib-toast="展开搜索工具"','search')+'</div>'
    d['v02']='<div class="lib-tabset"><div role="tablist" aria-label="作品视图">'+''.join(f'<button type="button" role="tab" aria-selected="{str(i==0).lower()}" data-tab="{i}">{t}</button>' for i,t in enumerate(['创作','我的作品','任务记录']))+'</div><div class="lib-tabpanel" role="tabpanel">填写画面描述并开始创作</div></div>'+note('显示下划线指示；切换标签不丢失草稿。')
    d['v03']='<div class="lib-segment" role="group" aria-label="阅读来源">'+''.join(f'<button type="button" aria-pressed="{str(i==0).lower()}">{t}</button>' for i,t in enumerate(['书架','书源','远程','本地','RSS']))+'</div><p class="lib-note">同级内容切换；RSS与其他来源同级。</p>'+states(('default','disabled'))
    d['v04']='<nav class="lib-bottom-nav" aria-label="示例一级导航">'+''.join(f'<button type="button" aria-current="{str(i==0).lower()}">{ic(sym)}<span>{title}</span></button>' for i,(title,sym) in enumerate([('阅读','book-open'),('工具','wrench'),('DSH','message-circle'),('多媒体','images'),('网盘','cloud')]))+'</nav>'+note('五个一级入口；选中包含文字和形态变化。')
    d['v05']='<nav class="lib-breadcrumb" aria-label="目录路径"><button type="button" data-lib-toast="回到全部文件">全部文件</button>'+ic('chevron-right')+'<button type="button" data-lib-toast="进入阅读资料">阅读资料</button>'+ic('chevron-right')+'<span aria-current="page">文档</span></nav>'+note('过长路径保留首级和当前级，中间层级收纳。')
    d['v06']='<div class="lib-pagination" aria-label="文件分页">'+btn('','icon','aria-label=上一页 data-page-step=-1 disabled','chevron-left')+'<span><b data-page-number>1</b> / 8</span>'+btn('','icon','aria-label=下一页 data-page-step=1','chevron-right')+'</div>'+note('加载新页时保留当前位置；第一页禁用上一页。')
    d['v07']='<ol class="lib-steps"><li class="done"><b>1</b><span>选择文件</span></li><li class="active" aria-current="step"><b>2</b><span>配置输出</span></li><li><b>3</b><span>查看结果</span></li></ol>'+btn('演示下一步','quiet','data-next-step')
    d['v08']='<div class="lib-menu-demo">'+btn('更多操作','secondary','data-menu-trigger aria-expanded=false','ellipsis')+'<div class="lib-menu" role="menu" hidden><button role="menuitem" type="button" data-lib-toast="重命名示例">重命名</button><button role="menuitem" type="button" data-lib-toast="移动示例">移动到…</button><hr><button class="lib-danger" role="menuitem" type="button" data-lib-toast="删除需要确认">删除文件</button></div></div>'+note('菜单项44高；危险操作单独分组，点击后继续确认。')
    d['c01']='<button type="button" class="lib-list-row" data-lib-toast="打开阅读资料">'+ic('folder')+'<span><b>阅读资料</b><small>12项 · 今天更新</small></span>'+ic('chevron-right')+'</button><button type="button" class="lib-list-row" disabled>'+ic('folder')+'<span><b>受限目录</b><small>暂时没有访问权限</small></span>'+ic('chevron-right')+'</button>'
    d['c02']='<button type="button" class="lib-list-row" data-lib-toast="进入阅读偏好">'+ic('type')+'<span><b>阅读偏好</b><small>字体、页边距与翻页</small></span>'+ic('chevron-right')+'</button><div class="lib-setting"><span>自动续读</span><button type="button" class="lib-switch" role="switch" aria-checked="true" aria-label="自动续读"><span></span></button></div><p class="lib-note">静态状态不伪装成整行可点击入口。</p>'
    d['c03']='<article class="lib-content-card"><span class="lib-tag">阅读计划</span><h4>为阅读留半小时</h4><p>从一个容易坚持的节奏开始，记录今天的一个想法。</p>'+btn('查看计划','quiet','data-lib-toast="打开示例阅读计划"')+'</article><p class="lib-note">内容卡片按任务分组，避免每一行都套卡片。</p>'
    d['c04']=table_demo()+note('示例支持全选与按名称排序；窄屏横向滚动，禁止压小字号。')
    d['c05']='<div class="lib-tree"><details open><summary>'+ic('folder')+'阅读资料</summary><button type="button" data-lib-toast="打开散文目录">'+ic('folder')+'散文</button><details><summary>'+ic('folder')+'小说</summary><button type="button" data-lib-toast="打开山海之间">'+ic('book-open')+'山海之间</button></details></details><details><summary>'+ic('folder')+'旅行相册</summary><p class="lib-note">空目录</p></details></div>'+note('层级缩进16；展开与选择分开，空节点显示说明。')
    d['c06']='<div class="lib-row wrap"><span class="lib-tag">已缓存</span><span class="lib-tag warning">排队中</span><span class="lib-tag error">失败</span><span class="lib-badge-label">未读 <b>12</b></span><span class="lib-badge-label">消息 <b>99+</b></span></div>'+note('状态文字和计数不是按钮；超过上限显示99+。')
    d['c07']='<div class="lib-row wrap"><span class="lib-avatar" aria-label="MyTools用户头像">M</span><span class="lib-avatar small" aria-label="匿名用户头像">'+ic('user')+'</span><span><b>MyTools 用户</b><small class="lib-block">账号已登录</small></span></div>'+note('无头像、加载失败时使用同尺寸占位；不破坏布局。')
    d['c08']='<dl class="lib-description"><div><dt>文件名称</dt><dd>阅读笔记.pdf</dd></div><div><dt>文件大小</dt><dd>2.4 MB</dd></div><div><dt>保存位置</dt><dd>本地 / 阅读资料</dd></div><div><dt>同步状态</dt><dd>尚未同步 '+btn('复制信息','quiet','data-lib-toast="文件信息已复制（示例）"','copy')+'</dd></div></dl>'
    d['f01']='<div class="lib-notice">'+ic('circle-alert')+'<span><b>当前处于离线状态</b><small>已缓存内容可继续阅读。</small></span></div><div class="lib-notice error">'+ic('circle-alert')+'<span><b>连接失败</b><small>请检查来源地址后重试。</small></span>'+btn('重试','quiet','data-lib-toast="已重新尝试连接"')+'</div>'
    d['f02']='<div class="lib-toast-sample">'+ic('check')+'文件信息已复制</div><div class="lib-row wrap">'+btn('成功提示','quiet','data-lib-toast="已保存"')+btn('失败提示','quiet','data-lib-toast="保存失败，请重试"')+'</div>'+note('短反馈不挡住主操作；长期错误保留在任务附近。')
    d['f03']=progress()+ '<div class="lib-row wrap">'+btn('演示进度','quiet','data-progress-demo')+btn('演示失败','quiet','data-progress-fail')+'</div><p class="lib-progress-status" aria-live="polite">原文件缓存中，可以继续浏览。</p>'
    d['f04']='<div class="lib-row">'+ic('loader-circle')+'<span>正在读取目录…</span></div><p class="lib-note">未知总量使用不定式，不伪造64%等确定百分比。</p>'+btn('结束加载','quiet','data-spinner-finish')
    d['f05']='<div class="lib-skeleton" aria-label="列表加载中"><span></span><div><i></i><i></i><i></i></div></div>'+note('骨架对应封面、标题、说明位置；后台刷新不清空已有内容。')+btn('显示内容','quiet','data-skeleton-finish')
    d['f06']='<div class="lib-empty">'+ic('book-open')+'<h4>书架还没有图书</h4><p>搜索书源，或导入本地图书。</p>'+btn('搜索图书','primary','data-lib-toast="打开搜索入口"')+'</div><div class="lib-mini-empty">没有符合筛选条件的结果 '+btn('清除筛选','quiet','data-lib-toast="筛选已清除"')+'</div>'
    d['f07']='<div class="lib-empty">'+ic('circle-alert')+'<h4>目录暂时无法加载</h4><p>已保留当前路径，请稍后重试。</p>'+btn('重新加载','secondary','data-retry-demo')+'</div><div class="lib-state-message" role="status" hidden></div>'
    d['f08']='<div class="lib-result">'+ic('check-check')+'<div><h4>PDF合并完成</h4><p>共12页，保存到本地后可离线使用。</p></div></div><div class="lib-row wrap">'+btn('预览结果','primary','data-lib-toast="打开示例结果预览"')+btn('继续处理','quiet','data-lib-toast="开始新的示例任务"')+'</div>'+states(('default','error'))
    d['f09']='<div class="lib-refresh-content"><div class="lib-mini-row">山海之间 <span>已缓存</span></div><div class="lib-mini-row">城市里的风 <span>昨天更新</span></div></div><div class="lib-row wrap">'+btn('刷新列表','quiet','data-refresh-demo','refresh-cw')+btn('加载更多','quiet','data-more-demo')+'</div><p class="lib-refresh-status" aria-live="polite">已有内容保留；刷新失败不闪空。</p>'
    d['o01']='<div class="lib-inline-dialog"><h4>清除离线缓存？</h4><p>阅读记录会保留，再次打开需要下载。</p><div class="lib-row wrap action-group">'+btn('取消')+btn('清除缓存','destructive','data-open-dialog="dialog"')+'</div></div>'+btn('打开完整对话框','quiet','data-open-dialog="dialog"')
    d['o02']='<div class="lib-sheet-sample"><span class="lib-sheet-handle"></span><h4>选择书源</h4><div class="lib-mini-row">综合书源 <span>当前</span></div><div class="lib-mini-row">公开图书馆</div>'+btn('打开底部面板','quiet','data-open-dialog="sheet"')+'</div>'
    d['o03']='<div class="lib-popover-demo">'+btn('排序方式','secondary','data-popover-trigger aria-expanded=false','arrow-down-up')+'<div class="lib-popover" hidden><b>排序方式</b>'+options(['最近阅读','书名升序','添加时间'],'lib-popover-sort')+'</div></div>'+note('锚定触发位置，空间不足时反向展开；点击外部或Escape关闭。')
    d['o04']='<div class="lib-tooltip-wrap">'+btn('缓存说明','quiet','aria-describedby=lib-tooltip data-tooltip-toggle','circle-alert')+'<div role="tooltip" id="lib-tooltip">缓存保存在当前设备，可离线打开。</div></div>'+note('悬停、键盘聚焦或点击均可查看；核心说明不只藏在提示气泡。')
    d['b01']='<div class="lib-upload-drop">'+ic('upload')+'<b>添加参考图片</b><p>PNG / JPEG，最多10 MB</p><label class="btn secondary">选择文件<input type="file" accept="image/png,image/jpeg" data-upload-input hidden></label></div><p class="lib-upload-name">示例图片.jpg · 2.4 MB</p>'+progress(0,'等待上传')+'<div class="lib-row wrap">'+btn('模拟上传','primary','data-upload-start')+btn('取消','quiet','data-upload-cancel')+btn('模拟失败','quiet','data-upload-fail')+'</div><p class="lib-upload-status" aria-live="polite">仅演示本地队列，不传输文件。</p>'
    d['b02']='<div class="lib-row wrap"><label class="btn secondary">'+ic('folder')+'选择本地文件<input type="file" multiple data-file-picker hidden></label>'+btn('远程文件','quiet','data-lib-toast="选择远程文件来源（样例）"','cloud')+'</div><div class="lib-picked-files"><div class="lib-mini-row">阅读笔记.pdf <span>2.4 MB</span></div></div>'+states(('default','loading','empty','error','disabled'))
    d['b03']='<div class="lib-file-row">'+ic('file-text')+'<div><b>阅读笔记.pdf</b><small>PDF · 2.4 MB · 已保存</small></div>'+btn('打开','quiet','data-lib-toast="打开PDF预览"')+btn('','icon','aria-label=文件更多操作 data-lib-toast="重命名、移动、删除进入更多菜单"','ellipsis')+'</div><div class="lib-file-row muted">'+ic('file-text')+'<div><b>损坏的文件.pdf</b><small class="lib-danger">无法读取，请重新选择</small></div>'+btn('重试','quiet','data-lib-toast="重新读取示例文件"')+'</div>'
    d['b04']='<div class="lib-media-player"><div><b>山间的清晨</b><small>示例音频 · 12:40</small></div><input class="lib-range" type="range" min="0" max="760" value="228" aria-label="播放进度"><div class="lib-row spread"><span data-media-time>03:48 / 12:40</span><div class="lib-row">'+btn('1×','quiet','data-media-speed aria-label=播放速度')+btn('播放','primary compact','data-media-toggle','play')+'</div></div></div>'+states(('default','loading','error','disabled'))
    d['b05']='<div class="lib-book-row"><button type="button" class="lib-book-cover" aria-label="查看山海之间详情" data-lib-toast="封面进入图书详情"><img src="assets/book-cover.png" alt="默认图书封面"></button><button type="button" class="lib-book-copy" data-lib-toast="文字区域继续阅读"><b>山海之间</b><small>示例作者 · 第28章</small><span class="lib-tag">已缓存到本地</span><span class="lib-progress-tiny"><i></i></span></button>'+btn('','icon','aria-label=书籍更多操作 data-lib-toast="管理缓存、查看目录、移出书架"','ellipsis')+'</div>'+note('封面和文字区分别命中，缓存状态不做伪按钮。')+states(('default','loading','error'))
    d['b06']='<div class="lib-reader-settings"><div class="lib-row spread"><b>阅读字号</b><div class="lib-stepper" data-stepper data-min="12" data-max="32">'+btn('−','icon','data-step=-1 aria-label=减小阅读字号')+'<output>18</output>'+btn('+','icon','data-step=1 aria-label=增大阅读字号')+'</div></div><label class="lib-field-label">行距<input class="lib-range" type="range" min="120" max="240" value="180" data-reader-line></label><div class="lib-reader-preview">清晨的光落在书页上，阅读从此刻开始。</div></div>'+note('样例调整即时预览；正式阅读保留读者主动选择的字体。')
    d['b07']='<form class="lib-chat-form"><label for="lib-chat" class="lib-field-label">给DSH发送消息</label><textarea class="lib-input" id="lib-chat" rows="2" placeholder="输入消息…"></textarea><div class="lib-row spread">'+btn('','icon','aria-label=添加附件 data-lib-toast="附件只进入本地草稿"','plus')+btn('发送','primary compact','data-chat-send','send')+'</div><div class="lib-chat-output" aria-live="polite">消息草稿仅保存在此样例。</div></form>'+states(('default','loading','error','disabled'))
    d['b08']='<div class="lib-task-row"><div class="lib-row spread"><b>图片生成任务</b><span class="lib-tag warning" data-task-tag>排队中</span></div><p>当前前方还有2个任务</p>'+progress(0,'等待处理')+'<div class="lib-row wrap">'+btn('开始演示','quiet','data-task-run')+btn('取消任务','quiet','data-task-cancel')+btn('演示失败','quiet','data-task-fail')+'</div><p class="lib-task-status" aria-live="polite">保留任务参数，失败后可重试。</p></div>'
    d['b09']='<div class="lib-file-preview"><div class="lib-preview-toolbar"><b>阅读笔记.pdf</b>'+btn('适应宽度','quiet','data-preview-fit')+'</div><div class="lib-document-page"><h4>阅读笔记</h4><p>阅读，让日常更清晰。</p><p>记录一个想法，留下一段感受。</p><small>第1页 / 共8页 · 文档样张</small></div><div class="lib-row wrap">'+btn('加载失败','quiet','data-preview-error')+btn('不支持格式','quiet','data-preview-unsupported')+btn('恢复预览','quiet','data-preview-reset')+'</div></div>'
    return d


def build_library():
    catalog=json.loads((ROOT/'component-catalog.json').read_text())
    samples=demos()
    components=catalog['components']
    ids={c['id'] for c in components}
    assert len(ids)==72, f'Expected 72 components, received {len(ids)}'
    assert ids==set(samples), f'Missing demos: {ids-set(samples)}; extra: {set(samples)-ids}'
    out='<div class="intro"><div><div class="kicker">COMPONENT LIBRARY / 1.2</div><h1>每种控件，都有样例与规则。</h1><p>72个组件，覆盖10个场景族。选择、日期、搜索可直接操作，展开卡片可查看尺寸、状态、交互与使用边界。</p></div><a href="COMPONENT-SPEC.md">完整组件规范</a></div>'
    out+='<div class="lib-toolbar"><label class="lib-catalog-search">'+ic('search')+'<input type="search" id="lib-catalog-search" aria-label="搜索组件或场景" placeholder="搜索组件 / 场景，如：下拉、日期、上传"></label><div class="lib-row"><span id="lib-count" role="status">72 / 72 组件</span>'+btn('深色样例','secondary compact','id=lib-theme-toggle aria-pressed=false','moon')+'</div></div>'
    out+='<nav class="lib-category-nav" aria-label="组件分类"><button type="button" data-lib-group="all" class="active" aria-pressed="true">全部 <small>72</small></button>'
    for group in catalog['groups']:
        n=sum(c['group']==group['id'] for c in components)
        out+=f'<button type="button" data-lib-group="{group["id"]}" aria-pressed="false">{escape(group["title"])} <small>{n}</small></button>'
    out+='</nav><div class="lib-guide"><span>建议先看：</span><button type="button" data-lib-jump="selection">下拉与选择</button><button type="button" data-lib-jump="datetime">日期与时间</button><button type="button" data-lib-jump="inputs">输入与搜索</button><a href="CONTROL-COVERAGE.md">查看实际页面覆盖</a></div><div class="lib-grid">'
    for c in components:
        searchable=' '.join([c['id'],c['name'],c['title'],c['description']]+c['scenes'])
        out+=f'<article class="lib-component" id="lib-{c["id"]}" data-lib-category="{c["group"]}" data-lib-search="{escape(searchable,quote=True)}"><header class="lib-component-heading"><span>{c["id"].upper()}</span><div><h3>{escape(c["title"])}</h3><small>{escape(c["name"])}</small></div></header><p class="lib-component-desc">{escape(c["description"])}</p><div class="lib-sample">{samples[c["id"]]}</div><div class="lib-scenes">'+''.join(f'<span>{escape(v)}</span>' for v in c['scenes'])+'</div>'
        out+='<details class="lib-spec"><summary>展开规范与状态要求</summary><dl>'
        for title,key in [('尺寸与字体','sizing'),('变体','variants'),('状态覆盖','states'),('交互行为','behavior'),('键盘与读屏','accessibility'),('使用边界','avoid'),('建议接口','api')]:
            value=c[key]
            if isinstance(value,list):value=' · '.join(value)
            out+=f'<div><dt>{title}</dt><dd>{escape(value)}</dd></div>'
        out+='</dl></details></article>'
    out+='</div><div class="lib-no-components" hidden><h3>没有匹配的组件</h3><p>换个关键词，或清除分类筛选。</p>'+btn('清除筛选','secondary','data-lib-reset')+'</div>'
    out+='<p class="lib-footnote">本地交互样例仅用于审核组件，不连接业务服务。日期等规范补齐项不代表当前客户端已具备对应功能；正式实现需验证原生控件、读屏与真实业务状态。</p>'
    out+='<dialog class="lib-modal" id="lib-global-dialog" aria-labelledby="lib-dialog-title"><div class="lib-modal-head"><h3 id="lib-dialog-title">清除离线缓存？</h3>'+btn('','icon','data-dialog-close aria-label=关闭对话框','x')+'</div><div id="lib-dialog-body"></div><div class="lib-modal-actions action-group">'+btn('取消','secondary','data-dialog-close')+btn('确认示例','primary','data-dialog-confirm')+'</div></dialog><div class="lib-toast" role="status" hidden></div>'
    return out


if __name__=='__main__':
    print('Demo count:',len(demos()))
    if (ROOT/'component-catalog.json').exists():
        print('Library HTML bytes:',len(build_library().encode()))
