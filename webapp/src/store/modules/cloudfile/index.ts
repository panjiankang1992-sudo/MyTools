import { ref } from 'vue';
import { defineStore } from 'pinia';
import { useLoading } from '@sa/hooks';
import { fetchCloudFiles } from '@/service/api/cloudfile';
import { SetupStoreId } from '@/enum';

interface CloudFileTreeNode {
  key: string;
  label: string;
  isLeaf: boolean;
  children?: CloudFileTreeNode[];
  loading?: boolean;
}

export const useCloudFileStore = defineStore(SetupStoreId.CloudFile, () => {
  const { loading, startLoading, endLoading } = useLoading();

  /** Current path */
  const currentPath = ref('/');

  /** File list items */
  const items = ref<Api.CloudFile.CloudFileItem[]>([]);

  /** Tree data for directory tree */
  const treeData = ref<CloudFileTreeNode[]>([]);

  /**
   * Load files at the given path
   *
   * @param path Path to load
   * @param depth Fetch depth (1 = list only, 2+ = include subdirectories)
   */
  async function loadFiles(path: string, depth = 1) {
    startLoading();

    try {
      const resp = await fetchCloudFiles(path, depth);
      currentPath.value = path;
      items.value = resp.data?.items || [];

      if (depth > 1) {
        const dirs = (resp.data?.items || []).filter(
          (i: Api.CloudFile.CloudFileItem) => i.isDirectory
        );
        updateTreeChildren(path, dirs);
      }
    } finally {
      endLoading();
    }
  }

  /**
   * Update tree node children for a given parent path
   *
   * @param parentPath Parent path (tree node key)
   * @param dirs Directory items to set as children
   */
  function updateTreeChildren(parentPath: string, dirs: Api.CloudFile.CloudFileItem[]) {
    const updateNode = (nodes: CloudFileTreeNode[]): boolean => {
      for (const node of nodes) {
        if (node.key === parentPath) {
          node.children = dirs.map(d => ({
            key: d.path,
            label: d.name,
            isLeaf: false,
            children: []
          }));
          return true;
        }
        if (node.children && updateNode(node.children)) {
          return true;
        }
      }
      return false;
    };

    updateNode(treeData.value);
  }

  /**
   * Build tree root from a flat directory list
   *
   * @param dirs Directory items to build tree from
   */
  function buildTree(dirs: Api.CloudFile.CloudFileItem[]) {
    treeData.value = dirs.map(d => ({
      key: d.path,
      label: d.name,
      isLeaf: false,
      children: []
    }));
  }

  return {
    currentPath,
    items,
    treeData,
    loading,
    loadFiles,
    updateTreeChildren,
    buildTree
  };
});
