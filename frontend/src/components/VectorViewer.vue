<template>
  <div class="vector-viewer-root">
    <!-- Masked Mode -->
    <div v-if="isMasked" class="vector-masked-box">
      <div class="masked-header">
        <el-tag size="small" type="info" effect="dark" class="dim-tag">
          {{ dim }} 维向量
        </el-tag>
        <span v-if="l2Norm !== null" class="norm-badge" title="L2 范数（模长）">
          ‖v‖ = {{ l2Norm.toFixed(3) }}
        </span>
      </div>

      <!-- Mini Heatstrip Thumbnail -->
      <div class="mini-heatstrip" title="向量维度特征微缩条" @click="showDetailDialog = true">
        <div
          v-for="(val, idx) in sampledVector"
          :key="idx"
          class="heat-cell"
          :style="{ backgroundColor: getHeatColor(val) }"
        ></div>
      </div>

      <div class="masked-footer">
        <span class="preview-text font-mono">{{ maskedPreviewText }}</span>
        <div class="masked-actions">
          <el-button link type="primary" size="small" @click="localUnmask = true">
            <el-icon><View /></el-icon>
            <span>明文</span>
          </el-button>
          <el-button link type="info" size="small" @click="copyVector">
            <el-icon><CopyDocument /></el-icon>
            <span>复制</span>
          </el-button>
          <el-button link type="warning" size="small" @click="showDetailDialog = true">
            <el-icon><DataAnalysis /></el-icon>
            <span>分析</span>
          </el-button>
        </div>
      </div>
    </div>

    <!-- Unmasked Mode -->
    <div v-else class="vector-unmasked-box">
      <div class="unmasked-header">
        <div class="left-info">
          <el-tag size="small" type="success" effect="dark" class="dim-tag">
            {{ dim }} 维 Float32
          </el-tag>
          <span v-if="l2Norm !== null" class="norm-badge">
            ‖v‖ = {{ l2Norm.toFixed(4) }}
          </span>
        </div>
        <div class="right-actions">
          <el-button link type="info" size="small" @click="copyVector">
            <el-icon><CopyDocument /></el-icon>
            <span>复制</span>
          </el-button>
          <el-button link type="primary" size="small" @click="showDetailDialog = true">
            <el-icon><DataAnalysis /></el-icon>
            <span>图表</span>
          </el-button>
          <el-button link type="info" size="small" @click="localUnmask = false">
            <el-icon><Hide /></el-icon>
            <span>屏蔽</span>
          </el-button>
        </div>
      </div>

      <div class="vector-raw-array font-mono">
        <div
          v-for="(val, idx) in vector"
          :key="idx"
          class="vector-dim-item"
          :class="{ 'is-positive': val > 0, 'is-negative': val < 0 }"
        >
          <span class="dim-index">d{{ idx }}:</span>
          <span class="dim-val">{{ typeof val === 'number' ? val.toFixed(4) : val }}</span>
        </div>
      </div>
    </div>

    <!-- Vector Detail & Chart Analysis Dialog -->
    <el-dialog v-model="showDetailDialog" :title="`高维向量特征分析 (${dim} 维)`" width="700px" append-to-body>
      <div class="vector-dialog-content">
        <div class="stats-row">
          <div class="stat-card">
            <span class="stat-title">维度 (Dimension)</span>
            <span class="stat-val">{{ dim }}</span>
          </div>
          <div class="stat-card">
            <span class="stat-title">L2 范数 (Norm)</span>
            <span class="stat-val font-mono">{{ l2Norm !== null ? l2Norm.toFixed(5) : '-' }}</span>
          </div>
          <div class="stat-card">
            <span class="stat-title">最大值 / 最小值</span>
            <span class="stat-val font-mono">{{ maxVal.toFixed(3) }} / {{ minVal.toFixed(3) }}</span>
          </div>
          <div class="stat-card">
            <span class="stat-title">均值 / 方差</span>
            <span class="stat-val font-mono">{{ meanVal.toFixed(3) }} / {{ varianceVal.toFixed(3) }}</span>
          </div>
        </div>

        <!-- Full Spectrum Heatstrip -->
        <div class="spectrum-section">
          <div class="spectrum-label">
            <span>维度数值分布热谱 (Dimension Spectrum)</span>
            <span class="spectrum-legend">
              <i class="legend-box neg"></i> 负值 [-1.0]
              <i class="legend-box zero"></i> 0.0
              <i class="legend-box pos"></i> 正值 [+1.0]
            </span>
          </div>
          <div class="full-heatstrip">
            <div
              v-for="(val, idx) in (vector || [])"
              :key="idx"
              class="heat-pixel"
              :style="{ backgroundColor: getHeatColor(val) }"
              :title="`Dim ${idx}: ${val}`"
            ></div>
          </div>
        </div>

        <!-- Raw JSON / Array Editor Box -->
        <div class="raw-box-section">
          <div class="raw-box-header">
            <span>原始浮点数组 (Raw JSON Array)</span>
            <el-button size="small" type="primary" plain @click="copyVector">
              <el-icon><CopyDocument /></el-icon> 复制全部数据
            </el-button>
          </div>
          <el-input
            type="textarea"
            :rows="6"
            readonly
            :model-value="JSON.stringify(vector)"
            class="font-mono"
          />
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useAppStore } from '../stores/appStore'
import { ElMessage } from 'element-plus'
import { View, Hide, CopyDocument, DataAnalysis } from '@element-plus/icons-vue'

const props = defineProps<{
  vector?: number[]
  dimension?: number
  forceMask?: boolean
}>()

const appStore = useAppStore()
const localUnmask = ref<boolean | null>(null)
const showDetailDialog = ref(false)

const isMasked = computed(() => {
  if (props.forceMask !== undefined) return props.forceMask
  if (localUnmask.value !== null) return !localUnmask.value
  return appStore.maskRawVector
})

const dim = computed(() => {
  if (props.vector && props.vector.length > 0) return props.vector.length
  return props.dimension || 0
})

const l2Norm = computed(() => {
  if (!props.vector || props.vector.length === 0) return null
  let sum = 0
  for (let i = 0; i < props.vector.length; i++) {
    sum += props.vector[i] * props.vector[i]
  }
  return Math.sqrt(sum)
})

const maxVal = computed(() => {
  if (!props.vector || props.vector.length === 0) return 0
  return Math.max(...props.vector)
})

const minVal = computed(() => {
  if (!props.vector || props.vector.length === 0) return 0
  return Math.min(...props.vector)
})

const meanVal = computed(() => {
  if (!props.vector || props.vector.length === 0) return 0
  const sum = props.vector.reduce((a, b) => a + b, 0)
  return sum / props.vector.length
})

const varianceVal = computed(() => {
  if (!props.vector || props.vector.length === 0) return 0
  const m = meanVal.value
  const v = props.vector.reduce((acc, val) => acc + Math.pow(val - m, 2), 0)
  return v / props.vector.length
})

const maskedPreviewText = computed(() => {
  if (!props.vector || props.vector.length === 0) {
    return `[${dim.value} 维向量未加载]`
  }
  const first = props.vector[0]?.toFixed(3) || '0.000'
  const second = props.vector[1]?.toFixed(3) || '0.000'
  const remaining = props.vector.length - 2
  return `[${first}, ${second}, ... +${remaining} 维 (已屏蔽)]`
})

// Sample vector up to 32 points for mini thumbnail
const sampledVector = computed(() => {
  if (!props.vector || props.vector.length === 0) return []
  const maxSamples = 32
  if (props.vector.length <= maxSamples) return props.vector
  const step = props.vector.length / maxSamples
  const samples: number[] = []
  for (let i = 0; i < maxSamples; i++) {
    samples.push(props.vector[Math.floor(i * step)])
  }
  return samples
})

const getHeatColor = (val: number) => {
  // Normalize between -1 and 1
  const clamped = Math.max(-1, Math.min(1, val))
  if (clamped >= 0) {
    // 0 to 1 -> blue to teal/emerald
    const intensity = Math.min(1, clamped * 2)
    return `rgba(16, 185, 129, ${0.2 + intensity * 0.8})`
  } else {
    // -1 to 0 -> slate to amber/red
    const intensity = Math.min(1, Math.abs(clamped) * 2)
    return `rgba(245, 158, 11, ${0.2 + intensity * 0.8})`
  }
}

const copyVector = () => {
  if (!props.vector) return
  navigator.clipboard.writeText(JSON.stringify(props.vector))
  ElMessage.success('已复制完整向量数据至剪贴板')
}
</script>

<style scoped>
.vector-viewer-root {
  width: 100%;
}

.vector-masked-box {
  background: #0f172a;
  border: 1px solid #1e293b;
  border-radius: 8px;
  padding: 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.masked-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dim-tag {
  font-family: var(--font-mono);
  font-weight: 600;
}

.norm-badge {
  font-size: 11px;
  font-family: var(--font-mono);
  color: #38bdf8;
  background: rgba(56, 189, 248, 0.1);
  padding: 2px 6px;
  border-radius: 4px;
}

.mini-heatstrip {
  display: flex;
  height: 6px;
  border-radius: 3px;
  overflow: hidden;
  gap: 1px;
  cursor: pointer;
  background: #1e293b;
}

.heat-cell {
  flex: 1;
  height: 100%;
  transition: opacity 0.2s;
}

.heat-cell:hover {
  opacity: 0.8;
}

.masked-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.preview-text {
  font-size: 12px;
  color: #64748b;
}

.masked-actions {
  display: flex;
  gap: 4px;
}

.vector-unmasked-box {
  background: #0f172a;
  border: 1px solid #334155;
  border-radius: 8px;
  padding: 8px 12px;
}

.unmasked-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  border-bottom: 1px solid #1e293b;
  padding-bottom: 6px;
}

.left-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.right-actions {
  display: flex;
  gap: 6px;
}

.vector-raw-array {
  max-height: 120px;
  overflow-y: auto;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 11px;
  padding: 4px;
}

.vector-dim-item {
  background: #1e293b;
  border: 1px solid #334155;
  padding: 2px 6px;
  border-radius: 4px;
  display: flex;
  gap: 4px;
}

.dim-index {
  color: #64748b;
}

.dim-val {
  color: #cbd5e1;
}

.vector-dim-item.is-positive .dim-val {
  color: #34d399;
}

.vector-dim-item.is-negative .dim-val {
  color: #f59e0b;
}

/* Dialog Styles */
.stats-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 20px;
}

.stat-card {
  background: #0f172a;
  border: 1px solid #334155;
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stat-title {
  font-size: 11px;
  color: #94a3b8;
}

.stat-val {
  font-size: 14px;
  font-weight: 600;
  color: #f8fafc;
}

.spectrum-section {
  margin-bottom: 20px;
}

.spectrum-label {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: #cbd5e1;
  margin-bottom: 8px;
}

.spectrum-legend {
  font-size: 11px;
  color: #94a3b8;
  display: flex;
  align-items: center;
  gap: 8px;
}

.legend-box {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
}

.legend-box.neg {
  background: #f59e0b;
}

.legend-box.zero {
  background: #334155;
}

.legend-box.pos {
  background: #10b981;
}

.full-heatstrip {
  display: flex;
  height: 24px;
  background: #0f172a;
  border: 1px solid #334155;
  border-radius: 6px;
  overflow: hidden;
  gap: 1px;
}

.heat-pixel {
  flex: 1;
  height: 100%;
  cursor: pointer;
  transition: transform 0.1s;
}

.heat-pixel:hover {
  transform: scaleY(1.3);
  z-index: 10;
}

.raw-box-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.raw-box-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: #cbd5e1;
}
</style>
