<template>
  <div class="vector-heatmap-root">
    <div class="heatmap-header">
      <div class="header-left">
        <span class="chart-title">向量多维特征共鸣对比 (Feature Resonance)</span>
        <span class="chart-sub">比较 Query 向量与 Target 向量在各维度的分布与积分</span>
      </div>
      <div class="header-right">
        <el-radio-group v-model="viewMode" size="small">
          <el-radio-button label="bar">柱状对比</el-radio-button>
          <el-radio-button label="diff">维度差异</el-radio-button>
          <el-radio-button label="spectrum">热谱条</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <!-- Canvas / SVG Visualizer -->
    <div class="visualizer-body">
      <!-- 1. Bar comparison mode -->
      <div v-if="viewMode === 'bar'" class="bar-chart-container">
        <div class="bars-wrapper">
          <div
            v-for="(item, idx) in sampledDims"
            :key="idx"
            class="dim-bar-column"
            :title="`Dim ${item.dimIndex}\nQuery: ${item.qVal.toFixed(4)}\nTarget: ${item.tVal.toFixed(4)}\nProd: ${(item.qVal * item.tVal).toFixed(4)}`"
          >
            <!-- Query bar -->
            <div class="bar-pair">
              <div
                class="bar q-bar"
                :style="{
                  height: `${Math.min(100, Math.abs(item.qVal) * 80)}px`,
                  backgroundColor: item.qVal >= 0 ? '#38bdf8' : '#f59e0b'
                }"
              ></div>
              <!-- Target bar -->
              <div
                class="bar t-bar"
                :style="{
                  height: `${Math.min(100, Math.abs(item.tVal) * 80)}px`,
                  backgroundColor: item.tVal >= 0 ? '#10b981' : '#ef4444'
                }"
              ></div>
            </div>
            <span class="dim-lbl">d{{ item.dimIndex }}</span>
          </div>
        </div>
        <div class="legend-row">
          <span class="legend-item"><i class="leg-dot q"></i> Query 向量</span>
          <span class="legend-item"><i class="leg-dot t"></i> 目标候选向量</span>
        </div>
      </div>

      <!-- 2. Diff mode (Cosine Product per dimension) -->
      <div v-else-if="viewMode === 'diff'" class="diff-chart-container">
        <div class="diff-bars">
          <div
            v-for="(item, idx) in sampledDims"
            :key="idx"
            class="diff-bar-col"
            :title="`Dim ${item.dimIndex}\n贡献点积: ${(item.qVal * item.tVal).toFixed(4)}`"
          >
            <div
              class="diff-bar"
              :class="{ 'is-pos': item.qVal * item.tVal >= 0, 'is-neg': item.qVal * item.tVal < 0 }"
              :style="{
                height: `${Math.min(90, Math.abs(item.qVal * item.tVal) * 150)}px`
              }"
            ></div>
            <span class="dim-lbl">d{{ item.dimIndex }}</span>
          </div>
        </div>
        <div class="legend-row">
          <span class="legend-item"><i class="leg-dot pos"></i> 正向贡献 (协同)</span>
          <span class="legend-item"><i class="leg-dot neg"></i> 负向贡献 (相斥)</span>
        </div>
      </div>

      <!-- 3. Dual Spectrum mode -->
      <div v-else class="dual-spectrum-container">
        <div class="spectrum-row">
          <span class="spec-name font-mono">Query:</span>
          <div class="spec-strip">
            <div
              v-for="(val, idx) in queryVector"
              :key="idx"
              class="spec-cell"
              :style="{ backgroundColor: getHeatColor(val) }"
              :title="`Query Dim ${idx}: ${val}`"
            ></div>
          </div>
        </div>
        <div class="spectrum-row">
          <span class="spec-name font-mono">Target:</span>
          <div class="spec-strip">
            <div
              v-for="(val, idx) in targetVector"
              :key="idx"
              class="spec-cell"
              :style="{ backgroundColor: getHeatColor(val) }"
              :title="`Target Dim ${idx}: ${val}`"
            ></div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'

const props = defineProps<{
  queryVector?: number[]
  targetVector?: number[]
}>()

const viewMode = ref<'bar' | 'diff' | 'spectrum'>('bar')

const dimCount = computed(() => {
  return Math.max(props.queryVector?.length || 0, props.targetVector?.length || 0)
})

// Sample up to 32 dimensions for bar / diff comparison
const sampledDims = computed(() => {
  const q = props.queryVector || []
  const t = props.targetVector || []
  const total = Math.max(q.length, t.length)
  if (total === 0) return []

  const maxCols = 32
  const step = Math.max(1, Math.floor(total / maxCols))
  const res = []

  for (let i = 0; i < total; i += step) {
    if (res.length >= maxCols) break
    res.push({
      dimIndex: i,
      qVal: q[i] !== undefined ? q[i] : 0,
      tVal: t[i] !== undefined ? t[i] : 0
    })
  }
  return res
})

const getHeatColor = (val: number) => {
  const clamped = Math.max(-1, Math.min(1, val))
  if (clamped >= 0) {
    const intensity = Math.min(1, clamped * 2)
    return `rgba(16, 185, 129, ${0.2 + intensity * 0.8})`
  } else {
    const intensity = Math.min(1, Math.abs(clamped) * 2)
    return `rgba(245, 158, 11, ${0.2 + intensity * 0.8})`
  }
}
</script>

<style scoped>
.vector-heatmap-root {
  background: #0f172a;
  border: 1px solid #1e293b;
  border-radius: 8px;
  padding: 14px;
}

.heatmap-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  border-bottom: 1px solid #1e293b;
  padding-bottom: 8px;
}

.chart-title {
  font-size: 13px;
  font-weight: 600;
  color: #f8fafc;
  display: block;
}

.chart-sub {
  font-size: 11px;
  color: #64748b;
}

.visualizer-body {
  padding: 8px 0;
}

/* Bar Chart */
.bar-chart-container, .diff-chart-container {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.bars-wrapper, .diff-bars {
  display: flex;
  align-items: flex-end;
  height: 120px;
  gap: 6px;
  overflow-x: auto;
  padding-bottom: 6px;
}

.dim-bar-column, .diff-bar-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex: 1;
  min-width: 14px;
  cursor: pointer;
}

.bar-pair {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 100px;
}

.bar {
  width: 5px;
  border-radius: 2px 2px 0 0;
  transition: height 0.3s;
}

.diff-bar {
  width: 8px;
  border-radius: 2px 2px 0 0;
  transition: height 0.3s;
}

.diff-bar.is-pos {
  background: #10b981;
}

.diff-bar.is-neg {
  background: #ef4444;
}

.dim-lbl {
  font-size: 9px;
  color: #64748b;
  font-family: var(--font-mono);
  margin-top: 4px;
}

.legend-row {
  display: flex;
  justify-content: center;
  gap: 16px;
  font-size: 11px;
  color: #94a3b8;
  margin-top: 6px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.leg-dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  display: inline-block;
}

.leg-dot.q { background: #38bdf8; }
.leg-dot.t { background: #10b981; }
.leg-dot.pos { background: #10b981; }
.leg-dot.neg { background: #ef4444; }

/* Dual Spectrum */
.dual-spectrum-container {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 8px 0;
}

.spectrum-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.spec-name {
  font-size: 11px;
  color: #94a3b8;
  width: 48px;
}

.spec-strip {
  flex: 1;
  display: flex;
  height: 20px;
  background: #1e293b;
  border-radius: 4px;
  overflow: hidden;
  gap: 1px;
}

.spec-cell {
  flex: 1;
  height: 100%;
}
</style>
