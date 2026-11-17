<template>
  <el-container class="app-shell">
    <el-aside :width="collapsed ? '64px' : '230px'" class="aside">
      <div class="logo" @click="$router.push('/home')">
        <div class="logo-mark">R</div>
        <div v-if="!collapsed" class="logo-text">自研报表与BI平台<small>Report & BI Platform</small></div>
      </div>
      <el-scrollbar>
        <el-menu
          :default-active="$route.path"
          :collapse="collapsed"
          :collapse-transition="false"
          background-color="#0f172a"
          text-color="#94a3b8"
          active-text-color="#38bdf8"
          router
          unique-opened
        >
          <el-menu-item index="/home"><el-icon><House /></el-icon><span>工作台</span></el-menu-item>

          <el-menu-item index="/datasource"><el-icon><Coin /></el-icon><span>M01 数据源接入</span></el-menu-item>
          <el-menu-item index="/dataset"><el-icon><Files /></el-icon><span>M02 数据集管理</span></el-menu-item>

          <el-sub-menu index="m03">
            <template #title><el-icon><Grid /></el-icon><span>M03 报表设计器</span></template>
            <el-menu-item index="/designer">设计器（核心）</el-menu-item>
            <el-menu-item index="/reports">报表管理</el-menu-item>
          </el-sub-menu>

          <el-menu-item index="/params"><el-icon><Operation /></el-icon><span>M04 参数与查询</span></el-menu-item>

          <el-sub-menu index="m05">
            <template #title><el-icon><TrendCharts /></el-icon><span>M05 可视化图表</span></template>
            <el-menu-item index="/charts">图表设计</el-menu-item>
            <el-menu-item index="/dashboard">仪表板大屏</el-menu-item>
          </el-sub-menu>

          <el-menu-item index="/form"><el-icon><EditPen /></el-icon><span>M06 数据填报</span></el-menu-item>
          <el-menu-item index="/analysis"><el-icon><MagicStick /></el-icon><span>M07 自助分析</span></el-menu-item>
          <el-menu-item index="/metrics"><el-icon><Odometer /></el-icon><span>M08 指标管理</span></el-menu-item>

          <el-sub-menu index="m09">
            <template #title><el-icon><Setting /></el-icon><span>M09 平台管理</span></template>
            <el-menu-item index="/admin/users">用户与角色</el-menu-item>
            <el-menu-item index="/admin/directory">目录与权限</el-menu-item>
            <el-menu-item index="/admin/schedule">定时调度</el-menu-item>
            <el-menu-item index="/admin/logs">操作日志</el-menu-item>
          </el-sub-menu>

          <el-menu-item index="/ops"><el-icon><Monitor /></el-icon><span>M10 部署运维</span></el-menu-item>
        </el-menu>
      </el-scrollbar>
      <div class="aside-footer" v-if="!collapsed">
        <span class="dot"></span> 系统运行中 · v1.0.0
      </div>
    </el-aside>

    <el-container class="main-area">
      <el-header class="header">
        <div class="header-left">
          <el-button text @click="collapsed = !collapsed" class="collapse-btn">
            <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
          </el-button>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>报表与BI平台</el-breadcrumb-item>
            <el-breadcrumb-item>{{ $route.meta.title || '首页' }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-tag type="success" size="small" effect="dark">演示环境</el-tag>
          <el-dropdown @command="onUserCmd">
            <span class="user-chip">
              <el-avatar :size="30" style="background:#2563eb">{{ (userInfo.realName || '管').charAt(0) }}</el-avatar>
              <span>{{ userInfo.realName || userInfo.username || '用户' }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
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
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  House, Coin, Files, Grid, Operation, TrendCharts, EditPen, MagicStick,
  Odometer, Setting, Monitor, Expand, Fold, ArrowDown
} from '@element-plus/icons-vue'

const router = useRouter()
const collapsed = ref(false)
const userInfo = ref(JSON.parse(localStorage.getItem('rbi_user') || '{}'))

onMounted(() => {
  if (!localStorage.getItem('rbi_token')) router.push('/login')
})

function onUserCmd(cmd) {
  if (cmd === 'logout') {
    localStorage.removeItem('rbi_token')
    localStorage.removeItem('rbi_user')
    router.push('/login')
  }
}
</script>

<style scoped>
.app-shell { height: 100vh; }
.aside { background: #0f172a; display: flex; flex-direction: column; transition: width .2s; overflow: hidden; }
.logo { height: 60px; display: flex; align-items: center; gap: 10px; padding: 0 16px; cursor: pointer; border-bottom: 1px solid #1e293b; }
.logo-mark { width: 32px; height: 32px; flex: 0 0 32px; border-radius: 8px; background: linear-gradient(135deg,#2563eb,#38bdf8); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 800; font-size: 18px; }
.logo-text { color: #f1f5f9; font-weight: 700; font-size: 14px; line-height: 1.2; white-space: nowrap; }
.logo-text small { display: block; color: #64748b; font-weight: 400; font-size: 9px; letter-spacing: .5px; }
.aside .el-menu { border-right: none; flex: 1; }
.aside-footer { padding: 12px 16px; color: #64748b; font-size: 11px; border-top: 1px solid #1e293b; white-space: nowrap; }
.dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: #22c55e; margin-right: 6px; }
.main-area { background: #f1f5f9; }
.header { background: #fff; border-bottom: 1px solid #e2e8f0; display: flex; align-items: center; justify-content: space-between; padding: 0 20px; height: 60px; }
.header-left, .header-right { display: flex; align-items: center; gap: 14px; }
.collapse-btn { font-size: 20px; }
.user-chip { display: flex; align-items: center; gap: 8px; cursor: pointer; color: #334155; font-size: 13px; }
.content { padding: 18px; overflow: auto; height: calc(100vh - 60px); }
</style>
