import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '../api/veclite'
import type { VectorStoreStats } from '../types'

export const useAppStore = defineStore('app', () => {
  // Global settings
  const currentStore = ref<string>(localStorage.getItem('veclite_active_store') || '')
  const storeList = ref<string[]>([])
  const currentStats = ref<VectorStoreStats | null>(null)
  
  // Vector Mask preference: when true, raw float arrays are masked / minimized
  const maskRawVector = ref<boolean>(
    localStorage.getItem('veclite_mask_vector') !== null
      ? localStorage.getItem('veclite_mask_vector') === 'true'
      : true
  )

  // Dark mode
  const isDarkMode = ref<boolean>(
    localStorage.getItem('veclite_dark_mode') !== null
      ? localStorage.getItem('veclite_dark_mode') === 'true'
      : true
  )

  // Connection state
  const isConnected = ref<boolean>(false)
  const latency = ref<number>(0)
  const isCheckingHealth = ref<boolean>(false)
  const connectionError = ref<string>('')
  const baseUrl = ref<string>(api.getBaseUrl())

  const setMaskRawVector = (val: boolean) => {
    maskRawVector.value = val
    localStorage.setItem('veclite_mask_vector', String(val))
  }

  const toggleDarkMode = () => {
    isDarkMode.value = !isDarkMode.value
    localStorage.setItem('veclite_dark_mode', String(isDarkMode.value))
    updateHtmlClass()
  }

  const updateHtmlClass = () => {
    if (isDarkMode.value) {
      document.documentElement.classList.add('dark')
    } else {
      document.documentElement.classList.remove('dark')
    }
  }

  const setCurrentStore = (name: string) => {
    currentStore.value = name
    localStorage.setItem('veclite_active_store', name)
    if (name) {
      fetchCurrentStats()
    } else {
      currentStats.value = null
    }
  }

  const setBaseUrl = (url: string) => {
    baseUrl.value = url
    api.setBaseUrl(url)
    checkHealth()
  }

  const checkHealth = async () => {
    isCheckingHealth.value = true
    connectionError.value = ''
    try {
      const res = await api.checkHealth()
      isConnected.value = res.ok
      latency.value = res.latencyMs
      if (!res.ok) {
        connectionError.value = res.error || '无法连接到后端'
      } else {
        await refreshStoreList()
      }
    } catch (err: any) {
      isConnected.value = false
      connectionError.value = err.message || '连接异常'
    } finally {
      isCheckingHealth.value = false
    }
  }

  const refreshStoreList = async () => {
    try {
      const stores = await api.listStores()
      storeList.value = stores
      if (stores.length > 0) {
        if (!currentStore.value || !stores.includes(currentStore.value)) {
          setCurrentStore(stores[0])
        } else {
          await fetchCurrentStats()
        }
      } else {
        setCurrentStore('')
      }
    } catch (err) {
      storeList.value = []
    }
  }

  const fetchCurrentStats = async () => {
    if (!currentStore.value) return
    try {
      const stats = await api.getStats(currentStore.value)
      currentStats.value = stats
    } catch (err) {
      currentStats.value = null
    }
  }

  return {
    currentStore,
    storeList,
    currentStats,
    maskRawVector,
    isDarkMode,
    isConnected,
    latency,
    isCheckingHealth,
    connectionError,
    baseUrl,
    setMaskRawVector,
    toggleDarkMode,
    updateHtmlClass,
    setCurrentStore,
    setBaseUrl,
    checkHealth,
    refreshStoreList,
    fetchCurrentStats
  }
})
