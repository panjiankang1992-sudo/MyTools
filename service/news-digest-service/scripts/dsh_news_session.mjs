import { connect } from 'node:net';

export const name = 'news-session-metadata';
export const inject = ['sessions', 'sessionTitle'];

/** 仅通过本机私有套接字让 DSH 网页进程登记分组，避免多个进程改写工作区存储。 */
export function registerSession(socketPath, sessionId) {
  return new Promise((resolve, reject) => {
    const socket = connect(socketPath);
    let response = '';
    const timer = setTimeout(() => socket.destroy(new Error('DSH_NEWS_GROUP_TIMEOUT')), 10000);
    socket.on('connect', () => socket.end(JSON.stringify({ sessionId }) + '\n'));
    socket.on('data', chunk => {
      response += chunk;
      if (response.length > 4096) socket.destroy(new Error('DSH_NEWS_GROUP_INVALID'));
    });
    socket.on('error', reject);
    socket.on('close', () => clearTimeout(timer));
    socket.on('end', () => {
      try {
        if (JSON.parse(response).ok !== true) throw new Error('DSH_NEWS_GROUP_FAILED');
        resolve();
      } catch { reject(new Error('DSH_NEWS_GROUP_FAILED')); }
    });
  });
}

/** 在模型请求前写入固定标题并确认归组，失败时阻止分析继续。 */
export function apply(ctx) {
  const title = process.env.NEWS_DSH_SESSION_TITLE;
  const socketPath = process.env.NEWS_DSH_SOCKET;
  if (!title || !socketPath) throw new Error('DSH_NEWS_METADATA_REQUIRED');
  ctx.on('agent/created', async ({ agent }) => {
    ctx.sessionTitle.rename(agent.session, title);
    await ctx.sessions.flush(agent.session);
    await registerSession(socketPath, agent.session.id);
  });
}
