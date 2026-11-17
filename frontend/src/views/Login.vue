<template>
  <div class="login-page">
    <div class="bg-grid"></div>
    <div class="bg-glow one"></div>
    <div class="bg-glow two"></div>

    <div class="login-card">
      <aside class="brand-panel">
        <div class="brand-top">
          <div class="brand-mark">R</div>
          <div class="brand-name">
            <strong>自研报表与BI平台</strong>
            <span>Report &amp; BI Platform</span>
          </div>
        </div>

        <div class="brand-copy">
          <h1>让数据<br />直接变成决策</h1>
          <p>从数据接入、报表设计到自助分析、定时推送，一套平台跑通全链路。</p>
          <ul class="points">
            <li><span class="dot"></span>单元格扩展引擎，拖拽生成中国式复杂报表</li>
            <li><span class="dot"></span>行级 / 列级数据权限，导出自动带追溯水印</li>
            <li><span class="dot"></span>定时调度与邮件推送，失败自动重试并告警</li>
          </ul>
        </div>

        <div class="brand-foot">DATA · INSIGHT · DECISION</div>
      </aside>

      <section class="form-panel">
        <div class="form-head">
          <h2>欢迎回来</h2>
          <p>使用平台账号登录，或通过企业单点登录</p>
        </div>

        <el-form :model="form" @keyup.enter="doLogin" class="login-form">
          <el-input v-model="form.username" placeholder="用户名" size="large" prefix-icon="User" />
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" prefix-icon="Lock" show-password />
          <div class="row">
            <el-checkbox v-model="remember">记住账号</el-checkbox>
            <el-link type="primary" :underline="false" @click="forgotDialog = true">忘记密码？</el-link>
          </div>
          <el-button class="submit" type="primary" size="large" :loading="loading" @click="doLogin">登 录</el-button>
          <el-button v-if="ssoEnabled" class="sso" size="large" @click="ssoLogin">
            企业单点登录（SSO）
          </el-button>
        </el-form>

        <div class="demo">
          <span class="demo-label">演示账号（密码 123456）</span>
          <div class="accounts">
            <button v-for="a in accounts" :key="a.u" type="button" class="chip" @click="fill(a.u)">
              <b>{{ a.n }}</b>{{ a.u }}
            </button>
          </div>
        </div>
      </section>
    </div>

    <el-dialog v-model="forgotDialog" title="找回密码" width="420px">
      <div class="hint" style="margin-bottom:12px">输入登录名，系统会向账号绑定的邮箱发送重置链接（30 分钟内有效）。</div>
      <el-input v-model="forgotForm.username" placeholder="登录名" @keyup.enter="sendReset" />
      <template #footer>
        <el-button @click="forgotDialog = false">取消</el-button>
        <el-button type="primary" :loading="sending" @click="sendReset">发送重置邮件</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="mfaDialog" title="二次验证" width="400px" :close-on-click-modal="false">
      <div class="hint" style="margin-bottom:12px">该账号已启用两步验证，请输入认证器上的 6 位动态码。</div>
      <el-input v-model="mfaCode" maxlength="6" placeholder="6 位动态码" @keyup.enter="submitMfa" />
      <template #footer>
        <el-button @click="mfaDialog = false">取消</el-button>
        <el-button type="primary" :loading="verifying" @click="submitMfa">验证</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const router = useRouter()
const loading = ref(false)
const remember = ref(true)
const form = ref({ username: '', password: '' })
const accounts = [
  { u: 'admin', n: '管理员' }, { u: 'analyst', n: '分析师' }, { u: 'viewer', n: '查看者' }
]

function fill(u) {
  form.value.username = u
  form.value.password = '123456'
}

async function doLogin() {
  if (!form.value.username) return ElMessage.warning('请输入用户名')
  if (!form.value.password) return ElMessage.warning('请输入密码')
  loading.value = true
  try {
    const res = await api.login(form.value)
    if (res.data && res.data.mfaRequired) {
      mfaToken.value = res.data.mfaToken
      mfaDialog.value = true
      return
    }
    finishLogin(res.data)
  } catch (e) {} finally { loading.value = false }
}

function finishLogin(data) {
  localStorage.setItem('rbi_token', data.token)
  localStorage.setItem('rbi_user', JSON.stringify(data))
  if (remember.value) localStorage.setItem('rbi_last_user', data.username || form.value.username)
  else localStorage.removeItem('rbi_last_user')
  ElMessage.success('登录成功')
  if (data.mustChangePwd) {
    ElMessage.warning('当前为初始/临时密码，请先修改密码')
    router.push('/profile')
  } else {
    router.push('/home')
  }
}

// 二次验证
const mfaDialog = ref(false)
const mfaCode = ref('')
const mfaToken = ref('')
const verifying = ref(false)
async function submitMfa() {
  if (!/^\d{6}$/.test(mfaCode.value)) return ElMessage.warning('请输入 6 位动态码')
  verifying.value = true
  try {
    const res = await api.loginMfa({ mfaToken: mfaToken.value, code: mfaCode.value })
    mfaDialog.value = false
    finishLogin(res.data)
  } catch (e) {} finally { verifying.value = false }
}

// 单点登录
const ssoEnabled = ref(false)
onMounted(async () => {
  const last = localStorage.getItem('rbi_last_user')
  if (last) form.value.username = last
  try {
    const res = await api.oidcConfig()
    ssoEnabled.value = !!res.data?.enabled
  } catch (e) { ssoEnabled.value = false }
})
async function ssoLogin() {
  try {
    const res = await api.oidcAuthorize()
    if (res.data?.url) location.href = res.data.url
  } catch (e) { /* 拦截器已提示 */ }
}

// 找回密码
const forgotDialog = ref(false)
const sending = ref(false)
const forgotForm = ref({ username: '' })
async function sendReset() {
  if (!forgotForm.value.username) return ElMessage.warning('请输入登录名')
  sending.value = true
  try {
    const res = await api.forgotPassword({ username: forgotForm.value.username })
    const msg = typeof res?.data === 'string' ? res.data : '如果账号存在，重置邮件已发送，请查收邮箱'
    ElMessage.success(msg)
    forgotDialog.value = false
  } catch (e) {} finally { sending.value = false }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh; display: grid; place-items: center;
  background: radial-gradient(1200px 800px at 20% 0%, #1e1b4b 0%, #0b1020 55%, #070a14 100%);
  position: relative; overflow: hidden; padding: 24px;
}
.bg-grid {
  position: absolute; inset: 0;
  background-image: linear-gradient(rgba(148, 163, 184, .07) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 184, .07) 1px, transparent 1px);
  background-size: 46px 46px;
  mask-image: radial-gradient(circle at 50% 40%, #000 35%, transparent 78%);
}
.bg-glow { position: absolute; border-radius: 50%; filter: blur(10px); opacity: .55; }
.bg-glow.one { width: 520px; height: 520px; top: -180px; left: -120px; background: radial-gradient(circle, rgba(99, 102, 241, .55), transparent 65%); }
.bg-glow.two { width: 460px; height: 460px; bottom: -180px; right: -100px; background: radial-gradient(circle, rgba(139, 92, 246, .4), transparent 65%); }

.login-card {
  position: relative; z-index: 2;
  display: grid; grid-template-columns: 1.02fr .98fr;
  width: min(980px, 94vw); min-height: 566px;
  background: #fff; border-radius: 22px; overflow: hidden;
  box-shadow: 0 44px 90px -40px rgba(0, 0, 0, .85), 0 0 0 1px rgba(255, 255, 255, .06);
}

.brand-panel {
  padding: 44px 44px 34px; color: #e5e7eb;
  background: linear-gradient(155deg, #1e1b4b 0%, #312e81 48%, #4c1d95 100%);
  display: flex; flex-direction: column; justify-content: space-between;
  position: relative; overflow: hidden;
}
.brand-panel::after {
  content: ''; position: absolute; width: 320px; height: 320px; right: -120px; bottom: -120px;
  background: radial-gradient(circle, rgba(165, 180, 252, .35), transparent 62%);
}
.brand-top { display: flex; align-items: center; gap: 12px; }
.brand-mark {
  width: 40px; height: 40px; border-radius: 13px; display: grid; place-items: center;
  background: linear-gradient(135deg, #6366f1, #a855f7); font-weight: 800; font-size: 20px; color: #fff;
  box-shadow: 0 10px 24px -10px rgba(139, 92, 246, .95);
}
.brand-name strong { display: block; font-size: 14px; color: #f3f4f6; letter-spacing: .3px; }
.brand-name span { font-size: 10px; color: #a5b4fc; letter-spacing: 1.4px; text-transform: uppercase; }

.brand-copy h1 { font-size: 34px; line-height: 1.25; margin: 0 0 14px; letter-spacing: -.5px; color: #fff; }
.brand-copy p { margin: 0 0 22px; font-size: 13px; line-height: 1.75; color: #c7d2fe; max-width: 330px; }
.points { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 12px; }
.points li { display: flex; gap: 10px; font-size: 12.5px; color: #dbeafe; line-height: 1.5; }
.points .dot {
  width: 6px; height: 6px; margin-top: 6px; flex: 0 0 6px; border-radius: 50%;
  background: linear-gradient(135deg, #a5b4fc, #c4b5fd);
  box-shadow: 0 0 0 3px rgba(165, 180, 252, .18);
}
.brand-foot { font-size: 10px; letter-spacing: 3.4px; color: rgba(199, 210, 254, .45); position: relative; z-index: 1; }

.form-panel { padding: 46px 48px; display: flex; flex-direction: column; justify-content: center; }
.form-head h2 { font-size: 22px; margin: 0 0 6px; color: var(--ink-900); letter-spacing: -.2px; }
.form-head p { margin: 0 0 24px; font-size: 12.5px; color: var(--ink-500); }
.login-form :deep(.el-input) { margin-bottom: 14px; }
.login-form :deep(.el-input__wrapper) { padding: 6px 12px; box-shadow: 0 0 0 1px var(--line) inset; }
.login-form :deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 1px var(--brand) inset; }
.row { display: flex; align-items: center; justify-content: space-between; margin: 2px 0 18px; font-size: 12.5px; }
.submit {
  width: 100%; height: 44px; border: none; font-weight: 600; font-size: 14px; letter-spacing: 4px;
  color: #fff;
  background: linear-gradient(135deg, #4f46e5, #7c3aed);
  box-shadow: 0 12px 26px -14px rgba(79, 70, 229, .95);
}
.submit:hover, .submit:focus { color: #fff; background: linear-gradient(135deg, #4338ca, #6d28d9); }
.sso {
  width: 100%; height: 42px; margin: 12px 0 0; border-radius: 10px;
  background: #f5f3ff; border: 1px solid #ddd6fe; color: #4338ca; font-weight: 500;
}
.sso:hover { background: #ede9fe; border-color: #c4b5fd; color: #4338ca; }

.demo { margin-top: 26px; padding-top: 18px; border-top: 1px dashed var(--line); }
.demo-label { font-size: 11.5px; color: var(--ink-400); }
.accounts { margin-top: 10px; display: flex; gap: 8px; flex-wrap: wrap; }
.chip {
  display: inline-flex; align-items: center; gap: 6px; cursor: pointer;
  padding: 6px 12px; border-radius: 999px; font-size: 11.5px;
  border: 1px solid var(--line); background: #f8fafc; color: var(--ink-500);
  transition: all .15s ease; font-family: inherit;
}
.chip b { color: var(--ink-700); font-weight: 600; }
.chip:hover { border-color: #c7d2fe; background: #eef2ff; color: var(--brand-strong); }

@media (max-width: 880px) {
  .login-card { grid-template-columns: 1fr; min-height: auto; }
  .brand-panel { display: none; }
  .form-panel { padding: 32px 26px; }
}
</style>
