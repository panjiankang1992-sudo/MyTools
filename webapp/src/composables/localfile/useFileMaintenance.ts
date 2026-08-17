import { onBeforeUnmount, ref } from 'vue';
import { useMessage } from 'naive-ui';
import { fetchGetFileMaintenanceTask, fetchStartFileMaintenance } from '@/service/api/localfile';

/** 管理文件去重及电子书智能整理后台任务。 */
export function useFileMaintenance(onCompleted?: () => void) {
  const message = useMessage();
  const maintenanceRunning = ref(false);
  const maintenanceProgress = ref('');
  let pollTimer: ReturnType<typeof setTimeout> | null = null;

  function clearPollTimer() {
    if (pollTimer) clearTimeout(pollTimer);
    pollTimer = null;
  }

  async function pollTask(taskId: string) {
    const { data, error } = await fetchGetFileMaintenanceTask(taskId);
    if (error || !data) {
      maintenanceRunning.value = false;
      maintenanceProgress.value = '';
      message.error('获取整理进度失败');
      return;
    }
    if (data.status === 'COMPLETED') {
      maintenanceRunning.value = false;
      maintenanceProgress.value = `已完成：检查 ${data.checkedCount}，隔离 ${data.duplicateCount}，重命名 ${data.renamedCount}`;
      message.success(`整理完成：检查 ${data.checkedCount} 个，隔离重复文件 ${data.duplicateCount} 个，重命名 ${data.renamedCount} 个`);
      onCompleted?.();
      return;
    }
    if (data.status === 'FAILED') {
      maintenanceRunning.value = false;
      maintenanceProgress.value = '';
      message.error(data.errorMessage || '文件整理失败');
      return;
    }
    maintenanceProgress.value = `整理中：已检查 ${data.checkedCount}，已隔离 ${data.duplicateCount}，已重命名 ${data.renamedCount}`;
    pollTimer = setTimeout(() => void pollTask(taskId), 2000);
  }

  async function startMaintenance(directoryId: number, mode: 'EXACT_DEDUP' | 'EBOOK_ORGANIZE') {
    if (maintenanceRunning.value) return;
    maintenanceRunning.value = true;
    maintenanceProgress.value = '正在提交整理任务…';
    const { data, error } = await fetchStartFileMaintenance(directoryId, mode);
    if (error || !data?.taskId) {
      maintenanceRunning.value = false;
      maintenanceProgress.value = '';
      message.error('提交文件整理任务失败');
      return;
    }
    message.success('文件整理任务已提交，将在后台执行');
    void pollTask(data.taskId);
  }

  onBeforeUnmount(clearPollTimer);
  return { maintenanceRunning, maintenanceProgress, startMaintenance };
}
