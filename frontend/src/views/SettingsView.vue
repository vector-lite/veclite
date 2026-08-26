<template>
  <div class="settings-view-container">
    <div class="view-header">
      <h2 class="page-title">系统配置</h2>
    </div>

    <div class="settings-grid">
      <!-- Connection Config -->
      <div class="settings-card">
        <div class="card-title">后端服务连接</div>
        <div class="card-body">
          <el-form label-position="top" size="small">
            <el-form-item label="API Base URL">
              <el-input v-model="targetUrl" placeholder="/veclite/api/v1" />
            </el-form-item>
          </el-form>

          <div class="conn-test-row">
            <div class="status-summary">
              <span class="status-dot" :class="{ 'is-online': appStore.isConnected }"></span>
              <span class="status-msg font-mono">
                {{ appStore.isConnected ? `已连接 (${appStore.latency}ms)` : '未连接' }}
              </span>
            </div>
            <el-button size="small" type="primary" :loading="testing" @click="testAndSave">
              保存并测试
            </el-button>
          </div>
        </div>
      </div>

      <!-- Demo Data Generator -->
      <div class="settings-card">
        <div class="card-title">演示数据集</div>
        <div class="card-body">
          <p class="card-desc">自动创建 <code>demo_knowledge_base</code> (128维) 库并写入 20 条模拟测试数据。</p>
          <el-button size="small" type="success" :loading="generatingDemo" @click="generateDemoData">
            生成 20 条演示数据
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/appStore'
import api from '../api/veclite'
import type { VectorDocument } from '../types'
import { ElMessage } from 'element-plus'

const router = useRouter()
const appStore = useAppStore()

const targetUrl = ref(appStore.baseUrl)
const testing = ref(false)
const generatingDemo = ref(false)

const testAndSave = async () => {
  testing.value = true
  appStore.setBaseUrl(targetUrl.value)
  await appStore.checkHealth()
  testing.value = false

  if (appStore.isConnected) {
    ElMessage.success('连接成功')
  } else {
    ElMessage.error(`连接失败: ${appStore.connectionError || '请检查后端'}`)
  }
}

const generateDemoData = async () => {
  generatingDemo.value = true
  const storeName = 'demo_knowledge_base'
  const dim = 128

  try {
    await api.createStore(storeName, {
      storeName,
      dimension: dim,
      metric: 'COSINE',
      quantization: 'NONE',
      maxCapacity: 50000,
      indexedMetadataFields: ['category', 'level']
    })

    const sampleDocs: VectorDocument[] = []
    const topics = [
      { text: '大语言模型在向量数据库中的检索增强生成 (RAG) 核心原理', cat: 'AI' },
      { text: 'VecLite 嵌入式向量检索引擎的高并发与低延迟优化报告', cat: 'Database' },
      { text: 'SQ8 标量量化算法在百万维高维浮点向量中的降维与精度损失评估', cat: 'Math' },
      { text: '现代微服务架构下的 Spring Boot 自动装配实践', cat: 'Backend' },
      { text: '分布式倒排索引与 RoaringBitmap 在元数据过滤中的实现方案', cat: 'Search' }
    ]

    for (let i = 1; i <= 20; i++) {
      const topic = topics[(i - 1) % topics.length]
      const vec: number[] = []
      let normSum = 0
      for (let d = 0; d < dim; d++) {
        const v = (Math.random() * 2 - 1) * (1 + ((i + d) % 5) * 0.2)
        vec.push(v)
        normSum += v * v
      }
      const norm = Math.sqrt(normSum)
      const normalizedVec = vec.map(v => Number((v / norm).toFixed(4)))

      sampleDocs.push({
        id: `demo_${i.toString().padStart(3, '0')}`,
        text: `${topic.text} (序号 ${i})`,
        vector: normalizedVec,
        metadata: {
          category: topic.cat,
          level: (i % 3) + 1
        }
      })
    }

    await api.upsertBatch(storeName, sampleDocs)
    ElMessage.success(`已创建 [${storeName}] 并写入 20 条测试数据`)
    await appStore.refreshStoreList()
    appStore.setCurrentStore(storeName)
    router.push('/documents')
  } catch (err: any) {
    ElMessage.error(`生成失败: ${err.message || err}`)
  } finally {
    generatingDemo.value = false
  }
}
</script>

<style scoped>
.settings-view-container {
  padding: 16px 24px;
  max-width: 900px;
  margin: 0 auto;
}

.view-header {
  margin-bottom: 16px;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #f8fafc;
  margin: 0;
}

.settings-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.settings-card {
  background: #1e293b;
  border: 1px solid #334155;
  border-radius: 8px;
  padding: 16px;
}

.card-title {
  font-size: 13px;
  font-weight: 600;
  color: #f8fafc;
  margin-bottom: 12px;
  border-bottom: 1px solid #334155;
  padding-bottom: 8px;
}

.card-desc {
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.4;
  margin-bottom: 12px;
}

.conn-test-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #1e293b;
}

.status-summary {
  display: flex;
  align-items: center;
  gap: 6px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ef4444;
}

.status-dot.is-online {
  background: #10b981;
}

.status-msg {
  font-size: 12px;
  color: #cbd5e1;
}
</style>
