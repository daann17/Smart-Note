<template>
  <a-modal
    v-model:open="visible"
    title="导入文档"
    width="600px"
    @cancel="handleCancel"
    :footer="null"
  >
    <div v-if="step === 1" class="import-step">
      <h3 class="step-title">选择导入文件</h3>
      <a-upload
        v-model:file-list="fileList"
        :multiple="false"
        :before-upload="beforeUpload"
        :show-upload-list="true"
        class="upload-area"
      >
        <a-button type="primary" icon="upload">
          选择文件
        </a-button>
        <div class="upload-hint">
          支持单个Markdown文件、Markdown ZIP包或Word文档
        </div>
      </a-upload>

      <h3 class="step-title">选择目标知识库</h3>
      <a-select
        v-model:value="selectedNotebookId"
        style="width: 100%"
        placeholder="请选择知识库"
      >
        <a-option
          v-for="notebook in notebooks"
          :key="notebook.id"
          :value="notebook.id"
        >
          {{ notebook.name }}
        </a-option>
      </a-select>

      <div class="step-actions">
        <a-button @click="handleCancel">取消</a-button>
        <a-button
          type="primary"
          @click="handleImport"
          :disabled="!fileList.length || !selectedNotebookId"
        >
          开始导入
        </a-button>
      </div>
    </div>

    <div v-else-if="step === 2" class="import-step">
      <h3 class="step-title">导入中</h3>
      <div class="progress-container">
        <a-progress
          :percent="progress"
          :status="progressStatus"
          :format="formatProgress"
        />
        <div class="progress-text">{{ progressText }}</div>
      </div>
    </div>

    <div v-else-if="step === 3" class="import-step">
      <h3 class="step-title">导入结果</h3>
      <div class="result-card">
        <div class="result-item">
          <span class="result-label">总文件数：</span>
          <span class="result-value">{{ task?.totalFiles || 0 }}</span>
        </div>
        <div class="result-item">
          <span class="result-label">成功：</span>
          <span class="result-value success">{{ task?.successCount || 0 }}</span>
        </div>
        <div class="result-item">
          <span class="result-label">失败：</span>
          <span class="result-value error">{{ task?.failureCount || 0 }}</span>
        </div>
        <div v-if="task?.failureCount > 0" class="result-item">
          <span class="result-label">失败原因：</span>
          <span class="result-value error">{{ errorMessage }}</span>
        </div>
      </div>

      <div class="step-actions">
        <a-button @click="handleCancel">关闭</a-button>
        <a-button
          type="primary"
          @click="handleImportAgain"
        >
          再次导入
        </a-button>
      </div>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { message } from 'ant-design-vue';
import api from '../api';

// Props
const props = defineProps<{
  visible: boolean;
  defaultNotebookId?: number;
}>();

// Emits
const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void;
  (e: 'import-success'): void;
}>();

// State
const step = ref(1);
const fileList = ref<any[]>([]);
const selectedNotebookId = ref<number | undefined>(props.defaultNotebookId);
const notebooks = ref<any[]>([]);
const task = ref<any>(null);
const progress = ref(0);
const progressText = ref('准备导入...');
const errorMessage = ref('');

// Computed
const progressStatus = computed(() => {
  if (progress.value === 100) return 'success';
  return 'active';
});

// Methods
const formatProgress = (percent: number) => {
  return `${percent}%`;
};

const beforeUpload = (file: any) => {
  const allowedTypes = ['.md', '.zip', '.docx'];
  const fileExtension = file.name.substring(file.name.lastIndexOf('.'));
  
  if (!allowedTypes.includes(fileExtension)) {
    message.error('仅支持Markdown文件、Markdown ZIP包或Word文档');
    return false;
  }

  if (file.size > 100 * 1024 * 1024) {
    message.error('文件大小不能超过100MB');
    return false;
  }

  fileList.value = [file];
  return false; // 手动上传
};

const handleCancel = () => {
  resetState();
  emit('update:visible', false);
};

const handleImport = async () => {
  if (!fileList.value.length || !selectedNotebookId.value) return;

  step.value = 2;
  progressText.value = '上传文件中...';

  try {
    const formData = new FormData();
    formData.append('file', fileList.value[0]);
    formData.append('notebookId', selectedNotebookId.value.toString());

    const response = await api.post('/import', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    });

    task.value = response.data;
    await pollTaskStatus(task.value.id);
  } catch (error) {
    message.error('导入失败，请重试');
    step.value = 1;
  }
};

const pollTaskStatus = async (taskId: number) => {
  let interval: any;
  
  interval = setInterval(async () => {
    try {
      const response = await api.get(`/import/task/${taskId}`);
      const updatedTask = response.data;
      task.value = updatedTask;

      // Update progress
      if (updatedTask.totalFiles > 0) {
        const processed = updatedTask.successCount + updatedTask.failureCount;
        progress.value = Math.round((processed / updatedTask.totalFiles) * 100);
      }

      // Update status text
      switch (updatedTask.status) {
        case 'PENDING':
          progressText.value = '等待处理...';
          break;
        case 'PROCESSING':
          progressText.value = '处理中...';
          break;
        case 'SUCCESS':
          progressText.value = '导入成功！';
          progress.value = 100;
          clearInterval(interval);
          setTimeout(() => {
            step.value = 3;
            emit('import-success');
          }, 1000);
          break;
        case 'PARTIAL_SUCCESS':
          progressText.value = '部分导入成功';
          progress.value = 100;
          clearInterval(interval);
          setTimeout(() => {
            step.value = 3;
            emit('import-success');
          }, 1000);
          break;
        case 'FAILED':
          progressText.value = '导入失败';
          clearInterval(interval);
          setTimeout(() => {
            step.value = 3;
          }, 1000);
          break;
      }
    } catch (error) {
      clearInterval(interval);
      message.error('获取任务状态失败');
      step.value = 1;
    }
  }, 1000);
};

const handleImportAgain = () => {
  resetState();
  step.value = 1;
};

const resetState = () => {
  step.value = 1;
  fileList.value = [];
  selectedNotebookId.value = props.defaultNotebookId;
  task.value = null;
  progress.value = 0;
  progressText.value = '准备导入...';
  errorMessage.value = '';
};

const loadNotebooks = async () => {
  try {
    const response = await api.get('/notebooks');
    notebooks.value = response.data;
  } catch (error) {
    message.error('获取知识库列表失败');
  }
};

// Lifecycle
onMounted(() => {
  loadNotebooks();
});

watch(() => props.visible, (newValue) => {
  if (newValue) {
    loadNotebooks();
  } else {
    resetState();
  }
});
</script>

<style scoped>
.import-step {
  padding: 20px 0;
}

.step-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 16px;
  color: #333;
}

.upload-area {
  margin-bottom: 24px;
}

.upload-hint {
  margin-top: 8px;
  font-size: 12px;
  color: #999;
}

.progress-container {
  margin: 40px 0;
}

.progress-text {
  text-align: center;
  margin-top: 16px;
  color: #666;
}

.result-card {
  background-color: #f5f5f5;
  padding: 20px;
  border-radius: 8px;
  margin: 20px 0;
}

.result-item {
  margin-bottom: 12px;
  display: flex;
  justify-content: space-between;
}

.result-label {
  color: #666;
}

.result-value {
  font-weight: 600;
}

.result-value.success {
  color: #52c41a;
}

.result-value.error {
  color: #ff4d4f;
}

.step-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 32px;
}
</style>