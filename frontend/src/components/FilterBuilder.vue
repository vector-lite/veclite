<template>
  <div class="filter-builder-root">
    <div class="filter-header">
      <span class="filter-title">元数据硬过滤条件 (Metadata Filter)</span>
      <el-switch v-model="enabled" active-text="启用过滤" size="small" @change="emitUpdate" />
    </div>

    <div v-if="enabled" class="filter-controls">
      <div class="control-row">
        <el-input
          v-model="field"
          placeholder="过滤字段名 (如 category)"
          size="small"
          class="field-input font-mono"
          @change="emitUpdate"
        />
        <el-select v-model="operator" size="small" class="op-select" @change="emitUpdate">
          <el-option label="等于 (EQ)" value="EQ" />
          <el-option label="包含于 (IN)" value="IN" />
          <el-option label="前缀 (PREFIX)" value="PREFIX" />
          <el-option label="大于 (GT)" value="GT" />
          <el-option label="小于 (LT)" value="LT" />
        </el-select>

        <!-- EQ Value -->
        <el-input
          v-if="operator === 'EQ' || operator === 'PREFIX'"
          v-model="valStr"
          :placeholder="operator === 'EQ' ? '目标值' : '前缀字符串'"
          size="small"
          class="val-input"
          @change="emitUpdate"
        />

        <!-- IN Values -->
        <el-input
          v-else-if="operator === 'IN'"
          v-model="inStr"
          placeholder="多个值以逗号分隔 (如 a,b,c)"
          size="small"
          class="val-input"
          @change="emitUpdate"
        />

        <!-- GT / LT Value -->
        <el-input-number
          v-else
          v-model="valNum"
          size="small"
          class="val-input"
          @change="emitUpdate"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { FilterExpression } from '../types'

const emit = defineEmits<{
  (e: 'update:modelValue', value: FilterExpression | null): void
}>()

const enabled = ref(false)
const field = ref('')
const operator = ref<'EQ' | 'IN' | 'PREFIX' | 'GT' | 'LT'>('EQ')
const valStr = ref('')
const inStr = ref('')
const valNum = ref(0)

const emitUpdate = () => {
  if (!enabled.value || !field.value.trim()) {
    emit('update:modelValue', null)
    return
  }

  const f: FilterExpression = {
    field: field.value.trim(),
    operator: operator.value
  }

  if (operator.value === 'EQ') {
    f.value = valStr.value
  } else if (operator.value === 'PREFIX') {
    f.prefix = valStr.value
  } else if (operator.value === 'IN') {
    f.values = inStr.value
      .split(',')
      .map(s => s.trim())
      .filter(Boolean)
  } else if (operator.value === 'GT' || operator.value === 'LT') {
    f.value = valNum.value
  }

  emit('update:modelValue', f)
}
</script>

<style scoped>
.filter-builder-root {
  background: #0f172a;
  border: 1px solid #1e293b;
  border-radius: 8px;
  padding: 12px;
}

.filter-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.filter-title {
  font-size: 12px;
  font-weight: 600;
  color: #cbd5e1;
}

.filter-controls {
  margin-top: 10px;
}

.control-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.field-input {
  width: 140px;
}

.op-select {
  width: 130px;
}

.val-input {
  flex: 1;
}
</style>
