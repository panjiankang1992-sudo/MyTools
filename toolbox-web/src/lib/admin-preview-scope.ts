/**
 * 草稿预览的**范围**（设计 164 行：「使用当前草稿的已选范围」）。
 *
 * 后端 `GET /textbooks/{id}/draft/preview` 支持 `nodeKind=unit|lesson` + `nodeKey`
 * + `draftRevision`；`nodeKind`／`nodeKey` 都不传即整册。这里放三个纯函数：
 *
 * - `previewScopeQuery`：把选中的目录节点翻成请求参数（整册 → 空对象，即两个都不传）；
 * - `describePreviewScope`：给界面用的范围名称，与"本次预览**真的**只有什么"的说明；
 * - `previewScopeFailureMessage`：把 404 `previewScopeNotFound` 翻成明确提示。
 *
 * **不许静默回退成整册**：范围不存在时必须显示错误。把"范围打错了"悄悄变成
 * "给你整册"会让管理员以为自己看到的是选中范围——那与"假装过滤"是同一类错误。
 */

/** 目录节点。 */
export interface PreviewScopeNode {
  kind: 'all' | 'unit' | 'lesson';
  id: string;
}

/** 预览请求的范围参数；整册时两个字段都不存在。 */
export interface PreviewScopeQuery {
  nodeKind?: 'unit' | 'lesson';
  nodeKey?: string;
}

/** 范围说明。 */
export interface PreviewScopeDescription {
  /** 面向用户的范围名称。 */
  label: string;
  /** 是否是整册。 */
  wholeBook: boolean;
  /** 本次预览实际包含的范围说明；整册时为空串。 */
  notice: string;
}

/**
 * 把选中的目录节点翻成预览请求的范围参数。
 *
 * 整册返回**空对象**：调用方据此**两个参数都不带**，与后端"缺省即整册"的契约一致。
 * 单元／课文即使标识为空也照样带上（后端会如实返回 404），
 * **不能**在这里退回空对象——那会把"范围无效"静默变成"整册"。
 *
 * @param node 当前选中的目录节点
 * @returns 需要附加到预览请求上的范围参数
 */
export function previewScopeQuery(node: PreviewScopeNode): PreviewScopeQuery {
  if (node.kind === 'all') {
    return {};
  }
  return { nodeKind: node.kind, nodeKey: node.id ?? '' };
}

/**
 * 生成预览范围的说明文案。
 *
 * @param node  当前选中的目录节点
 * @param titles 节点对应的真实标题（单元名或课文标题）；缺失时用占位名，**不编造**
 * @returns 范围名称与"本次预览真的包含什么"的说明
 */
export function describePreviewScope(
  node: PreviewScopeNode,
  titles: { unitTitle?: string | null; lessonTitle?: string | null } = {}
): PreviewScopeDescription {
  if (node.kind === 'unit') {
    const title = (titles.unitTitle ?? '').trim() || '未命名单元';
    return {
      label: `单元：${title}`,
      wholeBook: false,
      notice: '本次预览只包含该单元及其课文与条目。'
    };
  }
  if (node.kind === 'lesson') {
    const title = (titles.lessonTitle ?? '').trim() || '未命名课文';
    // 课文的父单元要**一起标出来**：预览该课文时后端会把所属单元一并带上。
    const parentUnit = (titles.unitTitle ?? '').trim();
    return {
      label: parentUnit ? `课文：${title}（${parentUnit}）` : `课文：${title}`,
      wholeBook: false,
      notice: parentUnit
        ? `本次预览只包含该课文及其所属单元（${parentUnit}）。`
        : '本次预览只包含该课文及其所属单元。'
    };
  }
  return { label: '整册', wholeBook: true, notice: '' };
}

/**
 * 把预览失败的 `details` 翻成用户可读的明确提示。
 *
 * @param details 管理接口错误里的 `details`
 * @returns 范围不存在时的提示；其他失败返回 null（由调用方按通用错误处理）
 */
export function previewScopeFailureMessage(
  details: Record<string, unknown> | undefined | null
): string | null {
  if (!details || details.reason !== 'previewScopeNotFound') {
    return null;
  }
  const kind = details.nodeKind === 'lesson' ? '课文' : details.nodeKind === 'unit' ? '单元' : '范围';
  return `预览范围不存在：所选的${kind}在已保存的草稿里找不到（可能刚被删除）。`
    + '本次预览未生成，也不会退化成整册内容；请重新选择范围后再试。';
}
