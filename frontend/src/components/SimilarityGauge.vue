<template>
  <div class="similarity-gauge-root">
    <div class="gauge-header">
      <span class="score-label">相似度得分 (Score)</span>
      <span class="score-number font-mono" :style="{ color: scoreColor }">
        {{ score.toFixed(4) }}
      </span>
    </div>
    <div class="progress-track">
      <div
        class="progress-fill"
        :style="{
          width: `${Math.min(100, Math.max(0, normalizedScore * 100))}%`,
          background: progressGradient
        }"
      ></div>
    </div>
    <div class="gauge-footer">
      <span class="confidence-tag font-mono" :style="{ color: scoreColor }">
        {{ confidenceLabel }}
      </span>
      <span v-if="rank !== undefined" class="rank-badge font-mono">
        TOP #{{ rank }}
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  score: number
  metric?: string
  rank?: number
}>()

const normalizedScore = computed(() => {
  if (props.metric === 'L2') {
    // For L2 distance, lower is closer, convert to pseudo-similarity 1 / (1 + d)
    return 1 / (1 + Math.max(0, props.score))
  }
  // For Cosine: range is -1 to 1, normalized to 0~1 or direct score
  return Math.max(0, Math.min(1, props.score))
})

const scoreColor = computed(() => {
  const s = normalizedScore.value
  if (s >= 0.85) return '#10b981' // Green
  if (s >= 0.70) return '#06b6d4' // Cyan
  if (s >= 0.50) return '#f59e0b' // Amber
  return '#ef4444' // Red
})

const progressGradient = computed(() => {
  const s = normalizedScore.value
  if (s >= 0.85) return 'linear-gradient(90deg, #059669, #10b981)'
  if (s >= 0.70) return 'linear-gradient(90deg, #0891b2, #06b6d4)'
  if (s >= 0.50) return 'linear-gradient(90deg, #d97706, #f59e0b)'
  return 'linear-gradient(90deg, #dc2626, #ef4444)'
})

const confidenceLabel = computed(() => {
  const s = normalizedScore.value
  if (s >= 0.90) return '极高匹配 (High Match)'
  if (s >= 0.75) return '高相似 (Strong)'
  if (s >= 0.60) return '中等相似 (Moderate)'
  if (s >= 0.40) return '弱相关 (Weak)'
  return '低相关 (Low)'
})
</script>

<style scoped>
.similarity-gauge-root {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 160px;
}

.gauge-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.score-label {
  font-size: 11px;
  color: #94a3b8;
}

.score-number {
  font-size: 15px;
  font-weight: 700;
}

.progress-track {
  height: 6px;
  background: #0f172a;
  border-radius: 3px;
  overflow: hidden;
  border: 1px solid #334155;
}

.progress-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.4s ease;
}

.gauge-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.confidence-tag {
  font-size: 10px;
  font-weight: 600;
}

.rank-badge {
  font-size: 10px;
  background: #334155;
  color: #f8fafc;
  padding: 1px 5px;
  border-radius: 3px;
}
</style>
