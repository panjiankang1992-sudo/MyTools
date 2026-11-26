/** 在当前 DSH 文档内换票，并只向原生端回报验收状态。 */
export class DshWebSessionRenewal {
  /** 复用网页换票的 Cookie，手动拦下 303，避免导航和重放当前操作。 */
  static exchangeScript(page: string, ticketPath: string, attempt: number): string {
    return DshWebSessionRenewal.startScript(page, attempt, `
      const path=${JSON.stringify(ticketPath)};
      if(!/^\\/_mytools\\/connect\\?ticket=[A-Za-z0-9_-]{43}$/.test(path))return 0;
    `, `fetch(new URL(path,location.origin),{...base,redirect:'manual'}).then(response=>{
        if(response.type!=='opaqueredirect'&&response.status!==303)throw new Error('Ticket exchange failed');
        if(location.href!==${JSON.stringify(page)})throw new Error('Page changed');
        return checkStatus();
      })`, false);
  }

  /** 探测当前 Cookie，对明确的 401 发起续期。 */
  static probeScript(page: string, attempt: number): string {
    return DshWebSessionRenewal.startScript(page, attempt, '', 'checkStatus()', true);
  }

  private static startScript(page: string, attempt: number, validate: string, request: string,
    detectExpiry: boolean): string {
    return `(function(){
      if(location.href!==${JSON.stringify(page)})return 0;
      ${validate}
      const controller=new AbortController();
      const state={id:${attempt},result:0,controller:controller};
      window.mytoolsDshWebRenewal?.controller.abort();
      window.mytoolsDshWebRenewal=state;
      const deadline=setTimeout(()=>controller.abort(),9000);
      const base={credentials:'same-origin',mode:'same-origin',cache:'no-store',referrerPolicy:'no-referrer',signal:controller.signal};
      const checkStatus=()=>fetch(new URL('/_mytools/session-status',location.origin),{...base,redirect:'error'});
      ${request}.then(response=>{
        state.result=response.status===204?1:(${detectExpiry}&&response.status===401?3:2);
      })
        .catch(()=>{state.result=2;}).finally(()=>clearTimeout(deadline));
      return 1;
    })()`;
  }

  /** ArkWeb 的脚本调用不等待网页 Promise，因此只查询本次异步换票的结果。 */
  static resultScript(page: string, attempt: number): string {
    return `(function(){
      if(location.href!==${JSON.stringify(page)})return 2;
      const state=window.mytoolsDshWebRenewal;
      if(!state||state.id!==${attempt})return 2;
      if(state.result!==0)delete window.mytoolsDshWebRenewal;
      return state.result;
    })()`;
  }

  /** 页面或账号切换后取消属于该轮的网页请求。 */
  static cancelScript(attempt: number): string {
    return `(function(){
      const state=window.mytoolsDshWebRenewal;
      if(state?.id!==${attempt})return 0;
      state.controller.abort();
      delete window.mytoolsDshWebRenewal;
      return 1;
    })()`;
  }
}
