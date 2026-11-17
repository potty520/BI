<template>
  <div class="sso-page">
    <div class="sso-box">
      <h2>正在完成单点登录…</h2>
      <el-alert v-if="error" type="error" :closable="false" show-icon>{{ error }}</el-alert>
      <div v-else class="hint">正在向身份提供方换取平台令牌，请稍候。</div>
      <div class="back"><el-link type="primary" @click="$router.push('/login')">返回登录</el-link></div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const router = useRouter()
const error = ref('')

onMounted(async () => {
  const hash = location.hash || ''
  const q = hash.indexOf('?') >= 0 ? hash.slice(hash.indexOf('?')) : ''
  const params = new URLSearchParams(q)
  const code = params.get('code')
  const state = params.get('state')
  if (!code || !state) {
    error.value = '回调缺少 code 或 state，请重新发起单点登录。'
    return
  }
  try {
    const res = await api.oidcCallback({ code, state })
    const data = res.data
    localStorage.setItem('rbi_token', data.token)
    localStorage.setItem('rbi_user', JSON.stringify(data))
    ElMessage.success('单点登录成功')
    router.push('/home')
  } catch (e) {
    error.value = e?.response?.data?.msg || e?.msg || '单点登录失败，请联系管理员'
  }
})
</script>

<style scoped>
.sso-page { height: 100vh; display: flex; align-items: center; justify-content: center; background: #0f172a; }
.sso-box { width: 420px; background: #fff; border-radius: 14px; padding: 30px; text-align: center; }
.sso-box h2 { font-size: 18px; margin: 0 0 14px; color: #0f172a; }
.hint { color: #64748b; font-size: 13px; }
.back { margin-top: 16px; font-size: 12px; }
</style>
