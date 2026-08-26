<template>
  <div class="metadata-editor-root">
    <div class="editor-mode-bar">
      <span class="mode-title">元数据编辑 (Metadata)</span>
      <el-radio-group v-model="mode" size="small">
        <el-radio-button label="table">键值表单</el-radio-button>
        <el-radio-button label="json">JSON 源码</el-radio-button>
      </el-radio-group>
    </div>

    <!-- Table mode -->
    <div v-if="mode === 'table'" class="table-editor">
      <div class="entries-list">
        <div v-for="(entry, index) in entries" :key="index" class="entry-row">
          <el-input
            v-model="entry.key"
            placeholder="字段名 (key)"
            size="small"
            class="key-input font-mono"
            @change="emitUpdate"
          />
          <el-select
            v-model="entry.type"
            size="small"
            class="type-select"
            @change="onTypeChange(entry)"
          >
            <el-option label="String" value="string" />
            <el-option label="Number" value="number" />
            <el-option label="Boolean" value="boolean" />
          </el-select>
          <el-input
            v-if="entry.type === 'string'"
            v-model="entry.value"
            placeholder="字符串值"
            size="small"
            class="val-input"
            @change="emitUpdate"
          />
          <el-input-number
            v-else-if="entry.type === 'number'"
            v-model="entry.numValue"
            size="small"
            class="val-input"
            @change="emitUpdate"
          />
          <el-switch
            v-else
            v-model="entry.boolValue"
            size="small"
            @change="emitUpdate"
          />
          <el-button
            circle
            size="small"
            type="danger"
            plain
            @click="removeEntry(index)"
          >
            <el-icon><Delete /></el-icon>
          </el-button>
        </div>
      </div>

      <div class="add-entry-btn">
        <el-button size="small" type="primary" plain @click="addEntry">
          <el-icon><Plus /></el-icon> 添加属性
        </el-button>
      </div>
    </div>

    <!-- JSON mode -->
    <div v-else class="json-editor">
      <el-input
        v-model="jsonText"
        type="textarea"
        :rows="6"
        placeholder='{"category": "tech", "views": 100}'
        class="font-mono"
        @blur="onJsonBlur"
      />
      <span v-if="jsonError" class="json-err-msg">{{ jsonError }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { Plus, Delete } from '@element-plus/icons-vue'

interface Entry {
  key: string
  type: 'string' | 'number' | 'boolean'
  value: string
  numValue?: number
  boolValue?: boolean
}

const props = defineProps<{
  modelValue?: Record<string, any>
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, any>): void
}>()

const mode = ref<'table' | 'json'>('table')
const entries = ref<Entry[]>([])
const jsonText = ref<string>('{}')
const jsonError = ref<string>('')

// Initialize entries from props
const syncFromProps = () => {
  const obj = props.modelValue || {}
  jsonText.value = JSON.stringify(obj, null, 2)
  jsonError.value = ''

  const newEntries: Entry[] = []
  for (const [k, v] of Object.entries(obj)) {
    if (typeof v === 'number') {
      newEntries.push({ key: k, type: 'number', value: '', numValue: v })
    } else if (typeof v === 'boolean') {
      newEntries.push({ key: k, type: 'boolean', value: '', boolValue: v })
    } else {
      newEntries.push({ key: k, type: 'string', value: String(v ?? '') })
    }
  }
  entries.value = newEntries
}

watch(() => props.modelValue, syncFromProps, { immediate: true, deep: true })

const addEntry = () => {
  entries.value.push({ key: '', type: 'string', value: '' })
}

const removeEntry = (index: number) => {
  entries.value.splice(index, 1)
  emitUpdate()
}

const onTypeChange = (entry: Entry) => {
  if (entry.type === 'number') entry.numValue = 0
  if (entry.type === 'boolean') entry.boolValue = true
  emitUpdate()
}

const emitUpdate = () => {
  const res: Record<string, any> = {}
  for (const entry of entries.value) {
    if (!entry.key.trim()) continue
    if (entry.type === 'number') {
      res[entry.key.trim()] = entry.numValue ?? 0
    } else if (entry.type === 'boolean') {
      res[entry.key.trim()] = Boolean(entry.boolValue)
    } else {
      res[entry.key.trim()] = entry.value
    }
  }
  jsonText.value = JSON.stringify(res, null, 2)
  emit('update:modelValue', res)
}

const onJsonBlur = () => {
  try {
    const parsed = JSON.parse(jsonText.value || '{}')
    jsonError.value = ''
    emit('update:modelValue', parsed)
  } catch (err: any) {
    jsonError.value = `JSON 解析错误: ${err.message}`
  }
}
</script>

<style scoped>
.metadata-editor-root {
  background: #0f172a;
  border: 1px solid #1e293b;
  border-radius: 8px;
  padding: 12px;
}

.editor-mode-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.mode-title {
  font-size: 12px;
  font-weight: 600;
  color: #cbd5e1;
}

.entries-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 10px;
}

.entry-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.key-input {
  width: 140px;
}

.type-select {
  width: 100px;
}

.val-input {
  flex: 1;
}

.add-entry-btn {
  display: flex;
  justify-content: flex-start;
}

.json-err-msg {
  display: block;
  font-size: 12px;
  color: #ef4444;
  margin-top: 6px;
}
</style>
