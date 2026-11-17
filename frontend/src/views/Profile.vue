<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h3 class="page-title"><span class="module-tag">账号</span>个人设置</h3>
        <div class="page-desc">修改个人资料与登录密码。首次登录或管理员重置密码后，必须先修改密码。</div>
      </div>
    </div>

    <el-alert v-if="mustChange" type="warning" :closable="false" show-icon style="margin-bottom:16px">
      当前账号使用的是初始/临时密码，请先修改密码后再继续使用系统。
    </el-alert>

    <el-row :gutter="20">
      <el-col :span="12">
        <div class="panel">
          <div class="panel-title">基本资料</div>
          <el-form :model="profile" label-width="90px">
            <el-form-item label="用户名">
              <el-input :model-value="profile.username" disabled />
            </el-form-item>
            <el-form-item label="角色">
              <el-tag v-for="r in profile.roles" :key="r" size="small" effect="plain" style="margin-right:6px">{{ r }}</el-tag>
            </el-form-item>
            <el-form-item label="姓名">
              <el-input v-model="profile.realName" maxlength="64" show-word-limit />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingProfile" @click="saveProfile">保存资料</el-button>
            </el-form-item>
          </el-form>
        </div>
      </el-col>

      <el-col :span="12">
        <div class="panel">
          <div class="panel-title">修改密码</div>
          <el-form :model="pwd" label-width="90px">
            <el-form-item label="原密码">
              <el-input v-model="pwd.oldPassword" type="password" show-password autocomplete="current-password" />
            </el-form-item>
            <el-form-item label="新密码">
              <el-input v-model="pwd.newPassword" type="password" show-password autocomplete="new-password" placeholder="至少 8 位，含字母和数字" />
            </el-form-item>
            <el-form-item label="确认新密码">
              <el-input v-model="pwd.confirm" type="password" show-password autocomplete="new-password" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingPwd" @click="savePassword">修改密码</el-button>
            </el-form-item>
          </el-form>
          <div class="hint" style="margin-top:4px">密码规则：至少 8 位，需同时包含字母和数字，且不能与用户名相同。修改成功后需重新登录。</div>
        </div>
      </el-col>
    </el-row>
    <el-row :gutter="20" style="margin-top:16px">
      <el-col :span="12">
        <div class="panel">
          <div class="panel-title">两步验证（TOTP）</div>
          <div style="margin-bottom:10px">
            状态：
            <el-tag :type="mfa.enabled ? 'success' : 'info'" size="small">{{ mfa.enabled ? '已启用' : '未启用' }}</el-tag>
          </div>
          <div class="hint" style="margin-bottom:10px">
            用 Google/Microsoft Authenticator 扫码或手动录入密钥，再输入一次 6 位动态码即可启用。
          </div>
          <el-form label-width="90px">
            <el-form-item v-if="mfa.secret" label="密钥">
              <el-input v-model="mfa.secret" readonly />
            </el-form-item>
            <el-form-item label="动态码">
              <el-input v-model="mfa.code" maxlength="6" placeholder="6 位动态码" />
            </el-form-item>
            <el-form-item>
              <el-button @click="setupMfa">生成密钥</el-button>
              <el-button type="primary" @click="enableMfa">启用</el-button>
              <el-button v-if="mfa.enabled" type="danger" @click="disableMfa">关闭</el-button>
            </el-form-item>
          </el-form>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="panel">
          <div class="panel-title">登录会话（可单设备踢出）</div>
          <div class="hint" style="margin-bottom:10px">
            每台设备一条会话；注销某条只影响该设备，改密或「退出所有会话」会让全部令牌失效。
          </div>
          <el-table :data="sessions" size="small" border max-height="220">
            <el-table-column label="IP" prop="ip" width="120"/>
            <el-table-column label="设备" prop="userAgent" show-overflow-tooltip/>
            <el-table-column label="最近活跃" prop="lastSeen" width="150"/>
            <el-table-column label="操作" width="90">
              <template #default="{row}">
                <el-tag v-if="row.current" size="small" type="success">当前</el-tag>
                <el-button v-else link type="danger" size="small" @click="revoke(row)">注销</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button type="danger" style="margin-top:10px" @click="logoutAll">退出所有会话</el-button>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const router = useRouter()
const profile = ref({ username: '', realName: '', roles: [] })
const pwd = ref({ oldPassword: '', newPassword: '', confirm: '' })
const savingProfile = ref(false)
const savingPwd = ref(false)
const mustChange = computed(() => !!profile.value.mustChangePwd)
const mfa = ref({ enabled: false, secret: '', code: '' })
const sessions = ref([])

async function load() {
  const res = await api.me()
  profile.value = { ...res.data, roles: res.data.roles || [] }
  mfa.value.enabled = !!res.data.mfaEnabled
  localStorage.setItem('rbi_user', JSON.stringify({ ...JSON.parse(localStorage.getItem('rbi_user') || '{}'), ...res.data }))
  await loadSessions()
}

async function loadSessions() {
  try { sessions.value = (await api.sessions()).data || [] } catch (e) { sessions.value = [] }
}

async function revoke(row) {
  await api.revokeSession(row.jti)
  ElMessage.success('该设备会话已注销')
  await loadSessions()
}

async function setupMfa() {
  const res = await api.mfaSetup()
  mfa.value.secret = res.data.secret
  ElMessage.success('已生成密钥，请在认证器中录入后输入动态码启用')
  if (res.data.otpauth) ElMessage.info({ message: '可复制到认证器：' + res.data.otpauth, duration: 8000 })
}

async function enableMfa() {
  if (!/^\d{6}$/.test(mfa.value.code)) return ElMessage.warning('请输入 6 位动态码')
  await api.mfaEnable({ code: mfa.value.code })
  ElMessage.success('已启用两步验证')
  mfa.value.code = ''
  await load()
}

async function disableMfa() {
  if (!/^\d{6}$/.test(mfa.value.code)) return ElMessage.warning('关闭前请输入当前动态码')
  await api.mfaDisable({ code: mfa.value.code })
  ElMessage.success('已关闭两步验证')
  mfa.value.code = ''
  await load()
}

async function logoutAll() {
  await api.logoutAll()
  localStorage.removeItem('rbi_token')
  localStorage.removeItem('rbi_user')
  ElMessage.success('所有会话已失效，请重新登录')
  router.push('/login')
}

async function saveProfile() {
  if (!profile.value.realName) return ElMessage.warning('请填写姓名')
  savingProfile.value = true
  try {
    await api.updateProfile({ realName: profile.value.realName })
    ElMessage.success('资料已保存')
    await load()
  } finally { savingProfile.value = false }
}

async function savePassword() {
  if (!pwd.value.oldPassword) return ElMessage.warning('请输入原密码')
  if (pwd.value.newPassword !== pwd.value.confirm) return ElMessage.warning('两次输入的新密码不一致')
  savingPwd.value = true
  try {
    await api.changePassword({ oldPassword: pwd.value.oldPassword, newPassword: pwd.value.newPassword })
    ElMessage.success('密码已修改，请重新登录')
    localStorage.removeItem('rbi_token')
    localStorage.removeItem('rbi_user')
    router.push('/login')
  } finally { savingPwd.value = false }
}

onMounted(load)
</script>

<style scoped>
.panel { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; padding: 18px; }
.panel-title { font-weight: 600; font-size: 14px; margin-bottom: 14px; color: #0f172a; }
</style>
