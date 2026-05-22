import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { fetchAlistAccounts, fetchAlistFiles } from '@/service/api/alist';
import { SetupStoreId } from '@/enum';

export interface AlistTreeNode {
  key: string;
  label: string;
  isLeaf: boolean;
  path: string;
  isDirectory: boolean;
  size?: number;
  lastModified?: string | null;
  children?: AlistTreeNode[];
}

export const useAlistStore = defineStore(SetupStoreId.Alist, () => {
  const currentPath = ref('/');
  const currentAccountId = ref<string>('');
  const fileList = ref<Api.CloudFile.CloudFileItem[]>([]);
  const treeData = ref<AlistTreeNode[]>([]);
  const loading = ref(false);
  const accounts = ref<Api.Webdav.WebdavAccount[]>([]);

  const isEmpty = computed(() => fileList.value.length === 0);

  function itemToNode(item: Api.CloudFile.CloudFileItem): AlistTreeNode {
    return {
      key: item.path,
      label: item.name,
      isLeaf: !item.isDirectory,
      path: item.path,
      isDirectory: item.isDirectory,
      size: item.size,
      lastModified: item.lastModified
    };
  }

  function updateNodeInTree(
    nodes: AlistTreeNode[],
    path: string,
    updater: (node: AlistTreeNode) => void
  ): boolean {
    for (const node of nodes) {
      if (node.path === path) {
        updater(node);
        return true;
      }
      if (node.children) {
        if (updateNodeInTree(node.children, path, updater)) return true;
      }
    }
    return false;
  }

  async function loadFiles(path: string, parentPath?: string) {
    loading.value = true;
    try {
      const { data, error } = await fetchAlistFiles(
        path,
        currentAccountId.value || undefined
      );
      if (error || !data) return;

      const items = data.items || [];
      fileList.value = items;
      currentPath.value = data.path || path;

      const effectiveParent =
        parentPath ?? (path === '/'
          ? '/'
          : path.substring(0, path.lastIndexOf('/')) || '/');

      if (effectiveParent === '/') {
        treeData.value = items.map(itemToNode);
      } else {
        const found = updateNodeInTree(treeData.value, effectiveParent, node => {
          node.children = items.map(itemToNode);
        });
        if (found) treeData.value = [...treeData.value];
      }
    } finally {
      loading.value = false;
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

  async function loadTreeNodeChildren(node: AlistTreeNode) {
    if (!node.isDirectory) return;
    if (node.children?.length) return;
    const { data } = await fetchAlistFiles(
      node.path,
      currentAccountId.value || undefined
    );
    if (!data) return;
    updateNodeInTree(treeData.value, node.path, n => {
      n.children = (data.items || []).map(itemToNode);
    });
    treeData.value = [...treeData.value];
  }

  async function loadAccounts() {
    const { data } = await fetchAlistAccounts();
    if (data) accounts.value = data;
  }

  return {
    currentPath,
    currentAccountId,
    fileList,
    treeData,
    loading,
    isEmpty,
    accounts,
    loadFiles,
    init,
    refresh,
    navigateTo,
    loadTreeNodeChildren,
    loadAccounts
  };
});
