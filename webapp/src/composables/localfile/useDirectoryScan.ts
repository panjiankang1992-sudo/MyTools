import { onBeforeUnmount, ref } from 'vue';
import { useMessage } from 'naive-ui';
import { fetchGetScanTask, fetchScanDirectory } from '@/service/api/localfile';

/** 管理目录后台扫描及状态轮询。 */
export function useDirectoryScan(onCompleted?: () => void) {
  const message = useMessage();
  const scanRunning = ref(false);
  let pollTimer: ReturnType<typeof setTimeout> | null = null;

  function clearPollTimer() {
    if (pollTimer) clearTimeout(pollTimer);
    pollTimer = null;
  }

  async function pollTask(taskId: string) {
    const { data, error } = await fetchGetScanTask(taskId);
    if (error || !data) {
      scanRunning.value = false;
      message.error('获取扫描进度失败');
      return;
    }

    if (data.status === 'COMPLETED') {
      scanRunning.value = false;
      message.success(`扫描完成：共扫描 ${data.scannedCount || 0} 个文件，新增 ${data.newCount || 0} 个`);
      onCompleted?.();
      return;
    }

    if (data.status === 'FAILED') {
      scanRunning.value = false;
      message.error(data.errorMessage || '扫描失败');
      return;
    }

    pollTimer = setTimeout(() => void pollTask(taskId), 2000);
  }

  async function startScan(directoryId: number) {
    if (scanRunning.value) return;
    scanRunning.value = true;
    const { data, error } = await fetchScanDirectory(directoryId, true);
    if (error || !data?.taskId) {
      scanRunning.value = false;
      message.error('提交扫描任务失败');
      return;
    }

    message.success('扫描任务已提交，将在后台执行');
    void pollTask(data.taskId);
  }

  onBeforeUnmount(clearPollTimer);

  return { scanRunning, startScan };
}
