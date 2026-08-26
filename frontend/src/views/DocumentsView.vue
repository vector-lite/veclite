<template>
  <div class="docs-view-container">
    <!-- Header Controls -->
    <div class="view-header">
      <div class="header-left">
        <span class="page-title font-mono">{{ appStore.currentStore || '未选择向量库' }}</span>
        <el-tag v-if="appStore.currentStats" size="small" type="info">
          {{ appStore.currentStats.dimension }} 维 · {{ appStore.currentStats.docCount }} 条
        </el-tag>
      </div>

      <div class="header-actions">
        <el-button type="primary" size="small" :icon="Plus" @click="openCreateDialog">
          插入文档
        </el-button>
        <el-button size="small" :icon="Upload" plain @click="showBatchDialog = true">
          批量导入
        </el-button>
        <el-button type="danger" size="small" :icon="Delete" plain :disabled="selectedIds.length === 0" @click="handleBatchDelete">
          批量删除 ({{ selectedIds.length }})
        </el-button>
        <el-button size="small" :icon="Filter" plain @click="showFilterDeleteDialog = true">
          条件删除
        </el-button>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="fetchDocuments">
          刷新
        </el-button>
      </div>
    </div>

    <!-- Search & Filter Toolbar -->
    <div class="toolbar-box">
      <div class="search-inputs">
        <el-input
          v-model="searchQuery"
          placeholder="按 ID 或 文本搜索..."
          clearable
          size="small"
          style="width: 240px;"
          :prefix-icon="Search"
        />
        <el-checkbox v-model="includeVectorInQuery" size="small" @change="fetchDocuments">
          加载向量值
        </el-checkbox>
      </div>
      <span class="table-stat-text">已加载 {{ filteredDocuments.length }} 条</span>
    </div>

    <!-- Documents Table -->
    <div class="table-wrapper">
      <el-table
        v-loading="loading"
        :data="paginatedDocuments"
        style="width: 100%"
        size="small"
        row-key="id"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="40" align="center" />

        <!-- ID Column -->
        <el-table-column label="ID" width="160">
          <template #default="{ row }">
            <div class="id-cell">
              <span class="id-text font-mono" :title="row.id">{{ row.id }}</span>
              <el-icon class="copy-icon" @click="copyText(row.id)"><CopyDocument /></el-icon>
            </div>
          </template>
        </el-table-column>

        <!-- Text Column -->
        <el-table-column label="文本内容" min-width="220">
          <template #default="{ row }">
            <span class="text-preview">{{ row.text || '-' }}</span>
          </template>
        </el-table-column>

        <!-- Metadata Column -->
        <el-table-column label="元数据" min-width="180">
          <template #default="{ row }">
            <div v-if="row.metadata && Object.keys(row.metadata).length > 0" class="meta-tags-wrapper">
              <el-tag
                v-for="(val, key) in row.metadata"
                :key="key"
                size="small"
                class="meta-tag font-mono"
              >
                <span class="meta-k">{{ key }}:</span>
                <span>{{ String(val) }}</span>
              </el-tag>
            </div>
            <span v-else class="empty-meta-text">-</span>
          </template>
        </el-table-column>

        <!-- Vector Column with Masking Support -->
        <el-table-column label="向量数据" width="300">
          <template #default="{ row }">
            <VectorViewer
              :vector="row.vector"
              :dimension="appStore.currentStats?.dimension"
            />
          </template>
        </el-table-column>

        <!-- Action Column -->
        <el-table-column label="操作" width="100" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEditDialog(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="handleDeleteOne(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- Pagination -->
      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          :total="totalFilteredCount"
          @size-change="onPageChange"
          @current-change="onPageChange"
        />
      </div>
    </div>

    <!-- Insert / Edit Single Document Modal -->
    <el-dialog
      v-model="showDocDialog"
      :title="isEditing ? '编辑文档' : '插入文档'"
      width="560px"
      append-to-body
    >
      <el-form label-position="top">
        <el-form-item label="文档 ID" required>
          <div class="id-input-row">
            <el-input v-model="docForm.id" placeholder="如: doc_01" class="font-mono" :disabled="isEditing" />
            <el-button v-if="!isEditing" size="small" @click="generateRandomId">随机 ID</el-button>
          </div>
        </el-form-item>

        <el-form-item label="文本内容">
          <el-input
            v-model="docForm.text"
            type="textarea"
            :rows="2"
            placeholder="输入文档文本..."
          />
        </el-form-item>

        <!-- Vector Input Section -->
        <el-form-item label="向量值 (JSON 浮点数组)">
          <div class="vector-input-header">
            <span class="field-hint">期望 {{ appStore.currentStats?.dimension || 0 }} 维</span>
            <el-button size="small" type="primary" link @click="generateRandomVector">
              生成随机向量
            </el-button>
          </div>
          <el-input
            v-model="vectorInputText"
            type="textarea"
            :rows="3"
            placeholder="[0.123, -0.456, ...]"
            class="font-mono"
          />
          <span v-if="vectorParsedCount > 0" class="vector-count-hint">
            已解析: {{ vectorParsedCount }} 维
          </span>
        </el-form-item>

        <!-- Metadata Section -->
        <el-form-item label="元数据">
          <MetadataEditor v-model="docForm.metadata" />
        </el-form-item>
      </el-form>

      <template #footer>
        <span class="dialog-footer">
          <el-button @click="showDocDialog = false">取消</el-button>
          <el-button type="primary" :loading="savingDoc" @click="submitSaveDoc">保存</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- Batch Import Modal -->
    <el-dialog v-model="showBatchDialog" title="批量导入文档" width="600px" append-to-body>
      <el-input
        v-model="batchJsonText"
        type="textarea"
        :rows="8"
        placeholder='[{"id": "doc_1", "text": "...", "vector": [...]}]'
        class="font-mono"
      />
      <div class="batch-helpers">
        <el-button size="small" link type="primary" @click="generateBatchTemplate">
          生成 10 条测试模版
        </el-button>
      </div>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="showBatchDialog = false">取消</el-button>
          <el-button type="primary" :loading="importing" @click="submitBatchImport">导入</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- Delete By Filter Modal -->
    <el-dialog v-model="showFilterDeleteDialog" title="按元数据条件删除" width="480px" append-to-body>
      <FilterBuilder v-model="filterToDelete" />
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="showFilterDeleteDialog = false">取消</el-button>
          <el-button type="danger" :disabled="!filterToDelete" @click="submitDeleteByFilter">确认删除</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useAppStore } from '../stores/appStore'
import api from '../api/veclite'
import type { VectorDocument, FilterExpression } from '../types'
import VectorViewer from '../components/VectorViewer.vue'
import MetadataEditor from '../components/MetadataEditor.vue'
import FilterBuilder from '../components/FilterBuilder.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Upload, Delete, Filter, Refresh, Search, CopyDocument } from '@element-plus/icons-vue'

const appStore = useAppStore()

const loading = ref(false)
const documents = ref<VectorDocument[]>([])
const searchQuery = ref('')
const includeVectorInQuery = ref(true)
const selectedIds = ref<string[]>([])

// Pagination
const currentPage = ref(1)
const pageSize = ref(20)

// Modal states
const showDocDialog = ref(false)
const isEditing = ref(false)
const savingDoc = ref(false)
const docForm = ref<VectorDocument>({ id: '', text: '', metadata: {} })
const vectorInputText = ref('')

const showBatchDialog = ref(false)
const batchJsonText = ref('')
const importing = ref(false)

const showFilterDeleteDialog = ref(false)
const filterToDelete = ref<FilterExpression | null>(null)

const fetchDocuments = async () => {
  if (!appStore.currentStore) return
  loading.value = true
  try {
    const list = await api.listDocuments(
      appStore.currentStore,
      currentPage.value,
      pageSize.value,
      includeVectorInQuery.value
    )
    documents.value = list
    await appStore.fetchCurrentStats()
  } catch (err: any) {
    ElMessage.error(err.message || '获取文档失败')
  } finally {
    loading.value = false
  }
}

watch(() => appStore.currentStore, () => {
  currentPage.value = 1
  fetchDocuments()
})

const filteredDocuments = computed(() => {
  if (!searchQuery.value.trim()) return documents.value
  const q = searchQuery.value.toLowerCase()
  return documents.value.filter(doc => {
    const matchId = doc.id?.toLowerCase().includes(q)
    const matchText = doc.text?.toLowerCase().includes(q)
    const matchMeta = doc.metadata ? JSON.stringify(doc.metadata).toLowerCase().includes(q) : false
    return matchId || matchText || matchMeta
  })
})

const totalFilteredCount = computed(() => {
  return appStore.currentStats?.docCount || filteredDocuments.value.length
})

const paginatedDocuments = computed(() => filteredDocuments.value)

const onPageChange = () => {
  fetchDocuments()
}

const handleSelectionChange = (rows: VectorDocument[]) => {
  selectedIds.value = rows.map(r => r.id)
}

const copyText = (text: string) => {
  navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}

const vectorParsedCount = computed(() => {
  if (!vectorInputText.value.trim()) return 0
  try {
    const arr = JSON.parse(vectorInputText.value)
    return Array.isArray(arr) ? arr.length : 0
  } catch {
    return 0
  }
})

const generateRandomId = () => {
  docForm.value.id = `doc_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`
}

const generateRandomVector = () => {
  const dim = appStore.currentStats?.dimension || 128
  const vec: number[] = []
  for (let i = 0; i < dim; i++) {
    vec.push(Number(((Math.random() * 2 - 1) * 0.5).toFixed(4)))
  }
  vectorInputText.value = JSON.stringify(vec)
}

const openCreateDialog = () => {
  isEditing.value = false
  generateRandomId()
  docForm.value.text = ''
  docForm.value.metadata = {}
  vectorInputText.value = ''
  showDocDialog.value = true
}

const openEditDialog = (row: VectorDocument) => {
  isEditing.value = true
  docForm.value = {
    id: row.id,
    text: row.text,
    metadata: row.metadata ? JSON.parse(JSON.stringify(row.metadata)) : {}
  }
  vectorInputText.value = row.vector ? JSON.stringify(row.vector) : ''
  showDocDialog.value = true
}

const submitSaveDoc = async () => {
  if (!docForm.value.id.trim()) {
    ElMessage.warning('请输入文档 ID')
    return
  }

  let parsedVec: number[] | undefined = undefined
  if (vectorInputText.value.trim()) {
    try {
      parsedVec = JSON.parse(vectorInputText.value)
      if (!Array.isArray(parsedVec)) throw new Error('向量必须为数组')
    } catch (err: any) {
      ElMessage.error(`向量格式错误: ${err.message}`)
      return
    }
  }

  savingDoc.value = true
  try {
    const payload: VectorDocument = {
      id: docForm.value.id.trim(),
      text: docForm.value.text,
      vector: parsedVec,
      metadata: docForm.value.metadata
    }
    await api.upsertDocument(appStore.currentStore, payload)
    ElMessage.success('保存成功')
    showDocDialog.value = false
    fetchDocuments()
  } catch (err: any) {
    ElMessage.error(err.message || '保存失败')
  } finally {
    savingDoc.value = false
  }
}

const handleDeleteOne = async (id: string) => {
  try {
    await ElMessageBox.confirm(`确定删除文档 [${id}] 吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
    await api.deleteByIds(appStore.currentStore, [id])
    ElMessage.success('已删除')
    fetchDocuments()
  } catch (err) {
    // cancel
  }
}

const handleBatchDelete = async () => {
  if (selectedIds.value.length === 0) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${selectedIds.value.length} 条文档吗？`, '批量删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
    const res = await api.deleteByIds(appStore.currentStore, selectedIds.value)
    ElMessage.success(`已删除 ${res.deletedCount || selectedIds.value.length} 条文档`)
    selectedIds.value = []
    fetchDocuments()
  } catch (err) {
    // cancel
  }
}

const submitDeleteByFilter = async () => {
  if (!filterToDelete.value) return
  try {
    const res = await api.deleteByFilter(appStore.currentStore, filterToDelete.value)
    ElMessage.success(`已删除 ${res.deletedCount} 条记录`)
    showFilterDeleteDialog.value = false
    fetchDocuments()
  } catch (err: any) {
    ElMessage.error(err.message || '删除失败')
  }
}

const generateBatchTemplate = () => {
  const dim = appStore.currentStats?.dimension || 128
  const list: VectorDocument[] = []
  const categories = ['science', 'tech', 'finance', 'education']

  for (let i = 1; i <= 10; i++) {
    const vec: number[] = []
    for (let d = 0; d < dim; d++) {
      vec.push(Number(((Math.random() * 2 - 1) * 0.3).toFixed(4)))
    }
    list.push({
      id: `doc_${i.toString().padStart(3, '0')}`,
      text: `测试样本 ${i}`,
      vector: vec,
      metadata: { category: categories[i % categories.length] }
    })
  }
  batchJsonText.value = JSON.stringify(list, null, 2)
}

const submitBatchImport = async () => {
  if (!batchJsonText.value.trim()) return
  try {
    const list = JSON.parse(batchJsonText.value)
    if (!Array.isArray(list)) throw new Error('必须为数组')
    importing.value = true
    await api.upsertBatch(appStore.currentStore, list)
    ElMessage.success(`成功导入 ${list.length} 条文档`)
    showBatchDialog.value = false
    batchJsonText.value = ''
    fetchDocuments()
  } catch (err: any) {
    ElMessage.error(`导入失败: ${err.message}`)
  } finally {
    importing.value = false
  }
}

onMounted(() => {
  fetchDocuments()
})
</script>

<style scoped>
.docs-view-container {
  padding: 16px 24px;
  max-width: 1300px;
  margin: 0 auto;
}

.view-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #f8fafc;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.toolbar-box {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #1e293b;
  border: 1px solid #334155;
  border-radius: 6px;
  padding: 8px 12px;
  margin-bottom: 12px;
}

.search-inputs {
  display: flex;
  align-items: center;
  gap: 12px;
}

.table-stat-text {
  font-size: 12px;
  color: #94a3b8;
}

.table-wrapper {
  background: #1e293b;
  border: 1px solid #334155;
  border-radius: 8px;
  padding: 8px;
}

.id-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.id-text {
  font-size: 12px;
  color: #38bdf8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.copy-icon {
  font-size: 12px;
  color: #94a3b8;
  cursor: pointer;
}

.copy-icon:hover {
  color: #f8fafc;
}

.text-preview {
  font-size: 12px;
  color: #cbd5e1;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
}

.meta-tags-wrapper {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.meta-tag {
  background: #0f172a;
  border-color: #334155;
  color: #e2e8f0;
  font-size: 11px;
}

.meta-k {
  color: #94a3b8;
  margin-right: 2px;
}

.empty-meta-text {
  font-size: 12px;
  color: #64748b;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}

.id-input-row {
  display: flex;
  gap: 8px;
}

.vector-input-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.field-hint {
  font-size: 11px;
  color: #64748b;
}

.vector-count-hint {
  font-size: 11px;
  color: #34d399;
  margin-top: 2px;
  display: block;
}

.batch-helpers {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}
</style>
