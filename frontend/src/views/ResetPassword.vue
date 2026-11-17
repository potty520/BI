<template>
  <div class="reset-page">
    <div class="reset-box">
      <h2>重置密码</h2>
      <p class="hint">请设置新的登录密码（至少 8 位，含字母和数字）。</p>
      <el-alert v-if="!token" type="error" :closable="false" show-icon style="margin-bottom:14px">
        链接中缺少令牌，请从重置邮件中重新打开，或返回登录页重新申请。
      </el-alert>
      <el-form :model="form" label-width="90px" v-if="token">
        <el-form-item label="新密码">
          <el-input v-model="form.newPassword" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input v-model="form.confirm" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-button type="primary" style="width:100%" :loading="loading" @click="submit">确认重置</el-button>
      </el-form>
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
const token = ref('')
const loading = ref(false)
const form = ref({ newPassword: '', confirm: '' })

onMounted(() => {
  // hash 路由：token 在 #/reset-password?token=xxx 里
  const hash = location.hash || ''
  const q = hash.indexOf('?') >= 0 ? hash.slice(hash.indexOf('?')) : ''
  const m = /[?&]token=([^&]+)/.exec(q)
  token.value = m ? decodeURIComponent(m[1]) : ''
})

async function submit() {
  if (!form.value.newPassword) return ElMessage.warning('请输入新密码')
  if (form.value.newPassword !== form.value.confirm) return ElMessage.warning('两次输入的密码不一致')
  loading.value = true
  try {
    await api.resetPassword({ token: token.value, newPassword: form.value.newPassword })
    ElMessage.success('密码已重置，请使用新密码登录')
    router.push('/login')
  } catch (e) { /* 拦截器已提示 */ } finally { loading.value = false }
}
</script>

<style scoped>
.reset-page { height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg,#0f172a,#1e3a5f 60%,#0f766e); }
.reset-box { width: 420px; background: #fff; border-radius: 14px; padding: 32px; box-shadow: 0 18px 50px rgba(0,0,0,.3); }
.reset-box h2 { margin: 0 0 6px; font-size: 20px; color: #0f172a; }
.hint { color: #64748b; font-size: 12px; margin: 0 0 18px; }
.back { margin-top: 14px; text-align: center; font-size: 12px; }
</style>
