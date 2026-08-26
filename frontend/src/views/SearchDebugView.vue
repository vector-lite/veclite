<template>
  <div class="search-debug-container">
    <div class="view-header">
      <h2 class="page-title">查询调试</h2>
      <el-tag size="small" type="primary" class="font-mono">
        {{ appStore.currentStore || '未选择库' }} ({{ appStore.currentStats?.dimension || 0 }} 维)
      </el-tag>
    </div>

    <!-- Main Workspace Layout -->
    <div class="workspace-layout">
      <!-- Left: Query Controls -->
      <div class="query-panel">
        <div class="panel-header">
          <span class="panel-title">检索配置</span>
          <el-radio-group v-model="searchMode" size="small">
            <el-radio-button label="VECTOR">向量检索</el-radio-button>
            <el-radio-button label="TEXT">文本检索</el-radio-button>
            <el-radio-button label="HYBRID">混合检索</el-radio-button>
          </el-radio-group>
        </div>

        <el-form label-position="top" class="query-form" size="small">
          <!-- Text Mode Input -->
          <el-form-item v-if="searchMode === 'TEXT' || searchMode === 'HYBRID'" label="检索文本">
            <el-input
              v-model="queryText"
              type="textarea"
              :rows="2"
              placeholder="输入待检索文本（由后台 Embedding 模型向量化）..."
            />
          </el-form-item>

          <!-- Vector Mode Input -->
          <el-form-item v-if="searchMode === 'VECTOR' || searchMode === 'HYBRID'" label="查询向量">
            <div class="vector-tools">
              <span class="field-hint">{{ currentDim }} 维浮点数组</span>
              <el-button size="small" link type="primary" @click="generateRandomQueryVector">
                随机向量
              </el-button>
            </div>
            <el-input
              v-model="queryVectorStr"
              type="textarea"
              :rows="3"
              placeholder="[0.123, -0.456, ...]"
              class="font-mono"
            />
          </el-form-item>

          <!-- Top-K & Threshold -->
          <div class="form-row-sliders">
            <el-form-item label="Top-K 数量">
              <div class="slider-box">
                <el-slider v-model="topK" :min="1" :max="50" :step="1" />
                <span class="slider-val font-mono">{{ topK }}</span>
              </div>
            </el-form-item>

            <el-form-item label="相似度阈值">
              <div class="slider-box">
                <el-slider v-model="threshold" :min="0" :max="1" :step="0.05" />
                <span class="slider-val font-mono">{{ threshold.toFixed(2) }}</span>
              </div>
            </el-form-item>
          </div>

          <!-- Metadata Filter -->
          <el-form-item label="元数据过滤">
            <FilterBuilder v-model="filterExpression" />
          </el-form-item>

          <!-- Action -->
          <div class="submit-action">
            <el-button
              type="primary"
              class="search-btn"
              :loading="searching"
              @click="executeSearch"
            >
              执行检索
            </el-button>
          </div>
        </el-form>
      </div>

      <!-- Right: Debug Results -->
      <div class="results-panel">
        <!-- Stats Summary -->
        <div class="stats-overview-grid">
          <div class="stat-box">
            <span class="stat-label">耗时</span>
            <span class="stat-number font-mono">{{ searchLatency }} <small>ms</small></span>
          </div>
          <div class="stat-box">
            <span class="stat-label">命中条数</span>
            <span class="stat-number font-mono">{{ searchResults.length }}</span>
          </div>
          <div class="stat-box">
            <span class="stat-label">最高得分</span>
            <span class="stat-number font-mono highlight-score">
              {{ maxScore !== null ? maxScore.toFixed(4) : '-' }}
            </span>
          </div>
          <div class="stat-box">
            <span class="stat-label">最低得分</span>
            <span class="stat-number font-mono">
              {{ minScore !== null ? minScore.toFixed(4) : '-' }}
            </span>
          </div>
        </div>

        <!-- Debug Tabs -->
        <div class="debug-tabs-wrapper">
          <el-tabs v-model="activeDebugTab">
            <!-- Results Tab -->
            <el-tab-pane label="召回结果" name="results">
              <div v-if="searchResults.length === 0" class="no-results-state">
                <el-empty :description="searchedOnce ? '未检索到符合条件的文档' : '请在左侧配置后点击检索'" />
              </div>

              <div v-else class="results-list">
                <!-- Resonance Heatmap Comparison -->
                <div v-if="selectedResultVector" class="selected-heatmap-card">
                  <div class="heatmap-card-header">
                    <span class="highlight-target-text">
                      TOP #{{ selectedResultRank }} (ID: {{ selectedResultId }}) 维度共鸣对比
                    </span>
                    <el-button link type="primary" size="small" @click="selectedResultVector = null">关闭</el-button>
                  </div>
                  <VectorHeatmap
                    :query-vector="parsedQueryVector"
                    :target-vector="selectedResultVector"
                  />
                </div>

                <!-- Result Cards -->
                <div
                  v-for="(item, index) in searchResults"
                  :key="item.id"
                  class="result-item-card"
                  :class="{ 'is-focused': selectedResultId === item.id }"
                >
                  <div class="result-card-top">
                    <div class="result-info-left">
                      <span class="rank-tag font-mono">#{{ index + 1 }}</span>
                      <span class="doc-id font-mono">{{ item.id }}</span>
                      <el-icon class="copy-btn" @click="copyText(item.id)"><CopyDocument /></el-icon>
                    </div>

                    <div class="gauge-wrapper">
                      <SimilarityGauge
                        :score="item.score"
                        :rank="index + 1"
                        :metric="appStore.currentStats?.metric"
                      />
                    </div>
                  </div>

                  <!-- Text -->
                  <div v-if="item.text || item.document?.text" class="result-text-box">
                    <span class="text-content">{{ item.text || item.document?.text }}</span>
                  </div>

                  <!-- Meta -->
                  <div
                    v-if="(item.metadata || item.document?.metadata) && Object.keys(item.metadata || item.document?.metadata || {}).length > 0"
                    class="result-meta-row"
                  >
                    <el-tag
                      v-for="(val, key) in (item.metadata || item.document?.metadata)"
                      :key="key"
                      size="small"
                      class="meta-chip font-mono"
                    >
                      <span>{{ key }}:</span>
                      <span>{{ String(val) }}</span>
                    </el-tag>
                  </div>

                  <!-- Vector with Masking -->
                  <div class="result-vector-section">
                    <div class="vector-label-row">
                      <span class="sec-label">向量数据</span>
                      <el-button
                        v-if="item.vector || item.document?.vector"
                        link
                        type="success"
                        size="small"
                        @click="inspectResonance(item, index + 1)"
                      >
                        维度特征对比
                      </el-button>
                    </div>
                    <VectorViewer
                      :vector="item.vector || item.document?.vector"
                      :dimension="appStore.currentStats?.dimension"
                    />
                  </div>
                </div>
              </div>
            </el-tab-pane>

            <!-- Score Decay Tab -->
            <el-tab-pane label="得分衰减" name="chart">
              <div v-if="searchResults.length === 0" class="no-results-state">
                <el-empty description="暂无数据" />
              </div>
              <div v-else class="decay-bars-list">
                <div
                  v-for="(item, idx) in searchResults"
                  :key="item.id"
                  class="decay-bar-row"
                >
                  <div class="decay-id-col font-mono">
                    <span class="decay-rank">#{{ idx + 1 }}</span>
                    <span class="decay-id" :title="item.id">{{ item.id }}</span>
                  </div>
                  <div class="decay-track-col">
                    <div
                      class="decay-fill"
                      :style="{
                        width: `${Math.max(5, item.score * 100)}%`,
                        background: item.score > 0.8 ? '#10b981' : item.score > 0.6 ? '#38bdf8' : '#f59e0b'
                      }"
                    ></div>
                  </div>
                  <span class="decay-score font-mono">{{ item.score.toFixed(4) }}</span>
                </div>
              </div>
            </el-tab-pane>

            <!-- Inspector Tab -->
            <el-tab-pane label="报文与 cURL" name="payload">
              <div class="inspector-section">
                <div class="code-snippet-box">
                  <div class="snippet-header">
                    <span class="snippet-title">cURL</span>
                    <el-button size="small" type="primary" link @click="copyText(curlSnippet)">复制</el-button>
                  </div>
                  <pre class="font-mono code-block">{{ curlSnippet }}</pre>
                </div>

                <div class="code-snippet-box">
                  <div class="snippet-header">
                    <span class="snippet-title">Request JSON</span>
                    <el-button size="small" link @click="copyText(JSON.stringify(lastRequestPayload, null, 2))">复制</el-button>
                  </div>
                  <pre class="font-mono code-block">{{ JSON.stringify(lastRequestPayload, null, 2) }}</pre>
                </div>
              </div>
            </el-tab-pane>
          </el-tabs>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAppStore } from '../stores/appStore'
import api from '../api/veclite'
import type {
  SearchMode,
  VectorSearchRequest,
  VectorSearchResult,
  FilterExpression
} from '../types'
import VectorViewer from '../components/VectorViewer.vue'
import VectorHeatmap from '../components/VectorHeatmap.vue'
import SimilarityGauge from '../components/SimilarityGauge.vue'
import FilterBuilder from '../components/FilterBuilder.vue'
import { ElMessage } from 'element-plus'
import { CopyDocument } from '@element-plus/icons-vue'

const appStore = useAppStore()

const searchMode = ref<SearchMode>('VECTOR')
const queryText = ref('')
const queryVectorStr = ref('')
const topK = ref(10)
const threshold = ref(0.0)
const filterExpression = ref<FilterExpression | null>(null)

const searching = ref(false)
const searchedOnce = ref(false)
const searchResults = ref<VectorSearchResult[]>([])
const searchLatency = ref(0)
const activeDebugTab = ref('results')

const selectedResultId = ref<string | null>(null)
const selectedResultRank = ref<number>(1)
const selectedResultVector = ref<number[] | null>(null)

const lastRequestPayload = ref<any>({})

const currentDim = computed(() => appStore.currentStats?.dimension || 128)

const parsedQueryVector = computed<number[]>(() => {
  if (!queryVectorStr.value.trim()) return []
  try {
    const arr = JSON.parse(queryVectorStr.value)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
})

const maxScore = computed(() => {
  if (searchResults.value.length === 0) return null
  return Math.max(...searchResults.value.map(r => r.score))
})

const minScore = computed(() => {
  if (searchResults.value.length === 0) return null
  return Math.min(...searchResults.value.map(r => r.score))
})

const generateRandomQueryVector = () => {
  const dim = currentDim.value
  const vec: number[] = []
  for (let i = 0; i < dim; i++) {
    vec.push(Number(((Math.random() * 2 - 1) * 0.4).toFixed(4)))
  }
  queryVectorStr.value = JSON.stringify(vec)
}

const executeSearch = async () => {
  if (!appStore.currentStore) {
    ElMessage.warning('请先选择向量库')
    return
  }

  let vec: number[] | undefined = undefined
  if (searchMode.value === 'VECTOR' || searchMode.value === 'HYBRID') {
    if (!queryVectorStr.value.trim()) {
      ElMessage.warning('请输入查询向量')
      return
    }
    try {
      vec = JSON.parse(queryVectorStr.value)
      if (!Array.isArray(vec)) throw new Error('必须为数组')
    } catch (err: any) {
      ElMessage.error(`向量格式错误: ${err.message}`)
      return
    }
  }

  if (searchMode.value === 'TEXT' && !queryText.value.trim()) {
    ElMessage.warning('请输入检索文本')
    return
  }

  searching.value = true
  selectedResultVector.value = null
  selectedResultId.value = null

  const req: VectorSearchRequest = {
    storeName: appStore.currentStore,
    mode: searchMode.value,
    queryText: queryText.value.trim() || undefined,
    queryVector: vec,
    topK: topK.value,
    threshold: threshold.value,
    filter: filterExpression.value || undefined,
    includeVector: true
  }

  lastRequestPayload.value = req
  const startTime = performance.now()

  try {
    let results: VectorSearchResult[] = []
    if (searchMode.value === 'VECTOR') {
      results = await api.searchByVector(appStore.currentStore, req)
    } else if (searchMode.value === 'TEXT') {
      results = await api.searchByText(appStore.currentStore, req)
    } else {
      results = await api.hybridSearch(appStore.currentStore, req)
    }

    searchLatency.value = Math.round(performance.now() - startTime)
    searchResults.value = results
    searchedOnce.value = true
  } catch (err: any) {
    searchLatency.value = Math.round(performance.now() - startTime)
    ElMessage.error(err.message || '检索失败')
  } finally {
    searching.value = false
  }
}

const inspectResonance = (item: VectorSearchResult, rank: number) => {
  const vec = item.vector || item.document?.vector
  if (!vec) return
  selectedResultId.value = item.id
  selectedResultRank.value = rank
  selectedResultVector.value = vec
}

const copyText = (text: string) => {
  navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}

const curlSnippet = computed(() => {
  const baseUrl = api.getBaseUrl()
  const endpoint = searchMode.value === 'TEXT' ? 'text' : searchMode.value === 'HYBRID' ? 'hybrid' : 'vector'
  return `curl -X POST "${baseUrl}/stores/${appStore.currentStore}/search/${endpoint}" \\
  -H "Content-Type: application/json" \\
  -d '${JSON.stringify(lastRequestPayload.value)}'`
})

onMounted(() => {
  if (currentDim.value > 0 && !queryVectorStr.value) {
    generateRandomQueryVector()
  }
})
</script>

<style scoped>
.search-debug-container {
  padding: 16px 24px;
  max-width: 1400px;
  margin: 0 auto;
}

.view-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #f8fafc;
  margin: 0;
}

.workspace-layout {
  display: grid;
  grid-template-columns: 380px 1fr;
  gap: 16px;
  align-items: start;
}

.query-panel {
  background: #1e293b;
  border: 1px solid #334155;
  border-radius: 8px;
  padding: 16px;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  border-bottom: 1px solid #334155;
  padding-bottom: 10px;
}

.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #f8fafc;
}

.vector-tools {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 2px;
}

.field-hint {
  font-size: 11px;
  color: #64748b;
}

.form-row-sliders {
  display: flex;
  flex-direction: column;
  gap: 6px;
  background: #0f172a;
  border: 1px solid #1e293b;
  padding: 10px 12px;
  border-radius: 6px;
  margin-bottom: 12px;
}

.slider-box {
  display: flex;
  align-items: center;
  gap: 12px;
}

.slider-box .el-slider {
  flex: 1;
}

.slider-val {
  font-size: 12px;
  color: #38bdf8;
  width: 35px;
  text-align: right;
}

.submit-action {
  margin-top: 14px;
}

.search-btn {
  width: 100%;
  height: 38px;
  font-weight: 600;
}

.results-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.stats-overview-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}

.stat-box {
  background: #1e293b;
  border: 1px solid #334155;
  border-radius: 8px;
  padding: 10px 14px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat-label {
  font-size: 11px;
  color: #94a3b8;
}

.stat-number {
  font-size: 16px;
  font-weight: 600;
  color: #f8fafc;
}

.highlight-score {
  color: #38bdf8;
}

.debug-tabs-wrapper {
  background: #1e293b;
  border: 1px solid #334155;
  border-radius: 8px;
  padding: 12px 16px;
}

.results-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 8px;
}

.selected-heatmap-card {
  background: #0f172a;
  border: 1px solid #38bdf8;
  border-radius: 8px;
  padding: 10px;
}

.heatmap-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.highlight-target-text {
  font-size: 12px;
  color: #38bdf8;
}

.result-item-card {
  background: #0f172a;
  border: 1px solid #334155;
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.result-card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.result-info-left {
  display: flex;
  align-items: center;
  gap: 6px;
}

.rank-tag {
  background: #3b82f6;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 3px;
}

.doc-id {
  font-size: 12px;
  font-weight: 600;
  color: #f8fafc;
}

.copy-btn {
  font-size: 12px;
  color: #94a3b8;
  cursor: pointer;
}

.copy-btn:hover {
  color: #fff;
}

.gauge-wrapper {
  width: 200px;
}

.result-text-box {
  background: rgba(30, 41, 59, 0.4);
  border: 1px solid #1e293b;
  border-radius: 4px;
  padding: 6px 10px;
  font-size: 12px;
  color: #cbd5e1;
}

.result-meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.meta-chip {
  background: #1e293b;
  border-color: #334155;
  font-size: 11px;
}

.result-vector-section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.vector-label-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.sec-label {
  font-size: 11px;
  color: #64748b;
}

.decay-bars-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 0;
}

.decay-bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.decay-id-col {
  width: 140px;
  display: flex;
  gap: 6px;
  font-size: 12px;
}

.decay-rank {
  color: #38bdf8;
  font-weight: 600;
}

.decay-id {
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.decay-track-col {
  flex: 1;
  height: 10px;
  background: #0f172a;
  border-radius: 3px;
  overflow: hidden;
  border: 1px solid #334155;
}

.decay-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.3s;
}

.decay-score {
  font-size: 12px;
  color: #f8fafc;
  width: 45px;
  text-align: right;
}

.inspector-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 6px 0;
}

.code-snippet-box {
  background: #0f172a;
  border: 1px solid #334155;
  border-radius: 6px;
  padding: 10px 12px;
}

.snippet-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.snippet-title {
  font-size: 12px;
  font-weight: 600;
  color: #cbd5e1;
}

.code-block {
  background: #020617;
  border: 1px solid #1e293b;
  border-radius: 4px;
  padding: 8px;
  font-size: 11px;
  color: #38bdf8;
  overflow-x: auto;
  margin: 0;
  max-height: 200px;
}
</style>
