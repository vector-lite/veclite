<template>
  <div class="app-root">
    <HeaderNav />
    <main class="main-content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import HeaderNav from './components/HeaderNav.vue'
</script>

<style scoped>
.app-root {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: var(--bg-primary);
}

.main-content {
  flex: 1;
}
</style>
