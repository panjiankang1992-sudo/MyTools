import { createServer } from 'node:net';
import { chmod, lstat, mkdir, realpath, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import { homedir } from 'node:os';

export const name = 'news-workspace-registration';
export const inject = ['workspaceRegistry'];
const title = '\u6bcf\u65e5\u65b0\u95fb';

/** 网页进程是分组存储的唯一写入者，只接收同用户新闻会话的登记请求。 */
export async function apply(ctx) {
  const directory = join(homedir(), 'workspace/news-personal/dsh');
  const workspacePath = join(directory, 'workspace');
  const socketPath = join(directory, 'group.sock');
  await mkdir(workspacePath, { recursive: true, mode: 0o700 });
  await chmod(directory, 0o700);
  const canonical = await realpath(workspacePath);
  // 工作区可被用户移除，后续登记时重新解析，保证新会话仍归入新闻分组。
  const workspace = async () => {
    const item = await ctx.workspaceRegistry.create(canonical, title);
    if (item.title !== title) await item.setTitle(title);
    return item;
  };
  await workspace();
  try {
    const file = await lstat(socketPath);
    if (!file.isSocket()) throw new Error('DSH_NEWS_SOCKET_PATH_INVALID');
    await unlink(socketPath);
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  const clients = new Set();
  const server = createServer({ allowHalfOpen: true }, socket => {
    clients.add(socket);
    socket.setTimeout(10000, () => socket.destroy());
    socket.on('error', () => {});
    socket.on('close', () => clients.delete(socket));
    let body = '';
    socket.on('data', chunk => {
      body += chunk;
      if (body.length > 1024) socket.destroy();
    });
    socket.on('end', async () => {
      try {
        const request = JSON.parse(body);
        const group = await workspace();
        if (request.sessionId !== undefined) {
          if (!/^session-[a-f0-9-]{36}$/.test(request.sessionId)) throw new Error('DSH_NEWS_SESSION_INVALID');
          // DSH 自身核验会话头的 cwd 与工作区完全一致，拒绝移动其他项目会话。
          await group.attachSession(request.sessionId);
          if (!group.sessionIds.includes(request.sessionId)) throw new Error('DSH_NEWS_GROUP_FAILED');
        }
        socket.end(JSON.stringify({ ok: true, workspaceId: group.id }) + '\n');
      } catch { socket.end(JSON.stringify({ ok: false }) + '\n'); }
    });
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(socketPath, resolve);
  });
  await chmod(socketPath, 0o600);
  ctx.on('dispose', async () => {
    for (const client of clients) client.destroy();
    await new Promise(resolve => server.close(resolve));
    await unlink(socketPath).catch(error => { if (error.code !== 'ENOENT') throw error; });
  });
}
