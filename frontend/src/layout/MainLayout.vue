<template>
  <el-container class="app-shell">
    <el-aside :width="collapsed ? '68px' : '236px'" class="aside">
      <div class="logo" @click="$router.push('/home')">
        <div class="logo-mark">R</div>
        <div v-if="!collapsed" class="logo-text">自研报表与BI平台<small>Report &amp; BI Platform</small></div>
      </div>
      <el-scrollbar>
        <el-menu
          :default-active="$route.path"
          :collapse="collapsed"
          :collapse-transition="false"
          background-color="transparent"
          text-color="#9aa4b2"
          active-text-color="#c7d2fe"
          router
          unique-opened
        >
          <el-menu-item index="/home"><el-icon><House /></el-icon><span>工作台</span></el-menu-item>

          <!-- 数据接入与建模：仅管理员/分析师可见（后端同样有鉴权，这里只是不展示无效入口） -->
          <template v-if="canEdit">
            <div v-if="!collapsed" class="nav-group">数据接入</div>
            <el-menu-item index="/datasource"><el-icon><Coin /></el-icon><span>数据源</span></el-menu-item>
            <el-menu-item index="/dataset"><el-icon><Files /></el-icon><span>数据集</span></el-menu-item>
          </template>

          <div v-if="!collapsed" class="nav-group">报表与分析</div>
          <el-sub-menu index="report">
            <template #title><el-icon><Grid /></el-icon><span>报表</span></template>
            <el-menu-item v-if="canEdit" index="/designer">设计器</el-menu-item>
            <el-menu-item index="/reports">报表管理</el-menu-item>
          </el-sub-menu>
          <el-menu-item v-if="canEdit" index="/params"><el-icon><Operation /></el-icon><span>参数与查询</span></el-menu-item>

          <el-sub-menu index="chart">
            <template #title><el-icon><TrendCharts /></el-icon><span>可视化</span></template>
            <el-menu-item v-if="canEdit" index="/charts">图表设计</el-menu-item>
            <el-menu-item index="/dashboard">仪表板大屏</el-menu-item>
          </el-sub-menu>

          <el-menu-item index="/form"><el-icon><EditPen /></el-icon><span>数据填报</span></el-menu-item>
          <el-menu-item index="/analysis"><el-icon><MagicStick /></el-icon><span>自助分析</span></el-menu-item>
          <el-menu-item index="/metrics"><el-icon><Odometer /></el-icon><span>指标管理</span></el-menu-item>

          <div v-if="isAdmin && !collapsed" class="nav-group">平台</div>
          <el-sub-menu v-if="isAdmin" index="admin">
            <template #title><el-icon><Setting /></el-icon><span>平台管理</span></template>
            <el-menu-item index="/admin/users">用户与角色</el-menu-item>
            <el-menu-item index="/admin/directory">目录与权限</el-menu-item>
            <el-menu-item index="/admin/schedule">定时调度</el-menu-item>
            <el-menu-item index="/admin/logs">操作日志</el-menu-item>
          </el-sub-menu>

          <el-menu-item v-if="isAdmin" index="/ops"><el-icon><Monitor /></el-icon><span>部署运维</span></el-menu-item>
        </el-menu>
      </el-scrollbar>
      <div class="aside-footer" v-if="!collapsed">
        <span class="dot" :class="{ bad: healthText !== '系统运行中' }"></span> {{ healthText }}
      </div>
    </el-aside>

    <el-container class="main-area">
      <el-header class="header">
        <div class="header-left">
          <el-button text class="collapse-btn" @click="collapsed = !collapsed">
            <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
          </el-button>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>报表与BI平台</el-breadcrumb-item>
            <el-breadcrumb-item>{{ $route.meta.title || '首页' }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-tag :type="isAdmin ? 'danger' : 'info'" size="small" effect="light" round>{{ roleLabel }}</el-tag>
          <el-dropdown @command="onUserCmd">
            <span class="user-chip">
              <el-avatar :size="30" class="avatar">{{ (userInfo.realName || '用').charAt(0) }}</el-avatar>
              <span class="user-name">{{ userInfo.realName || userInfo.username || '用户' }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ userInfo.username }}</el-dropdown-item>
                <el-dropdown-item command="profile" divided>个人设置</el-dropdown-item>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  House, Coin, Files, Grid, Operation, TrendCharts, EditPen, MagicStick,
  Odometer, Setting, Monitor, Expand, Fold, ArrowDown
} from '@element-plus/icons-vue'
import { api } from '../api'

const router = useRouter()
const collapsed = ref(false)
const userInfo = ref(JSON.parse(localStorage.getItem('rbi_user') || '{}'))
const healthText = ref('服务检查中')

const roles = computed(() => (userInfo.value.roles || []).map(r => String(r).toUpperCase()))
const isAdmin = computed(() => roles.value.includes('ADMIN'))
const canEdit = computed(() => isAdmin.value || roles.value.includes('ANALYST'))
const roleLabel = computed(() => {
  if (isAdmin.value) return '管理员'
  if (canEdit.value) return '分析师'
  return '查看者'
})

onMounted(async () => {
  if (!localStorage.getItem('rbi_token')) {
    router.push('/login')
    return
  }
  try {
    // 用服务端校验令牌有效性，localStorage 只是缓存
    const res = await api.me()
    const me = res.data || {}
    userInfo.value = { ...userInfo.value, ...me }
    localStorage.setItem('rbi_user', JSON.stringify(userInfo.value))
    const h = await api.publicHealth()
    healthText.value = h.data?.status === 'UP' ? '系统运行中' : '服务异常'
    if (me.mustChangePwd && router.currentRoute.value.path !== '/profile') {
      ElMessage.warning('当前为初始/临时密码，请先修改密码')
      router.push('/profile')
    }
  } catch (e) {
    /* 401 已由拦截器跳转登录页 */
  }
})

function onUserCmd(cmd) {
  if (cmd === 'profile') { router.push('/profile'); return }
  if (cmd === 'logout') {
    localStorage.removeItem('rbi_token')
    localStorage.removeItem('rbi_user')
    router.push('/login')
  }
}
</script>

<style scoped>
.app-shell { height: 100vh; }

.aside {
  background: linear-gradient(180deg, #0b1020 0%, #121830 55%, #0b1020 100%);
  display: flex;
  flex-direction: column;
  border-right: 1px solid rgba(255, 255, 255, .06);
  transition: width .2s ease;
  overflow: hidden;
}
.logo { display: flex; align-items: center; gap: 12px; padding: 18px 16px 14px; cursor: pointer; }
.logo-mark {
  width: 38px; height: 38px; flex: 0 0 38px; border-radius: 12px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff; font-weight: 800; font-size: 19px;
  display: grid; place-items: center;
  box-shadow: 0 8px 20px -8px rgba(99, 102, 241, .9);
}
.logo-text { color: #e5e7eb; font-size: 14px; font-weight: 600; line-height: 1.25; white-space: nowrap; }
.logo-text small { display: block; color: #6b7280; font-size: 10px; font-weight: 400; letter-spacing: .4px; margin-top: 3px; }

.aside :deep(.el-scrollbar) { flex: 1; }
.aside :deep(.el-menu) { border-right: none; background: transparent; padding-bottom: 8px; }
.aside :deep(.el-menu-item),
.aside :deep(.el-sub-menu__title) {
  height: 42px; line-height: 42px; margin: 3px 10px; border-radius: 10px;
  color: #9aa4b2; font-size: 13px;
}
.aside :deep(.el-menu-item:hover),
.aside :deep(.el-sub-menu__title:hover) { background: rgba(255, 255, 255, .06); color: #e5e7eb; }
.aside :deep(.el-menu-item.is-active) {
  background: linear-gradient(90deg, rgba(99, 102, 241, .26), rgba(139, 92, 246, .12));
  color: #c7d2fe;
  box-shadow: inset 0 0 0 1px rgba(129, 140, 248, .28);
}
.aside :deep(.el-sub-menu.is-active > .el-sub-menu__title) { color: #c7d2fe; }
.aside :deep(.el-menu--inline) { background: transparent; }
.aside :deep(.el-menu--inline .el-menu-item) { margin: 2px 10px 2px 18px; height: 38px; line-height: 38px; font-size: 12.5px; }

.nav-group {
  padding: 14px 22px 6px; font-size: 10.5px; letter-spacing: 1.2px;
  color: #4b5563; text-transform: uppercase; font-weight: 600;
}

.aside-footer {
  display: flex; align-items: center; gap: 8px;
  padding: 12px 18px; font-size: 11.5px; color: #6b7280;
  border-top: 1px solid rgba(255, 255, 255, .06);
}
.dot { width: 7px; height: 7px; border-radius: 50%; background: #22c55e; box-shadow: 0 0 0 3px rgba(34, 197, 94, .16); }
.dot.bad { background: #ef4444; box-shadow: 0 0 0 3px rgba(239, 68, 68, .16); }

.main-area { background: var(--bg); }
.header {
  height: 58px; display: flex; align-items: center; justify-content: space-between;
  padding: 0 18px; background: rgba(255, 255, 255, .82);
  backdrop-filter: saturate(180%) blur(12px);
  border-bottom: 1px solid var(--line);
}
.header-left { display: flex; align-items: center; gap: 10px; }
.header-right { display: flex; align-items: center; gap: 12px; }
.collapse-btn { color: var(--ink-500); }
.user-chip { display: flex; align-items: center; gap: 8px; cursor: pointer; color: var(--ink-700); font-size: 13px; }
.user-name { max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.avatar { background: linear-gradient(135deg, #6366f1, #8b5cf6) !important; font-weight: 600; }
.content { padding: 18px; overflow: auto; }
</style>
