import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { fetchCloudFiles } from '@/service/api/cloudfile';
import { SetupStoreId } from '@/enum';

export const useCloudFileStore = defineStore(SetupStoreId.CloudFile, () => {
  /** 当前路径 */
  const currentPath = ref('/');

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

  /** 将 CloudFileItem 列表转换为 NTree 节点 */
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
      // children undefined → NTree shows expand arrow and triggers @load for lazy loading
    };
  }

  /** 递归查找并更新 treeData 中的某个节点 */
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

  /** 在 treeData 中查找指定路径的节点 */
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

  /** 加载指定路径的文件列表并更新 treeData */
  async function loadFiles(path: string, parentPath?: string) {
    const { data, error } = await fetchCloudFiles(path, 1);
    if (error || !data) {
      return;
    }

    const items = data.items || [];
    const nodes = items.map(itemToNode);

    fileList.value = items;
    currentPath.value = data.path || path;

    // 确定父路径：如果未指定，从当前路径推导
    const effectiveParent = parentPath ?? (path === '/' ? '/' : path.substring(0, path.lastIndexOf('/')) || '/');

    // 更新 treeData：替换或追加子节点
    if (effectiveParent === '/') {
      // 根目录，直接替换
      treeData.value = nodes;
    } else {
      // 找到父节点并更新其 children
      const found = updateNodeInTree(treeData.value, effectiveParent, node => {
        node.children = nodes;
      });
      if (found) {
        // 强制响应式更新
        treeData.value = [...treeData.value];
      }
    }
  }

  /** 初始化，加载根目录 */
  async function init() {
    await loadFiles('/');
  }

  /** 刷新当前目录 */
  async function refresh() {
    await loadFiles(currentPath.value);
  }

  /** 导航到指定路径 */
  async function navigateTo(path: string) {
    loading.value = true;
    try {
      await loadFiles(path);
    } finally {
      loading.value = false;
    }
  }

  /** 选择文件节点，切换到文件详情视图 */
  function selectFile(path: string) {
    const node = findNode(treeData.value, path);
    if (node && !node.isDirectory) {
      selectedNode.value = node;
    }
  }

  /** 清除文件选择，返回目录视图 */
  function clearSelection() {
    selectedNode.value = null;
  }

  /** 懒加载树节点的子节点 */
  async function loadTreeNodeChildren(node: CloudFileTreeNode) {
    if (!node.isDirectory) return;
    if (node.children && node.children.length > 0) return; // 已有子节点

    const { data, error } = await fetchCloudFiles(node.path, 1);
    if (error || !data) return;

    const children = (data.items || []).map(itemToNode);

    updateNodeInTree(treeData.value, node.path, n => {
      n.children = children;
    });
    treeData.value = [...treeData.value];
  }

  return {
    currentPath,
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
    clearSelection
  };
});

/** NTree 树节点类型 */
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
