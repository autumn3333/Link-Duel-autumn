<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <div class="game-page">
    <h2>对局房间 {{ route.params.roomId }}</h2>
    <p>棋盘与实时对战将在 M9 实现</p>
  </div>
</template>

<style scoped>
.game-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
}
</style>
