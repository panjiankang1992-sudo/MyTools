/* 组件库仅运行本地样例交互，不调用业务服务。 */
(() => {
  'use strict';
  const root = document.getElementById('library');
  if (!root) return;
  const $ = (s, base=root) => base.querySelector(s);
  const $$ = (s, base=root) => [...base.querySelectorAll(s)];
  const timers = new WeakMap();
  let toastTimer;
  const showToast = text => {
    const el = $('.lib-toast');
    el.textContent = text;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.hidden=true; }, 2600);
  };
  const setStatus = (sample, selector, text, error=false) => {
    const out=$(selector,sample);
    if (!out) return;
    out.textContent=text;
    out.classList.toggle('lib-danger',error);
  };
  const original = new WeakMap();
  $$('.lib-sample input,.lib-sample textarea,.lib-sample select,.lib-sample button').forEach(el => {
    original.set(el,{disabled:el.disabled,readonly:el.readOnly||false,value:el.value});
  });
  const errorMessages = {
    i01:'请输入文件名称，不能只填写空格。',i02:'描述超过字数限制，请保留核心内容。',i03:'密码不正确，输入已保留。',i04:'请输入1–100之间的整数。',i08:'订阅地址格式不正确，请检查后保存。',i09:'验证码已过期，请重新获取。',
    s01:'书源加载失败，已保留之前选择。',s02:'模型列表读取失败，可以重新尝试。',s03:'至少保留一个可用标签。',s06:'至少选择一项导出内容。',s07:'设置保存失败，已恢复上次成功状态。',
    d01:'日期超出可选范围，请选择2026-11-02或之后。',d04:'该时间段不可用，请选择其他时间。',d05:'日期时间必须晚于当前时间，请检查时区。',f08:'处理未完成：输入文件无法读取，请重新选择。',b02:'文件来源不可访问，请检查连接后重试。',b04:'音频读取失败，播放位置已保留。',b05:'缓存暂时失败，阅读进度已保留，可重新下载。',b07:'发送失败，草稿已保留。'
  };
  const stateMessage=(id,state)=> {
    if (state==='error') return errorMessages[id]||'操作暂未完成，当前输入已保留，可重新尝试。';
    if (state==='disabled') return '当前条件尚未满足，控件不可操作；状态切换仍可用。';
    if (state==='readonly') return '只读：可以查看与复制内容；非文本操作禁止修改。';
    if (state==='loading') return id==='s04'?'正在读取下级目录，保留当前来源。':'正在加载，请稍候；重复操作已暂时停用。';
    if (state==='empty') return id==='b02'?'当前来源没有可选择的文件。':'没有匹配的结果，可以更换关键词或条件。';
    return '';
  };
  // 切换样例状态时只影响演示区业务控件，不影响状态工具本身。
  $$('[data-lib-state]').forEach(button => button.addEventListener('click',()=>{
    const sample=button.closest('.lib-sample');
    const id=button.closest('.lib-component').id.replace('lib-','');
    const state=button.dataset.libState;
    sample.dataset.sampleState=state;
    $$('[data-lib-state]',sample).forEach(b=>b.setAttribute('aria-pressed',String(b===button)));
    $$('input,textarea,select,button',sample).filter(el=>!el.closest('.lib-state-tools')).forEach(el=>{
      const prev=original.get(el)||{disabled:false,readonly:false};
      el.disabled=state==='disabled'||state==='loading'||(state==='readonly'&&(!('readOnly' in el)||['checkbox','radio','range','color'].includes(el.type)))||prev.disabled;
      if ('readOnly' in el) el.readOnly=state==='readonly'||prev.readonly;
      el.setAttribute('aria-invalid',String(state==='error'&&el.matches('input,textarea,select,[role=combobox]')));
      if (state==='readonly'&&el.matches('input:not([type=checkbox]):not([type=radio]):not([type=range]),textarea')&&!el.value) el.value='阅读笔记（只读示例）';
    });
    const message=$('.lib-state-message',sample);
    if (message) {
      message.textContent=stateMessage(id,state);
      message.hidden=state==='default';
      message.classList.toggle('info',state!=='error');
    }
    $$('.lib-select',sample).forEach(select=>{
      const panel=$('.lib-select-panel',select), list=$('[role=listbox]',select);
      const empty=$('.lib-select-empty',select), trigger=$('.lib-select-trigger',select);
      const feedback=['loading','empty','error'].includes(state);
      panel.hidden=!feedback; trigger.setAttribute('aria-expanded',String(feedback));
      list.hidden=feedback; empty.hidden=!feedback;
      empty.textContent=stateMessage(id,state);
    });
    if (id==='i05') {
      const result=$('.lib-search-results',sample);
      result.textContent=state==='loading'?'正在搜索，保留当前关键词…':state==='empty'?'没有找到图书，请试试其他书名或作者。':'输入关键词后点击搜索，或按Enter提交。';
    }
    if (id==='b05') {
      const tag=$('.lib-tag',sample);
      tag.textContent=state==='loading'?'正在缓存…':state==='error'?'缓存失败 · 可重试':'已缓存到本地';
      tag.classList.toggle('error',state==='error');
    }
  }));
  $$('[data-lib-toast]').forEach(b=>b.addEventListener('click',()=>showToast(b.dataset.libToast)));
  // 分类与关键词共同过滤，始终给出可见组件数与空结果。
  let selectedGroup='all';
  const filterCatalog=()=>{
    const keyword=$('#lib-catalog-search').value.trim().toLocaleLowerCase();
    const cards=$$('.lib-component');let count=0;
    cards.forEach(card=>{const visible=(selectedGroup==='all'||card.dataset.libCategory===selectedGroup)&&card.dataset.libSearch.toLocaleLowerCase().includes(keyword);card.hidden=!visible;if(visible)count++;});
    $('#lib-count').textContent=`${count} / ${cards.length} 组件`;
    $('.lib-no-components').hidden=count>0;
  };
  const changeGroup=group=>{selectedGroup=group;$$('[data-lib-group]').forEach(b=>{const active=b.dataset.libGroup===group;b.classList.toggle('active',active);b.setAttribute('aria-pressed',String(active));});filterCatalog();};
  $$('[data-lib-group]').forEach(b=>b.addEventListener('click',()=>changeGroup(b.dataset.libGroup)));
  $$('[data-lib-jump]').forEach(b=>b.addEventListener('click',()=>{$('#lib-catalog-search').value='';changeGroup(b.dataset.libJump);}));
  $('#lib-catalog-search').addEventListener('input',filterCatalog);
  $('[data-lib-reset]').addEventListener('click',()=>{$('#lib-catalog-search').value='';changeGroup('all');});
  $('#lib-theme-toggle').addEventListener('click',event=>{
    const enabled=$('.lib-grid').classList.toggle('theme-dark');
    event.currentTarget.setAttribute('aria-pressed',String(enabled));
    event.currentTarget.querySelector('span').textContent=enabled?'浅色样例':'深色样例';
    $('#lib-global-dialog').classList.toggle('theme-dark',enabled);
  });
  $$('[data-count]').forEach(input=>{const output=$('[data-count-output]',input.closest('.lib-sample'));const update=()=>output.textContent=`${input.value.length} / ${input.maxLength}`;input.addEventListener('input',update);update();});
  $$('[data-password-toggle]').forEach(b=>b.addEventListener('click',()=>{const input=$('input',b.parentElement);const visible=input.type==='password';input.type=visible?'text':'password';b.textContent=visible?'隐藏':'显示';b.setAttribute('aria-label',visible?'隐藏密码':'显示密码');}));
  $$('[data-validation-form]').forEach(form=>{
    $('[data-validate-form]',form).addEventListener('click',()=>{
      const name=$('input[type=text]',form),url=$('input[type=url]',form),out=$('.lib-validation-message',form);
      const nameValid=!!name.value.trim(), urlValid=/^https?:\/\/[^\s.]+\.[^\s]+$/i.test(url.value.trim());
      name.classList.toggle('invalid',!nameValid);url.classList.toggle('invalid',!urlValid);
      name.setAttribute('aria-invalid',String(!nameValid));url.setAttribute('aria-invalid',String(!urlValid));
      out.classList.toggle('error',!nameValid||!urlValid);
      out.textContent=!nameValid?'请填写订阅名称。':!urlValid?'请填写完整的http或https订阅地址。':'订阅信息校验通过（本地样例，未提交）。';
    });
    form.addEventListener('submit',e=>{e.preventDefault();$('[data-validate-form]',form).click();});
  });
  $$('[data-verification-code]').forEach(b=>b.addEventListener('click',()=>{
    let seconds=60;b.disabled=true;b.textContent=`${seconds}秒后重发`;
    const timer=setInterval(()=>{seconds--;b.textContent=`${seconds}秒后重发`;if(!seconds){clearInterval(timer);b.disabled=false;b.textContent='重新获取';}},1000);
    showToast('已开始本地倒计时，没有发送验证码');
  }));
  const books=[{title:'山海之间',author:'示例作者'},{title:'城市里的风',author:'示例作者'},{title:'漫长的旅程',author:'示例作者'}];
  const runSearch=form=>{
    const input=$('input',form),output=$('.lib-search-results',form),query=input.value.trim();
    if(!query){output.textContent='请输入书名或作者后搜索。';return;}
    const matches=books.filter(b=>`${b.title}${b.author}`.includes(query));
    output.replaceChildren();
    if(!matches.length){output.textContent=`没有找到“${query}”，请试试其他关键词。`;return;}
    const summary=document.createElement('p');summary.className='lib-note';summary.textContent=`找到${matches.length}本图书`;output.append(summary);
    matches.forEach(book=>{const row=document.createElement('div');row.className='lib-result-row';row.textContent=`${book.title} · ${book.author}`;output.append(row);});
  };
  $$('[data-search-demo]').forEach(form=>{
    form.addEventListener('submit',e=>{e.preventDefault();runSearch(form);});
    $('[data-search-submit]',form).addEventListener('click',()=>runSearch(form));
    $('[data-search-clear]',form).addEventListener('click',()=>{$('input',form).value='';$('.lib-search-results',form).textContent='关键词已清空，可重新搜索。';$('input',form).focus();});
    $$('[data-search-word]',form).forEach(b=>b.addEventListener('click',()=>{$('input',form).value=b.dataset.searchWord;runSearch(form);}));
  });
  const closeSelect=select=>{ if(select._libDiscard)select._libDiscard();$('.lib-select-panel',select).hidden=true;$('.lib-select-trigger',select).setAttribute('aria-expanded','false'); };
  const openSelect=select=>{
    $$('.lib-select').filter(other=>other!==select).forEach(closeSelect);
    $('.lib-select-panel',select).hidden=false;$('.lib-select-trigger',select).setAttribute('aria-expanded','true');
  };
  $$('.lib-select').forEach(select=>{
    const trigger=$('.lib-select-trigger',select),panel=$('.lib-select-panel',select),items=$$('[role=option]',select);
    const filter=$('input',panel),multiple=select.dataset.multiple==='true';let selected=multiple?[]:[$('.lib-selected',select).textContent],committed=[...selected];
    trigger.addEventListener('click',()=>panel.hidden?openSelect(select):closeSelect(select));
    trigger.addEventListener('keydown',e=>{if(e.key==='ArrowDown'){e.preventDefault();openSelect(select);(filter||items.find(o=>!o.disabled)).focus();}});
    const update=()=>{
      items.forEach(option=>{
        const chosen=selected.includes(option.dataset.option);option.setAttribute('aria-selected',String(chosen));
        let icon=$('img',option);if(chosen&&!icon){icon=document.createElement('img');icon.className='ic';icon.alt='';icon.src='assets/check.svg';option.append(icon);}else if(!chosen&&icon)icon.remove();
      });
      const shown=multiple?committed:selected;$('.lib-selected',select).textContent=shown.length?shown.join('、'):'请选择标签';
      const summary=$('.lib-selection-summary',select.closest('.lib-sample'));
      if(summary)summary.textContent=panel.hidden?(committed.length?`已提交${committed.length}项：${committed.join('、')}`:'尚未选择标签'):`待确认${selected.length}项；原已提交${committed.length}项`; 
    };
    if(multiple)select._libDiscard=()=>{selected=[...committed];panel.hidden=true;update();};
    items.forEach(option=>option.addEventListener('click',()=>{
      if(multiple){selected=selected.includes(option.dataset.option)?selected.filter(v=>v!==option.dataset.option):[...selected,option.dataset.option];update();return;}
      const cascade=select.closest('.lib-cascade');
      if(cascade&&select===cascade.querySelector('.lib-select')&&selected[0]!==option.dataset.option){
        const next=option.dataset.option;closeSelect(select);
        requestConfirmation(trigger,'切换来源？','切换后将清除当前已选目录，需要重新选择下级目录。取消会保留原来源和路径。','切换来源',()=>{
          selected=[next];update();const child=$$('.lib-select',cascade)[1];$('.lib-selected',child).textContent='请选择目录';$$('[role=option]',child).forEach(o=>o.setAttribute('aria-selected','false'));
        });return;
      }
      selected=[option.dataset.option];update();closeSelect(select);trigger.focus();if(select.closest('#lib-d05'))updateDateTime();
    }));
    panel.addEventListener('keydown',e=>{
      if(e.key==='Escape'){e.stopPropagation();closeSelect(select);trigger.focus();}
      if(['ArrowDown','ArrowUp','Home','End'].includes(e.key)){
        const available=items.filter(o=>!o.disabled&&!o.hidden);if(!available.length)return;e.preventDefault();let index=available.indexOf(document.activeElement);
        index=e.key==='Home'?0:e.key==='End'?available.length-1:(index+(e.key==='ArrowDown'?1:-1)+available.length)%available.length;available[index].focus();
      }
    });
    if(filter)filter.addEventListener('input',()=>{let visible=0;items.forEach(o=>{o.hidden=!o.dataset.option.includes(filter.value.trim());if(!o.hidden)visible++;});$('.lib-select-empty',panel).hidden=visible>0;$('.lib-select-empty',panel).textContent='没有匹配的选项';});
    const done=$('[data-select-done]',select);if(done)done.addEventListener('click',()=>{committed=[...selected];closeSelect(select);trigger.focus();});const cancel=$('[data-select-cancel]',select);if(cancel)cancel.addEventListener('click',()=>{closeSelect(select);trigger.focus();});
  });
  $$('[data-check-all]').forEach(all=>{const sample=all.closest('.lib-sample'),items=$$('fieldset input[type=checkbox]',sample);const summary=document.createElement('p');summary.className='lib-note';summary.setAttribute('aria-live','polite');all.closest('label').after(summary);const sync=()=>{const count=items.filter(i=>i.checked).length;all.checked=count===items.length;all.indeterminate=count>0&&count<items.length;all.setAttribute('aria-checked',all.indeterminate?'mixed':String(all.checked));summary.textContent=`已选择${count} / ${items.length}项`;};all.addEventListener('change',()=>{items.forEach(i=>i.checked=all.checked);sync();});items.forEach(i=>i.addEventListener('change',sync));sync();});
  $$('.lib-switch').forEach(b=>b.addEventListener('click',()=>{b.setAttribute('aria-checked',String(b.getAttribute('aria-checked')!=='true'));}));
  $$('[data-multi-chip] button').forEach(b=>b.addEventListener('click',()=>{
    const parent=b.parentElement;if(b.textContent==='全部'){$$('button',parent).forEach(x=>x.setAttribute('aria-pressed',String(x===b)));}
    else{b.setAttribute('aria-pressed',String(b.getAttribute('aria-pressed')!=='true'));$('button',parent).setAttribute('aria-pressed','false');}
  }));
  const pad=n=>String(n).padStart(2,'0');
  const renderCalendar=cal=>{
    const year=+cal.dataset.year,month=+cal.dataset.month,days=$('.lib-days',cal),first=(new Date(year,month-1,1).getDay()+6)%7,total=new Date(year,month,0).getDate();
    $('.lib-month-label',cal).textContent=`${year}年${month}月`;days.replaceChildren();
    for(let i=0;i<first;i++){const blank=document.createElement('span');blank.className='lib-day-blank';days.append(blank);}
    for(let day=1;day<=total;day++){
      const button=document.createElement('button'),date=`${year}-${pad(month)}-${pad(day)}`;button.type='button';button.className='lib-day';button.dataset.date=date;button.textContent=day;button.disabled=date<'2026-11-02';button.setAttribute('aria-label',`${year}年${month}月${day}日`);days.append(button);
    }
    markCalendar(cal);
  };
  const markCalendar=cal=>{
    $$('[data-date]',cal).forEach(b=>{
      const selected=b.dataset.date===cal.dataset.selected||b.dataset.date===cal.dataset.rangeStart||b.dataset.date===cal.dataset.rangeEnd;
      b.classList.toggle('selected',selected);b.classList.toggle('today',b.dataset.date==='2026-11-02');b.setAttribute('aria-pressed',String(selected));b.tabIndex=b.dataset.date===(cal.dataset.rangeEnd||cal.dataset.rangeStart||cal.dataset.selected)?0:-1;if(b.dataset.date==='2026-11-02')b.setAttribute('aria-current','date');else b.removeAttribute('aria-current');
      b.classList.toggle('in-range',!!cal.dataset.rangeEnd&&b.dataset.date>cal.dataset.rangeStart&&b.dataset.date<cal.dataset.rangeEnd);
    });const active=$('[data-date][tabindex="0"]',cal);if(!active){const first=$('[data-date]:not(:disabled)',cal);if(first)first.tabIndex=0;}
  };
  $$('.lib-calendar').forEach(cal=>{
    cal.dataset.selected='2026-11-12';cal.dataset.committed='2026-11-12';if(cal.dataset.range==='true'){cal.dataset.rangeStart='2026-11-12';delete cal.dataset.selected;}
    markCalendar(cal);
    cal.addEventListener('click',event=>{
      const step=event.target.closest('[data-month-step]');
      if(step){let month=+cal.dataset.month+(+step.dataset.monthStep),year=+cal.dataset.year;if(month<1){month=12;year--;}if(month>12){month=1;year++;}cal.dataset.month=month;cal.dataset.year=year;renderCalendar(cal);return;}
      const day=event.target.closest('[data-date]');if(!day||day.disabled)return;
      const sample=cal.closest('.lib-sample'),date=day.dataset.date;
      if(cal.dataset.range==='true'){
        if(!cal.dataset.rangeStart||cal.dataset.rangeEnd||date<cal.dataset.rangeStart){cal.dataset.rangeStart=date;delete cal.dataset.rangeEnd;}else cal.dataset.rangeEnd=date;
        $('[data-range-start]',sample).textContent=cal.dataset.rangeStart;
        $('[data-range-end]',sample).textContent=cal.dataset.rangeEnd||'待选择';
        const output=$('.lib-range-status',sample);output.classList.remove('error');output.textContent=cal.dataset.rangeEnd?'日期范围有效，可以继续。':'请选择结束日期。';
      } else {
        cal.dataset.selected=date;
        const output=$('.lib-date-selection',sample);if(output)output.textContent=`已提交：${cal.dataset.committed}；草稿：${date}`;
        const trigger=$('[data-date-open]',sample);if(trigger)trigger.dataset.draft=date;
      }
      markCalendar(cal);
    });
    // 日期格支持方向键移动焦点，Enter/空格由原生按钮完成选择。
    cal.addEventListener('keydown',event=>{
      const day=event.target.closest('[data-date]');if(!day)return;
      const diff={ArrowLeft:-1,ArrowRight:1,ArrowUp:-7,ArrowDown:7}[event.key];if(!diff)return;event.preventDefault();
      const choices=$$('[data-date]',cal),index=choices.indexOf(day),next=choices[index+diff];if(next&&!next.disabled){choices.forEach(b=>b.tabIndex=-1);next.tabIndex=0;next.focus();}
    });
  });
  $$('[data-date-open]').forEach(trigger=>{
    const sample=trigger.closest('.lib-sample'),calendar=$('.lib-calendar-scroll',sample),actions=$('.lib-date-actions',sample),cal=$('.lib-calendar',sample);
    const close=()=>{calendar.hidden=true;actions.hidden=true;trigger.setAttribute('aria-expanded','false');trigger.focus();};
    trigger.addEventListener('click',()=>{const opening=calendar.hidden;calendar.hidden=!opening;actions.hidden=!opening;trigger.setAttribute('aria-expanded',String(opening));if(opening){trigger.dataset.draft=$('[data-date-value]',trigger).textContent;cal.dataset.selected=trigger.dataset.draft;markCalendar(cal);const active=$('[data-date="'+cal.dataset.selected+'"]',cal)||$('[data-date]:not(:disabled)',cal);if(active)active.focus();}});
    $('[data-date-cancel]',sample).addEventListener('click',()=>{cal.dataset.selected=$('[data-date-value]',trigger).textContent;markCalendar(cal);close();});
    $('[data-date-confirm]',sample).addEventListener('click',()=>{$('[data-date-value]',trigger).textContent=trigger.dataset.draft||cal.dataset.selected;const combined=$('[data-combined-date]',sample);if(combined){combined.value=$('[data-date-value]',trigger).textContent;updateDateTime();}close();});
    sample.addEventListener('keydown',event=>{if(event.key==='Escape'&&!calendar.hidden){event.stopPropagation();$('[data-date-cancel]',sample).click();}});
  });
  $$('[data-calendar-cancel],[data-calendar-confirm]').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample'),cal=$('.lib-calendar',sample);if(b.hasAttribute('data-calendar-confirm'))cal.dataset.committed=cal.dataset.selected;else cal.dataset.selected=cal.dataset.committed;markCalendar(cal);$('.lib-date-selection',sample).textContent=`已提交：${cal.dataset.committed}；草稿：${cal.dataset.selected}`;}));
  $$('[data-range-clear]').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample'),cal=$('.lib-calendar',sample);delete cal.dataset.rangeStart;delete cal.dataset.rangeEnd;$('[data-range-start]',sample).textContent='待选择';$('[data-range-end]',sample).textContent='待选择';markCalendar(cal);$('.lib-range-status',sample).textContent='已清空，先选择开始日期。';$('.lib-range-status',sample).classList.remove('error');}));
  $$('[data-range-error]').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample');$('[data-range-start]',sample).textContent='2026-11-20';$('[data-range-end]',sample).textContent='2026-11-12';$('.lib-range-status',sample).textContent='结束日期不能早于开始日期，请重新选择。';$('.lib-range-status',sample).classList.add('error');}));
  $$('[data-time-picker]').forEach(picker=>{picker.dataset.committed=picker.dataset.initialTime||'09:00';
    $$('[role=option]',picker).forEach(b=>b.addEventListener('click',()=>{$$('[role=option]',b.parentElement).forEach(x=>x.setAttribute('aria-selected',String(x===b)));const hour=$('[data-time-hour][aria-selected=true]',picker).dataset.timeHour,minute=$('[data-time-minute][aria-selected=true]',picker).dataset.timeMinute;$('.lib-time-output',picker.closest('.lib-sample')).textContent=`已提交${picker.dataset.committed}；草稿${hour}:${minute}`;}));
    picker.addEventListener('keydown',e=>{if(!['ArrowDown','ArrowUp','Home','End'].includes(e.key))return;const button=e.target.closest('[role=option]');if(!button)return;e.preventDefault();const items=$$('[role=option]',button.parentElement),i=items.indexOf(button),next=e.key==='Home'?0:e.key==='End'?items.length-1:Math.max(0,Math.min(items.length-1,i+(e.key==='ArrowDown'?1:-1)));items[next].focus();items[next].click();});
  });
  $$('[data-time-cancel],[data-time-confirm]').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample'),picker=$('[data-time-picker]',sample);if(b.hasAttribute('data-time-confirm')){picker.dataset.committed=$('[data-time-hour][aria-selected=true]',picker).dataset.timeHour+':'+$('[data-time-minute][aria-selected=true]',picker).dataset.timeMinute;}else{const [hour,minute]=picker.dataset.committed.split(':');$$('[data-time-hour]',picker).forEach(x=>x.setAttribute('aria-selected',String(x.dataset.timeHour===hour)));$$('[data-time-minute]',picker).forEach(x=>x.setAttribute('aria-selected',String(x.dataset.timeMinute===minute)));}if(sample.closest('#lib-d05')){$('#lib-datetime-time').value=picker.dataset.committed;updateDateTime();}$('.lib-time-output',sample).textContent=`已提交${picker.dataset.committed}；草稿${$('[data-time-hour][aria-selected=true]',picker).dataset.timeHour}:${$('[data-time-minute][aria-selected=true]',picker).dataset.timeMinute}`;}));
  const positionTimes=()=>{$$('[data-time-picker] [role=listbox]').forEach(list=>{if(list.offsetHeight&&!list.dataset.positioned){const chosen=$('[aria-selected=true]',list);list.scrollTop=chosen.offsetTop-list.offsetTop-44;list.dataset.positioned='true';}});};
  new MutationObserver(positionTimes).observe(root,{attributes:true,attributeFilter:['hidden']});
  positionTimes();
  const updateDateTime=()=>{const zone=$('#lib-d05 .lib-selected').textContent,offset=zone.includes('UTC+09:00')?'UTC+09:00':zone.includes('UTC+00:00')?'UTC+00:00':'UTC+08:00';$('.lib-datetime-output').textContent=`${$('#lib-datetime-date').value||'待选择日期'} ${$('#lib-datetime-time').value||'待选择时间'} · ${offset}`;};
  ['#lib-datetime-date','#lib-datetime-time'].forEach(s=>$(s).addEventListener('input',updateDateTime));
  $$('[data-quick-date] button').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample');$$('button',b.parentElement).forEach(x=>x.setAttribute('aria-pressed',String(x===b)));const end=new Date(2026,10,2),start=new Date(2026,10,2);start.setDate(start.getDate()-Math.max(0,(+b.dataset.days)-1));const fmt=d=>`${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}`;$('.lib-quick-range',sample).textContent=`${fmt(start)} 至 ${fmt(end)} · Asia/Shanghai`;}));
  $$('[data-slider-unit]').forEach(input=>input.addEventListener('input',()=>{$('output',input.closest('.lib-sample')).textContent=input.value+input.dataset.sliderUnit;}));
  const syncDual=sample=>{const low=$('[data-range-low]',sample),high=$('[data-range-high]',sample);if(+low.value>+high.value)high.value=low.value;$('[data-range-number-output]',sample).textContent=`${low.value}–${high.value} 页`;};
  $$('[data-range-low],[data-range-high]').forEach(input=>input.addEventListener('input',()=>syncDual(input.closest('.lib-sample'))));
  $$('[data-stepper]').forEach(stepper=>{
    const min=+stepper.dataset.min,max=+stepper.dataset.max,output=$('output',stepper);
    const update=()=>{$$('[data-step]',stepper).forEach(b=>b.disabled=(+b.dataset.step<0&&+output.value<=min)||(+b.dataset.step>0&&+output.value>=max));const preview=$('.lib-reader-preview',stepper.closest('.lib-sample'));if(preview)preview.style.fontSize=`${output.value}px`;};
    $$('[data-step]',stepper).forEach(b=>b.addEventListener('click',()=>{output.value=Math.max(min,Math.min(max,+output.value+(+b.dataset.step)));output.textContent=output.value;update();}));update();
  });
  $$('[data-rating]').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample');$$('[data-rating]',sample).forEach(x=>x.setAttribute('aria-checked',String(x===b)));$('.lib-rating-output',sample).textContent=`已选择${b.dataset.rating}分`;}));
  const applyColor=(sample,color)=>{const preview=$('.lib-color-preview',sample);preview.style.background=color;const channels=[1,3,5].map(i=>parseInt(color.slice(i,i+2),16)/255).map(v=>v<=0.04045?v/12.92:Math.pow((v+0.055)/1.055,2.4));const luminance=channels[0]*0.2126+channels[1]*0.7152+channels[2]*0.0722;preview.style.color=luminance>0.179?'#000000':'#FFFFFF';};
  $$('[data-color]').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample');$$('[data-color]',sample).forEach(x=>x.setAttribute('aria-pressed',String(x===b)));applyColor(sample,b.dataset.color);}));
  $$('.lib-color-custom input').forEach(input=>input.addEventListener('input',()=>applyColor(input.closest('.lib-sample'),input.value)));
  $$('.lib-tabset [role=tab]').forEach(b=>b.addEventListener('click',()=>{const tabs=b.closest('.lib-tabset');$$('[role=tab]',tabs).forEach(x=>x.setAttribute('aria-selected',String(x===b)));$('.lib-tabpanel',tabs).textContent=['填写画面描述并开始创作','暂无作品；完成生成后在这里查看','示例任务：已完成1项，排队中1项'][+b.dataset.tab];}));
  $$('.lib-segment button').forEach(b=>b.addEventListener('click',()=>{$$('button',b.parentElement).forEach(x=>x.setAttribute('aria-pressed',String(x===b)));}));
  $$('.lib-bottom-nav button').forEach(b=>b.addEventListener('click',()=>{$$('button',b.parentElement).forEach(x=>x.setAttribute('aria-current',String(x===b)));}));
  $$('[data-page-step]').forEach(b=>b.addEventListener('click',()=>{const pagination=b.closest('.lib-pagination'),out=$('[data-page-number]',pagination),page=Math.max(1,Math.min(8,+out.textContent+(+b.dataset.pageStep)));out.textContent=page;$$('[data-page-step]',pagination).forEach(x=>x.disabled=+x.dataset.pageStep<0?page===1:page===8);}));
  $$('[data-next-step]').forEach(b=>b.addEventListener('click',()=>{const steps=$$('.lib-steps li',b.closest('.lib-sample')),index=steps.findIndex(s=>s.classList.contains('active')),next=(index+1)%steps.length;steps.forEach((s,i)=>{s.classList.toggle('active',i===next);s.classList.toggle('done',i<next);s.setAttribute('aria-current',i===next?'step':'false');});}));
  $$('[data-menu-trigger],[data-popover-trigger]').forEach(b=>b.addEventListener('click',()=>{const panel=$('.lib-menu,.lib-popover',b.parentElement);panel.hidden=!panel.hidden;b.setAttribute('aria-expanded',String(!panel.hidden));if(!panel.hidden){const first=$('button,input',panel);if(first)first.focus();}}));
  $$('[data-tooltip-toggle]').forEach(b=>b.addEventListener('click',()=>b.parentElement.classList.toggle('open')));
  const closeTransient=event=>{
    $$('.lib-select').forEach(select=>{if(event.key==='Escape'||!select.contains(event.target))closeSelect(select);});
    $$('.lib-menu-demo,.lib-popover-demo').forEach(wrap=>{if(event.key==='Escape'||!wrap.contains(event.target)){const panel=$('.lib-menu,.lib-popover',wrap),trigger=$('[aria-expanded]',wrap);if(!panel.hidden){panel.hidden=true;trigger.setAttribute('aria-expanded','false');if(event.key==='Escape')trigger.focus();}}});
    if(event.key==='Escape')$$('.lib-tooltip-wrap.open').forEach(w=>w.classList.remove('open'));
  };
  document.addEventListener('click',closeTransient);document.addEventListener('keydown',e=>{if(e.key==='Escape')closeTransient(e);});
  $$('[data-table-all]').forEach(all=>{const table=all.closest('table'),items=$$('tbody input',table),summary=document.createElement('p');summary.className='lib-note';summary.setAttribute('aria-live','polite');table.parentElement.after(summary);const sync=()=>{const count=items.filter(i=>i.checked).length;all.checked=count===items.length;all.indeterminate=count>0&&count<items.length;all.setAttribute('aria-checked',all.indeterminate?'mixed':String(all.checked));summary.textContent=`已选择${count} / ${items.length}个文件`;};all.addEventListener('change',()=>{items.forEach(i=>i.checked=all.checked);sync();});items.forEach(i=>i.addEventListener('change',sync));sync();});
  $$('[data-table-sort]').forEach(b=>b.addEventListener('click',()=>{const body=$('tbody',b.closest('table')),rows=$$('tr',body);rows.reverse().forEach(r=>body.append(r));showToast('已切换文件名排序');}));
  const simulateProgress=(sample,onComplete)=>{
    clearInterval(timers.get(sample));let value=0;const bar=$('progress',sample),label=$('[data-progress-label]',sample);
    const timer=setInterval(()=>{value=Math.min(100,value+16);bar.value=value;label.textContent=`${value}%`;if(value>=100){clearInterval(timer);if(onComplete)onComplete();}},250);timers.set(sample,timer);
  };
  $$('[data-progress-demo]').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample');setStatus(sample,'.lib-progress-status','正在缓存，可继续浏览。');simulateProgress(sample,()=>setStatus(sample,'.lib-progress-status','缓存已完成，可离线打开。'));}));
  $$('[data-progress-fail]').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample');clearInterval(timers.get(sample));setStatus(sample,'.lib-progress-status','缓存失败：连接中断，已保存进度，可重新尝试。',true);}));
  $$('[data-spinner-finish]').forEach(b=>b.addEventListener('click',()=>{const row=$('.lib-row',b.closest('.lib-sample'));row.replaceChildren();const img=document.createElement('img');img.className='ic';img.alt='';img.src='assets/check.svg';row.append(img,document.createTextNode('目录读取完成'));b.disabled=true;}));
  $$('[data-skeleton-finish]').forEach(b=>b.addEventListener('click',()=>{const skeleton=$('.lib-skeleton',b.closest('.lib-sample'));skeleton.className='lib-result-row';skeleton.textContent='山海之间 · 已缓存到本地';b.disabled=true;}));
  $$('[data-retry-demo]').forEach(b=>b.addEventListener('click',()=>{const empty=b.closest('.lib-empty');b.disabled=true;b.querySelector('span').textContent='正在重试…';setTimeout(()=>{$('h4',empty).textContent='目录已恢复';$('p',empty).textContent='找到12个项目，当前路径已保留。';b.disabled=false;b.querySelector('span').textContent='重新加载';},650);}));
  $$('[data-refresh-demo]').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample');b.disabled=true;setStatus(sample,'.lib-refresh-status','正在刷新，当前两项内容仍然保留。');setTimeout(()=>{b.disabled=false;setStatus(sample,'.lib-refresh-status','已更新 · 当前列表没有新项目。');},700);}));
  $$('[data-more-demo]').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample'),row=document.createElement('div');row.className='lib-mini-row';row.textContent='漫长的旅程 · 新加载';$('.lib-refresh-content',sample).append(row);setStatus(sample,'.lib-refresh-status','没有更多内容了。');b.disabled=true;}));
  const dialog=$('#lib-global-dialog');let dialogTrigger,pendingConfirmation=null;
  function requestConfirmation(trigger,title,copy,confirm,callback){dialogTrigger=trigger;pendingConfirmation=callback;dialog.classList.remove('as-sheet');$('#lib-dialog-title').textContent=title;const body=$('#lib-dialog-body');body.replaceChildren();const p=document.createElement('p');p.textContent=copy;body.append(p);const button=$('[data-dialog-confirm]',dialog);button.classList.remove('destructive');button.classList.add('primary');$('span',button).textContent=confirm;dialog.showModal();}
  $$('[data-open-dialog]').forEach(b=>b.addEventListener('click',()=>{
    pendingConfirmation=null;dialogTrigger=b;const sheet=b.dataset.openDialog==='sheet';dialog.classList.toggle('as-sheet',sheet);$('#lib-dialog-title').textContent=sheet?'选择书源':'清除离线缓存？';
    const body=$('#lib-dialog-body');body.replaceChildren();
    if(sheet){['综合书源','公开图书馆','本地导入'].forEach((name,index)=>{const label=document.createElement('label');label.className='lib-option';const input=document.createElement('input');input.type='radio';input.name='lib-sheet-source';input.checked=index===0;label.append(input,document.createTextNode(name));body.append(label);});}
    else {const paragraph=document.createElement('p');paragraph.textContent='阅读记录会保留。再次打开时需要重新下载。本地样例不会清除任何文件。';body.append(paragraph);}
    const confirm=$('[data-dialog-confirm]',dialog);confirm.classList.toggle('destructive',!sheet);confirm.classList.toggle('primary',sheet);confirm.querySelector('span').textContent=sheet?'确定书源':'清除缓存';dialog.showModal();
  }));
  const closeDialog=()=>{dialog.close();if(dialogTrigger)dialogTrigger.focus();};
  $$('[data-dialog-close]',dialog).forEach(b=>b.addEventListener('click',()=>{pendingConfirmation=null;closeDialog();}));
  $('[data-dialog-confirm]',dialog).addEventListener('click',()=>{const action=pendingConfirmation;pendingConfirmation=null;closeDialog();if(action)action();else showToast('示例确认完成；未操作真实业务数据');});
  dialog.addEventListener('cancel',()=>{pendingConfirmation=null;setTimeout(()=>{if(dialogTrigger)dialogTrigger.focus();},0);});
  $$('[data-upload-input]').forEach(input=>input.addEventListener('change',()=>{
    const file=input.files[0],sample=input.closest('.lib-sample');if(!file)return;
    const valid=['image/png','image/jpeg'].includes(file.type)&&file.size<=10*1024*1024;
    $('.lib-upload-name',sample).textContent=`${file.name} · ${(file.size/1024/1024).toFixed(1)} MB`;
    setStatus(sample,'.lib-upload-status',valid?'文件已加入本地样例队列，尚未上传。':'文件格式或大小不符合要求，请选择10 MB以内的PNG/JPEG。',!valid);
    $('[data-upload-start]',sample).disabled=!valid;
  }));
  $$('.lib-upload-drop').forEach(drop=>{
    drop.addEventListener('dragover',e=>{e.preventDefault();drop.classList.add('dragover');});drop.addEventListener('dragleave',()=>drop.classList.remove('dragover'));
    drop.addEventListener('drop',e=>{e.preventDefault();drop.classList.remove('dragover');const file=e.dataTransfer.files[0];if(file){const sample=drop.closest('.lib-sample');$('.lib-upload-name',sample).textContent=`${file.name} · 本地示例队列`;setStatus(sample,'.lib-upload-status','已识别拖放文件；不会自动上传。');}});
  });
  $$('[data-upload-start]').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample');setStatus(sample,'.lib-upload-status','正在模拟上传进度，不进行网络传输。');b.disabled=true;simulateProgress(sample,()=>{b.disabled=false;setStatus(sample,'.lib-upload-status','模拟上传完成。');});}));
  $$('[data-upload-cancel]').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample');clearInterval(timers.get(sample));setStatus(sample,'.lib-upload-status','已取消，文件仍保留在本地草稿中。');$('[data-upload-start]',sample).disabled=false;}));
  $$('[data-upload-fail]').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample');clearInterval(timers.get(sample));setStatus(sample,'.lib-upload-status','模拟连接中断，点击“重试上传”恢复。',true);const start=$('[data-upload-start]',sample);start.disabled=false;$('span',start).textContent='重试上传';}));
  $$('[data-file-picker]').forEach(input=>input.addEventListener('change',()=>{const list=$('.lib-picked-files',input.closest('.lib-sample'));list.replaceChildren();[...input.files].forEach(file=>{const row=document.createElement('div');row.className='lib-mini-row';row.textContent=`${file.name} · ${(file.size/1024).toFixed(0)} KB`;list.append(row);});}));
  $$('[data-media-toggle]').forEach(b=>b.addEventListener('click',()=>{const playing=b.dataset.playing==='true';b.dataset.playing=String(!playing);$('span',b).textContent=playing?'播放':'暂停';showToast(playing?'已暂停本地演示':'正在播放控件状态演示，不加载真实音频');}));
  $$('[data-media-speed]').forEach(b=>b.addEventListener('click',()=>{const rates=['1×','1.25×','1.5×','2×'];$('span',b).textContent=rates[(rates.indexOf($('span',b).textContent)+1)%rates.length];}));
  $$('.lib-media-player .lib-range').forEach(input=>input.addEventListener('input',()=>{const seconds=+input.value;$('[data-media-time]',input.closest('.lib-sample')).textContent=`${pad(Math.floor(seconds/60))}:${pad(seconds%60)} / 12:40`;}));
  $$('[data-reader-line]').forEach(input=>input.addEventListener('input',()=>{$('.lib-reader-preview',input.closest('.lib-sample')).style.lineHeight=input.value/100;}));
  $$('.lib-chat-form').forEach(form=>{
    const send=()=>{const input=$('textarea',form),out=$('.lib-chat-output',form);if(!input.value.trim()){out.textContent='请输入消息后再发送。';return;}out.textContent=`本地消息样例：${input.value.trim()}`;input.value='';};
    $('[data-chat-send]',form).addEventListener('click',send);form.addEventListener('submit',e=>{e.preventDefault();send();});
    $('textarea',form).addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key==='Enter'){e.preventDefault();send();}});
  });
  $$('[data-task-run]').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample'),tag=$('[data-task-tag]',sample);tag.textContent='处理中';tag.className='lib-tag warning';setStatus(sample,'.lib-task-status','正在处理本地演示任务…');simulateProgress(sample,()=>{tag.textContent='已完成';tag.className='lib-tag';setStatus(sample,'.lib-task-status','任务完成，可以查看结果。');});}));
  $$('[data-task-cancel]').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample');clearInterval(timers.get(sample));$('[data-task-tag]',sample).textContent='已取消';setStatus(sample,'.lib-task-status','任务已取消，参数保留，可重新开始。');}));
  $$('[data-task-fail]').forEach(b=>b.addEventListener('click',()=>{const sample=b.closest('.lib-sample');clearInterval(timers.get(sample));const tag=$('[data-task-tag]',sample);tag.textContent='失败';tag.className='lib-tag error';setStatus(sample,'.lib-task-status','任务失败，原始参数已保留，请重试。',true);$('[data-task-run] span',sample).textContent='重试任务';}));
  const previewOriginal=new WeakMap();
  $$('.lib-document-page').forEach(page=>previewOriginal.set(page,page.innerHTML));
  $$('[data-preview-error],[data-preview-unsupported],[data-preview-reset],[data-preview-fit]').forEach(b=>b.addEventListener('click',()=>{
    const page=$('.lib-document-page',b.closest('.lib-sample'));
    if(b.hasAttribute('data-preview-reset')){page.innerHTML=previewOriginal.get(page);return;}
    if(b.hasAttribute('data-preview-fit')){showToast('已按可用宽度显示样张');return;}
    page.replaceChildren();const title=document.createElement('h4'),description=document.createElement('p');title.textContent=b.hasAttribute('data-preview-error')?'预览加载失败':'暂不支持此格式';description.textContent=b.hasAttribute('data-preview-error')?'文件仍然保留，请重新加载或保存后打开。':'可以保存到本地，并使用其他应用打开。';page.append(title,description);
  }));
})();
