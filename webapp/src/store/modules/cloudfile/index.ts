import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { fetchCloudFiles } from '@/service/api/cloudfile';
import { SetupStoreId } from '@/enum';

export const useCloudFileStore = defineStore(SetupStoreId.CloudFile, () => {
  /** 当前路径 */
  const currentPath = ref('/');

  /** 当前选中的 WebDAV 账号 ID */
  const currentAccountId = ref<string>('');

  /** 当前目录的文件列表 */
  const fileList = ref<Api.CloudFile.CloudFileItem[]>([]);

  /** 目录树数据 (用于 NTree) */
  const treeData = ref<CloudFileTreeNode[]>([]);

  /** 加载状态 */
  const loading = ref(false);

  /** 是否有文件或目录 */
  const isEmpty = computed(() => fileList.value.length === 0);

  /** 当前选中的文件节点（用于文件详情视图） */
  const selectedNode = ref<CloudFileTreeNode | null>(null);

  /** 当前视图模式 */
  const viewMode = computed<'directory' | 'file-detail'>(() =>
    selectedNode.value && !selectedNode.value.isDirectory ? 'file-detail' : 'directory'
  );

  function itemToNode(item: Api.CloudFile.CloudFileItem): CloudFileTreeNode {
    return {
      key: item.path,
      label: item.name,
      isLeaf: !item.isDirectory,
      path: item.path,
      isDirectory: item.isDirectory,
      size: item.size,
      contentType: item.contentType,
      lastModified: item.lastModified
    };
  }

  function updateNodeInTree(
    nodes: CloudFileTreeNode[],
    path: string,
    updater: (node: CloudFileTreeNode) => void
  ): boolean {
    for (const node of nodes) {
      if (node.path === path) {
        updater(node);
        return true;
      }
      if (node.children && node.children.length > 0) {
        const found = updateNodeInTree(node.children, path, updater);
        if (found) return true;
      }
    }
    return false;
  }

  function findNode(nodes: CloudFileTreeNode[], path: string): CloudFileTreeNode | null {
    for (const node of nodes) {
      if (node.path === path) return node;
      if (node.children && node.children.length > 0) {
        const found = findNode(node.children, path);
        if (found) return found;
      }
    }
    return null;
  }

  function getAccountId(): string | undefined {
    return currentAccountId.value || undefined;
  }

  async function loadFiles(path: string, parentPath?: string) {
    const { data, error } = await fetchCloudFiles(path, 1, getAccountId());
    if (error || !data) {
      return;
    }

    const items = data.items || [];
    const nodes = items.map(itemToNode);

    fileList.value = items;
    currentPath.value = data.path || path;

    const effectiveParent = parentPath ?? (path === '/' ? '/' : path.substring(0, path.lastIndexOf('/')) || '/');

    if (effectiveParent === '/') {
      treeData.value = nodes;
    } else {
      const found = updateNodeInTree(treeData.value, effectiveParent, node => {
        node.children = nodes;
      });
      if (found) {
        treeData.value = [...treeData.value];
      }
    }
  }

  async function init(accountId?: string) {
    if (accountId) currentAccountId.value = accountId;
    currentPath.value = '/';
    await loadFiles('/');
  }

  async function refresh() {
    await loadFiles(currentPath.value);
  }

  async function navigateTo(path: string) {
    loading.value = true;
    try {
      await loadFiles(path);
    } finally {
      loading.value = false;
    }
  }

  function selectFile(path: string) {
    const node = findNode(treeData.value, path);
    if (node && !node.isDirectory) {
      selectedNode.value = node;
    }
  }

  function clearSelection() {
    selectedNode.value = null;
  }

  async function loadTreeNodeChildren(node: CloudFileTreeNode) {
    if (!node.isDirectory) return;
    if (node.children && node.children.length > 0) return;

    const { data, error } = await fetchCloudFiles(node.path, 1, getAccountId());
    if (error || !data) return;

    const children = (data.items || []).map(itemToNode);

    updateNodeInTree(treeData.value, node.path, n => {
      n.children = children;
    });
    treeData.value = [...treeData.value];
  }

  return {
    currentPath,
    currentAccountId,
    fileList,
    treeData,
    loading,
    isEmpty,
    selectedNode,
    viewMode,
    loadFiles,
    init,
    refresh,
    navigateTo,
    loadTreeNodeChildren,
    findNode: (path: string) => findNode(treeData.value, path),
    selectFile,
    clearSelection,
    getAccountId
  };
});

export interface CloudFileTreeNode {
  key: string;
  label: string;
  isLeaf: boolean;
  path: string;
  isDirectory: boolean;
  size?: number;
  contentType?: string | null;
  lastModified?: string | null;
  children?: CloudFileTreeNode[];
}
