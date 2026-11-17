<template>
  <div class="login-page">
    <div class="login-box">
      <div class="brand">
        <div class="brand-mark">R</div>
        <div>
          <h1>自研报表与BI平台</h1>
          <p>Report & BI Platform · 参照帆软功能体系</p>
        </div>
      </div>
      <el-form :model="form" @keyup.enter="doLogin" class="login-form">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" size="large" prefix-icon="User" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" prefix-icon="Lock" show-password />
        </el-form-item>
        <el-button type="primary" size="large" style="width:100%" :loading="loading" @click="doLogin">登 录</el-button>
      </el-form>
      <div class="tips">
        <div>演示账号（密码均为 <b>123456</b>）：</div>
        <div class="accounts">
          <el-tag v-for="a in accounts" :key="a.u" @click="form.username=a.u; form.password='123456'" class="account-tag" effect="plain">{{ a.u }} · {{ a.n }}</el-tag>
        </div>
      </div>
    </div>
    <div class="login-bg-text">DATA · INSIGHT · DECISION</div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const router = useRouter()
const loading = ref(false)
const form = ref({ username: 'admin', password: '123456' })
const accounts = [
  { u: 'admin', n: '管理员' }, { u: 'analyst', n: '分析师' }, { u: 'viewer', n: '查看者' }
]

async function doLogin() {
  loading.value = true
  try {
    const res = await api.login(form.value)
    localStorage.setItem('rbi_token', res.data.token)
    localStorage.setItem('rbi_user', JSON.stringify(res.data))
    ElMessage.success('登录成功')
    router.push('/home')
  } catch (e) {} finally { loading.value = false }
}
</script>

<style scoped>
.login-page { height: 100vh; display: flex; align-items: center; justify-content: center; position: relative; overflow: hidden; background: linear-gradient(135deg,#0f172a 0%,#1e3a5f 60%,#0f766e 100%); }
.login-page::before { content: ''; position: absolute; width: 700px; height: 700px; border-radius: 50%; background: radial-gradient(circle,rgba(56,189,248,.12),transparent 65%); top: -250px; right: -150px; }
.login-page::after { content: ''; position: absolute; width: 500px; height: 500px; border-radius: 50%; background: radial-gradient(circle,rgba(16,185,129,.1),transparent 65%); bottom: -200px; left: -100px; }
.login-box { width: 400px; background: rgba(255,255,255,.97); border-radius: 16px; padding: 36px; box-shadow: 0 20px 60px rgba(0,0,0,.35); z-index: 2; }
.brand { display: flex; align-items: center; gap: 14px; margin-bottom: 28px; }
.brand-mark { width: 46px; height: 46px; border-radius: 12px; background: linear-gradient(135deg,#2563eb,#38bdf8); color: #fff; font-size: 26px; font-weight: 800; display: flex; align-items: center; justify-content: center; }
.brand h1 { font-size: 19px; margin: 0; color: #0f172a; }
.brand p { margin: 4px 0 0; font-size: 11px; color: #64748b; }
.login-form :deep(.el-input__wrapper) { padding: 4px 4px; }
.tips { margin-top: 20px; padding-top: 16px; border-top: 1px solid #e2e8f0; font-size: 12px; color: #64748b; }
.accounts { margin-top: 10px; display: flex; gap: 8px; flex-wrap: wrap; }
.account-tag { cursor: pointer; }
.login-bg-text { position: absolute; bottom: 30px; font-size: 13px; letter-spacing: 8px; color: rgba(255,255,255,.25); z-index: 1; }
</style>
