<template>
  <div class="stores-view-container">
    <div class="view-header">
      <h2 class="page-title">向量库列表</h2>
      <div class="header-actions">
        <el-button type="primary" size="small" :icon="Plus" @click="showCreateDialog = true">
          新建向量库
        </el-button>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="fetchStores">
          刷新
        </el-button>
      </div>
    </div>

    <!-- Backend offline warning -->
    <div v-if="!appStore.isConnected" class="offline-banner">
      <el-icon class="warn-icon"><WarningFilled /></el-icon>
      <span>后端服务未连接，请确认应用已启动</span>
      <el-button size="small" type="primary" plain @click="appStore.checkHealth()">重试</el-button>
    </div>

    <!-- Stores Grid -->
    <div v-if="loading && storeDetails.length === 0" class="loading-state">
      <el-skeleton :rows="3" animated />
    </div>

    <div v-else-if="storeDetails.length === 0" class="empty-state">
      <el-empty description="暂无向量库">
        <el-button type="primary" size="small" :icon="Plus" @click="showCreateDialog = true">新建向量库</el-button>
      </el-empty>
    </div>

    <div v-else class="stores-grid">
      <div
        v-for="store in storeDetails"
        :key="store.storeName"
        class="store-card"
        :class="{ 'is-active': appStore.currentStore === store.storeName }"
        @click="selectStore(store.storeName)"
      >
        <div class="card-header">
          <div class="store-name-row">
            <span class="store-name font-mono">{{ store.storeName }}</span>
            <span v-if="appStore.currentStore === store.storeName" class="active-badge">当前</span>
          </div>
          <el-dropdown trigger="click" @command="(cmd: string) => handleStoreAction(cmd, store.storeName)">
            <el-button link class="more-btn">
              <el-icon><MoreFilled /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="documents">数据管理</el-dropdown-item>
                <el-dropdown-item command="search">查询调试</el-dropdown-item>
                <el-dropdown-item command="refresh" divided>手动落盘</el-dropdown-item>
                <el-dropdown-item command="reload">快照重载</el-dropdown-item>
                <el-dropdown-item command="drop" divided class="danger-item">删除库</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>

        <div class="card-meta-grid">
          <div class="meta-item">
            <span class="meta-lbl">维度</span>
            <span class="meta-val font-mono">{{ store.dimension }}</span>
          </div>
          <div class="meta-item">
            <span class="meta-lbl">度量</span>
            <span class="meta-val font-mono">{{ store.metric || 'COSINE' }}</span>
          </div>
          <div class="meta-item">
            <span class="meta-lbl">量化</span>
            <span class="meta-val font-mono">{{ store.quantization || 'NONE' }}</span>
          </div>
          <div class="meta-item">
            <span class="meta-lbl">文档数</span>
            <span class="meta-val font-mono num-highlight">{{ store.docCount }}</span>
          </div>
        </div>

        <div class="card-footer" @click.stop>
          <el-button size="small" type="primary" plain @click="goToDocs(store.storeName)">
            数据管理
          </el-button>
          <el-button size="small" type="success" plain @click="goToSearch(store.storeName)">
            查询调试
          </el-button>
        </div>
      </div>
    </div>

    <!-- Create Store Dialog -->
    <el-dialog v-model="showCreateDialog" title="新建向量库" width="460px" append-to-body>
      <el-form :model="createForm" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="createForm.storeName" placeholder="如: knowledge_base" class="font-mono" />
        </el-form-item>

        <el-form-item label="维度" required>
          <el-input-number v-model="createForm.dimension" :min="1" :max="4096" :step="128" style="width: 100%;" />
        </el-form-item>

        <div class="form-row">
          <el-form-item label="距离度量" style="flex: 1;">
            <el-select v-model="createForm.metric" style="width: 100%;">
              <el-option label="COSINE (余弦)" value="COSINE" />
              <el-option label="L2 (欧氏距离)" value="L2" />
              <el-option label="DOT_PRODUCT (点积)" value="DOT_PRODUCT" />
            </el-select>
          </el-form-item>

          <el-form-item label="量化方式" style="flex: 1;">
            <el-select v-model="createForm.quantization" style="width: 100%;">
              <el-option label="NONE (不量化)" value="NONE" />
              <el-option label="SQ8 (标量量化)" value="SQ8" />
            </el-select>
          </el-form-item>
        </div>

        <el-form-item label="最大容量">
          <el-input-number v-model="createForm.maxCapacity" :min="1000" :max="10000000" :step="10000" style="width: 100%;" />
        </el-form-item>

        <el-form-item label="元数据过滤字段 (逗号分隔)">
          <el-input v-model="indexedFieldsStr" placeholder="如: category, tag" />
        </el-form-item>
      </el-form>

      <template #footer>
        <span class="dialog-footer">
          <el-button @click="showCreateDialog = false">取消</el-button>
          <el-button type="primary" :loading="creating" @click="submitCreateStore">确认</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/appStore'
import api from '../api/veclite'
import type { VectorStoreDefinition, VectorStoreStats } from '../types'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, MoreFilled, WarningFilled } from '@element-plus/icons-vue'

const router = useRouter()
const appStore = useAppStore()

const loading = ref(false)
const storeDetails = ref<VectorStoreStats[]>([])
const showCreateDialog = ref(false)
const creating = ref(false)
const indexedFieldsStr = ref('')

const createForm = ref<VectorStoreDefinition>({
  storeName: '',
  dimension: 512,
  metric: 'COSINE',
  quantization: 'NONE',
  maxCapacity: 100000
})

const fetchStores = async () => {
  loading.value = true
  try {
    const stores = await api.listStores()
    appStore.storeList = stores
    const list: VectorStoreStats[] = []

    for (const name of stores) {
      try {
        const stats = await api.getStats(name)
        list.push(stats)
      } catch {
        list.push({
          storeName: name,
          dimension: 0,
          docCount: 0,
          maxCapacity: 100000,
          metric: 'COSINE',
          quantization: 'NONE'
        })
      }
    }
    storeDetails.value = list
    if (!appStore.currentStore && stores.length > 0) {
      appStore.setCurrentStore(stores[0])
    }
  } catch (err: any) {
    ElMessage.error(err.message || '获取向量库列表失败')
  } finally {
    loading.value = false
  }
}

const selectStore = (name: string) => {
  appStore.setCurrentStore(name)
}

const goToDocs = (name: string) => {
  appStore.setCurrentStore(name)
  router.push('/documents')
}

const goToSearch = (name: string) => {
  appStore.setCurrentStore(name)
  router.push('/search-debug')
}

const handleStoreAction = async (command: string, storeName: string) => {
  if (command === 'documents') {
    goToDocs(storeName)
  } else if (command === 'search') {
    goToSearch(storeName)
  } else if (command === 'refresh') {
    try {
      await api.refreshSnapshot(storeName)
      ElMessage.success('已落盘至快照')
    } catch (err: any) {
      ElMessage.error(`落盘失败: ${err.message}`)
    }
  } else if (command === 'reload') {
    try {
      await api.reloadStore(storeName)
      ElMessage.success('快照已重载')
      fetchStores()
    } catch (err: any) {
      ElMessage.error(`重载失败: ${err.message}`)
    }
  } else if (command === 'drop') {
    try {
      await ElMessageBox.confirm(`确定删除向量库 [${storeName}] 吗？`, '删除确认', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      })
      await api.dropStore(storeName)
      ElMessage.success('已删除')
      await fetchStores()
      if (appStore.currentStore === storeName) {
        appStore.setCurrentStore(storeDetails.value[0]?.storeName || '')
      }
    } catch (err: any) {
      // cancel
    }
  }
}

const submitCreateStore = async () => {
  if (!createForm.value.storeName.trim()) {
    ElMessage.warning('请输入名称')
    return
  }

  creating.value = true
  try {
    const fields = indexedFieldsStr.value
      .split(',')
      .map(s => s.trim())
      .filter(Boolean)

    await api.createStore(createForm.value.storeName.trim(), {
      ...createForm.value,
      indexedMetadataFields: fields
    })
    ElMessage.success('创建成功')
    showCreateDialog.value = false
    createForm.value.storeName = ''
    indexedFieldsStr.value = ''
    await fetchStores()
    appStore.setCurrentStore(createForm.value.storeName)
  } catch (err: any) {
    ElMessage.error(err.message || '创建失败')
  } finally {
    creating.value = false
  }
}

onMounted(() => {
  fetchStores()
})
</script>

<style scoped>
.stores-view-container {
  padding: 16px 24px;
  max-width: 1200px;
  margin: 0 auto;
}

.view-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #f8fafc;
  margin: 0;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.offline-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.2);
  padding: 8px 14px;
  border-radius: 6px;
  font-size: 13px;
  color: #f87171;
  margin-bottom: 16px;
}

.warn-icon {
  font-size: 16px;
}

.stores-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
}

.store-card {
  background: #1e293b;
  border: 1px solid #334155;
  border-radius: 8px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  cursor: pointer;
  transition: all 0.15s;
}

.store-card:hover {
  border-color: #38bdf8;
}

.store-card.is-active {
  border-color: #38bdf8;
  background: #1e293b;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.store-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.store-name {
  font-size: 14px;
  font-weight: 600;
  color: #f8fafc;
}

.active-badge {
  font-size: 10px;
  background: rgba(56, 189, 248, 0.15);
  color: #38bdf8;
  padding: 1px 5px;
  border-radius: 3px;
}

.more-btn {
  color: #94a3b8;
}

.card-meta-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 6px;
  background: #0f172a;
  padding: 8px 10px;
  border-radius: 6px;
}

.meta-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.meta-lbl {
  font-size: 10px;
  color: #64748b;
}

.meta-val {
  font-size: 12px;
  color: #cbd5e1;
}

.num-highlight {
  color: #38bdf8;
  font-weight: 600;
}

.card-footer {
  display: flex;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px solid #334155;
}

.form-row {
  display: flex;
  gap: 12px;
}

.danger-item {
  color: #ef4444 !important;
}
</style>
