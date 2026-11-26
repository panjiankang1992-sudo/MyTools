// 仅用于本地验收的投递状态机，不连接任何真实提供方。
export function createDeliveryFixture(reply) {
  const email = '00000000-0000-4000-8000-000000000050';
  const feishu = '00000000-0000-4000-8000-000000000051';
  const createdAt = '2026-10-18T00:00:00Z';
  const destinations = [{ id: email, channel: 'EMAIL', label: 'Personal inbox', enabled: true, createdAt },
    { id: feishu, channel: 'FEISHU', label: 'Daily news group', enabled: true, createdAt },
    { id: 'qq-unavailable', channel: 'QQ', label: 'Unavailable QQ', enabled: true, createdAt }];
  let targets = { revision: 1, targets: [{ destinationId: email, channel: 'EMAIL', mode: 'FULL', enabled: true }] };
  const makeBatch = (suffix, target, channel, status) => ({ id: `00000000-0000-4000-8000-0000000000${suffix}`,
    runId: '00000000-0000-4000-8000-000000000010', editionId: '00000000-0000-4000-8000-000000000001', destinationId: target,
    channel, mode: 'FULL', status, cancelRequested: false, expiresAt: '2099-01-01T00:00:00Z', createdAt, errorCode: null,
    parts: [{ id: `part-${suffix}`, index: 1, count: 1, status, generation: 1, errorCode: null, retryPending: false }] });
  const batches = [makeBatch('60', email, 'EMAIL', 'UNKNOWN'), makeBatch('61', feishu, 'FEISHU', 'REJECTED')];
  let conflict = false;
  let historyFailure = false;
  let loseRetry = true;
  return {
    control(body) { conflict = body.deliveryConflict ?? conflict; historyFailure = body.failDeliveryHistory ?? historyFailure; },
    snapshot() { return { targets, batches }; },
    handle(path, req, res, body) {
      if (path.endsWith('/delivery-capabilities')) reply(res, { enabled: true, supportedChannels: ['EMAIL', 'FEISHU'], qqStatus: 'PROACTIVE_PUSH_UNAVAILABLE', bindingMode: 'ADMIN_VERIFIED', targetLimit: 5 });
      else if (path.endsWith('/destinations')) reply(res, destinations);
      else if (path.endsWith('/delivery-targets') && req.method === 'GET') reply(res, targets);
      else if (path.endsWith('/delivery-targets') && req.method === 'PUT') {
        if (conflict || body.expectedRevision !== targets.revision) { conflict = false; targets.revision++; reply(res, null, 409, 'NEWS_022'); }
        else { targets = { revision: targets.revision + 1, targets: body.targets.map(item => ({ ...item, channel: destinations.find(d => d.id === item.destinationId)?.channel })) }; reply(res, targets); }
      } else if (path.endsWith('/deliveries')) {
        if (historyFailure) reply(res, null, 503, 'GATEWAY_003'); else reply(res, batches);
      } else if (path.includes('/deliveries/')) {
        const batch = batches.find(item => path.includes(item.id));
        if (!batch) { reply(res, null, 404, 'NEWS_003'); return true; }
        const part = batch.parts[0];
        if (path.endsWith('/cancel')) { batch.cancelRequested = true; batch.status = 'SENDING'; part.status = 'SENDING'; }
        if (path.endsWith('/redrive')) { batch.status = 'SUBMITTING'; part.status = 'SUBMITTING'; }
        if (path.endsWith('/retry')) {
          if (body.expectedGeneration === part.generation) { part.generation++; part.status = 'FAILED'; batch.status = 'FAILED'; }
          if (loseRetry) { loseRetry = false; req.socket.destroy(); return true; }
        }
        reply(res, batch);
      } else return false;
      return true;
    }
  };
}
