<template>
  <header class="header-container">
    <div class="header-left">
      <div class="logo" @click="router.push('/stores')">
        <span class="logo-title">VecLite</span>
      </div>

      <nav class="nav-menu">
        <router-link to="/stores" class="nav-item" active-class="active">
          <el-icon><Coin /></el-icon>
          <span>向量库</span>
        </router-link>
        <router-link to="/documents" class="nav-item" active-class="active">
          <el-icon><Document /></el-icon>
          <span>数据管理</span>
        </router-link>
        <router-link to="/search-debug" class="nav-item" active-class="active">
          <el-icon><Aim /></el-icon>
          <span>查询调试</span>
        </router-link>
        <router-link to="/settings" class="nav-item" active-class="active">
          <el-icon><Setting /></el-icon>
          <span>系统配置</span>
        </router-link>
      </nav>
    </div>

    <div class="header-right">
      <!-- Store Selector -->
      <el-select
        v-model="appStore.currentStore"
        placeholder="选择向量库"
        size="small"
        class="store-select"
        @change="onStoreChange"
      >
        <el-option
          v-for="storeName in appStore.storeList"
          :key="storeName"
          :label="storeName"
          :value="storeName"
        />
      </el-select>

      <!-- Vector Mask Toggle -->
      <div class="mask-pill" :class="{ 'is-masked': appStore.maskRawVector }" @click="toggleMask">
        <el-icon v-if="appStore.maskRawVector"><Hide /></el-icon>
        <el-icon v-else><View /></el-icon>
        <span class="mask-text">{{ appStore.maskRawVector ? '屏蔽向量值: 开' : '屏蔽向量值: 关' }}</span>
      </div>

      <!-- Connection Status -->
      <div class="connection-badge" @click="showUrlDialog = true" title="点击配置后端连接">
        <span class="status-indicator" :class="{ 'online': appStore.isConnected, 'offline': !appStore.isConnected }"></span>
        <span class="status-label">{{ appStore.isConnected ? `${appStore.latency}ms` : '未连接' }}</span>
      </div>

      <!-- Settings URL Dialog -->
      <el-dialog v-model="showUrlDialog" title="配置后端连接" width="440px" append-to-body>
        <el-form label-position="top">
          <el-form-item label="API Base URL">
            <el-input v-model="tempBaseUrl" placeholder="/veclite/api/v1" />
          </el-form-item>
        </el-form>
        <div v-if="appStore.connectionError" class="conn-error-alert">
          <el-icon><WarningFilled /></el-icon>
          <span>{{ appStore.connectionError }}</span>
        </div>
        <template #footer>
          <span class="dialog-footer">
            <el-button @click="showUrlDialog = false">取消</el-button>
            <el-button type="primary" :loading="appStore.isCheckingHealth" @click="saveAndTestConnection">
              测试并保存
            </el-button>
          </span>
        </template>
      </el-dialog>
    </div>
  </header>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/appStore'
import { ElMessage } from 'element-plus'
import {
  Coin,
  Document,
  Aim,
  Setting,
  Hide,
  View,
  WarningFilled
} from '@element-plus/icons-vue'

const router = useRouter()
const appStore = useAppStore()
const showUrlDialog = ref(false)
const tempBaseUrl = ref(appStore.baseUrl)

const toggleMask = () => {
  appStore.setMaskRawVector(!appStore.maskRawVector)
  ElMessage({
    type: 'info',
    message: appStore.maskRawVector ? '已开启向量值屏蔽' : '已关闭向量值屏蔽',
    duration: 1500
  })
}

const onStoreChange = (storeName: string) => {
  appStore.setCurrentStore(storeName)
}

const saveAndTestConnection = async () => {
  appStore.setBaseUrl(tempBaseUrl.value)
  await appStore.checkHealth()
  if (appStore.isConnected) {
    ElMessage.success('连接成功')
    showUrlDialog.value = false
  } else {
    ElMessage.error('连接失败，请检查服务状态')
  }
}

onMounted(() => {
  appStore.checkHealth()
})
</script>

<style scoped>
.header-container {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 52px;
  padding: 0 20px;
  background: #0f172a;
  border-bottom: 1px solid #1e293b;
  position: sticky;
  top: 0;
  z-index: 1000;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 24px;
}

.logo {
  cursor: pointer;
  user-select: none;
}

.logo-title {
  font-size: 16px;
  font-weight: 700;
  color: #38bdf8;
  letter-spacing: 0.5px;
}

.nav-menu {
  display: flex;
  align-items: center;
  gap: 4px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 6px 12px;
  border-radius: 6px;
  color: #94a3b8;
  text-decoration: none;
  font-size: 13px;
  font-weight: 500;
  transition: all 0.15s;
}

.nav-item:hover {
  color: #f8fafc;
  background: #1e293b;
}

.nav-item.active {
  color: #38bdf8;
  background: rgba(56, 189, 248, 0.1);
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.store-select {
  width: 160px;
}

.mask-pill {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  background: #1e293b;
  border: 1px solid #334155;
  border-radius: 6px;
  font-size: 12px;
  color: #94a3b8;
  cursor: pointer;
  user-select: none;
  transition: all 0.15s;
}

.mask-pill:hover {
  border-color: #475569;
  color: #f8fafc;
}

.mask-pill.is-masked {
  background: rgba(16, 185, 129, 0.1);
  border-color: rgba(16, 185, 129, 0.3);
  color: #34d399;
}

.connection-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: #1e293b;
  border: 1px solid #334155;
  border-radius: 6px;
  cursor: pointer;
}

.status-indicator {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.status-indicator.online {
  background: #10b981;
}

.status-indicator.offline {
  background: #ef4444;
}

.status-label {
  font-size: 12px;
  font-family: var(--font-mono);
  color: #94a3b8;
}

.conn-error-alert {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.3);
  color: #f87171;
  border-radius: 6px;
  font-size: 12px;
  margin-top: 10px;
}
</style>
