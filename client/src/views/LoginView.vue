<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

// 演示账号(与后端 DataSeeder 种子数据一致)
const DEMO_ACCOUNTS = [
  { label: '玩家 A', email: 'player_a@example.com', password: 'Test123456!' },
  { label: '玩家 B', email: 'player_b@example.com', password: 'Test123456!' },
]

const auth = useAuthStore()
const router = useRouter()
const form = ref({ email: '', password: '' })
const loading = ref(false)

function fill(account: (typeof DEMO_ACCOUNTS)[number]) {
  form.value = { email: account.email, password: account.password }
}

async function submit() {
  if (!form.value.email || !form.value.password) {
    ElMessage.warning('请输入邮箱和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.value.email, form.value.password)
    ElMessage.success(`欢迎,${auth.user?.nickname}`)
    router.push({ name: 'lobby' })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <h1 class="title">🍎 Link-Duel</h1>
      <p class="subtitle">在线对战连连看 · 限时积分制</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="player_a@example.com" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="Test123456!"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
          登 录
        </el-button>
      </el-form>
      <el-divider>演示账号一键填充</el-divider>
      <div class="demo-buttons">
        <el-button v-for="acc in DEMO_ACCOUNTS" :key="acc.email" size="large" @click="fill(acc)">
          {{ acc.label }}
        </el-button>
      </div>
      <p class="hint">
        提示:两名玩家请分别在普通窗口与隐身窗口登录(浏览器标签页共享 localStorage)
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #e0f2fe 0%, #fef3c7 100%);
}

.login-card {
  width: 420px;
  padding: 8px 12px;
}

.title {
  text-align: center;
  font-size: 32px;
  margin-bottom: 4px;
}

.subtitle {
  text-align: center;
  color: #909399;
  margin-bottom: 20px;
}

.submit {
  width: 100%;
}

.demo-buttons {
  display: flex;
  gap: 12px;
}

.demo-buttons .el-button {
  flex: 1;
}

.hint {
  margin-top: 14px;
  font-size: 12px;
  color: #a8abb2;
  text-align: center;
}
</style>
