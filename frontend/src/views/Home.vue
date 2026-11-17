<template>
  <div>
    <div class="hero">
      <div>
        <h2>自研报表与BI平台</h2>
        <p>以帆软 FineReport + FineBI 功能体系为参照，覆盖数据源、数据集、报表设计、参数查询、可视化、填报、自助分析、指标、平台管理与运维全链路。</p>
        <div class="hero-tags">
          <el-tag v-for="m in modules" :key="m.code" size="small" effect="dark" :type="m.type">{{ m.code }} {{ m.name }}</el-tag>
        </div>
      </div>
      <div class="hero-illustration">
        <div class="bar" v-for="(h,i) in [40,65,50,80,60,95,70]" :key="i" :style="{ height: h + '%', animationDelay: (i*0.1)+'s' }"></div>
      </div>
    </div>

    <el-row :gutter="16" class="stats">
      <el-col :span="6" v-for="s in stats" :key="s.label">
        <div class="stat-card">
          <div class="stat-icon" :style="{ background: s.color }"><el-icon :size="24"><component :is="s.icon" /></el-icon></div>
          <div><div class="stat-value">{{ s.value }}</div><div class="stat-label">{{ s.label }}</div></div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="14">
        <div class="page-card">
          <div class="page-header"><div><h3 class="page-title">快速开始</h3><div class="page-desc">按推荐路径体验平台核心能力</div></div></div>
          <el-steps direction="vertical" :active="4" finish-status="success">
            <el-step title="M01 数据源接入" description="查看内置演示数据源，或新增 JDBC / 文件 / API 数据源并测试连通性" />
            <el-step title="M02 数据集管理" description="SQL 数据集支持 ${param} 占位符，在线预览执行结果" />
            <el-step title="M03 报表设计器（核心）" description="拖拽字段绑定单元格，设置纵向/横向扩展与父格，实时预览扩展效果与公式计算" />
            <el-step title="M04 ~ M10 能力扩展" description="参数联动、图表大屏、填报入库、自助分析、指标、权限、调度、运维监控" />
          </el-steps>
        </div>
      </el-col>
      <el-col :span="10">
        <div class="page-card">
          <div class="page-header"><div><h3 class="page-title">技术架构</h3><div class="page-desc">前后端分离 · 单Jar可部署</div></div></div>
          <div class="arch">
            <div class="arch-row"><span class="arch-label">前端</span><span>Vue 3 · Vite · Element Plus · ECharts · Pinia</span></div>
            <div class="arch-row"><span class="arch-label">后端</span><span>Java 8 · Spring Boot 2.7 · MyBatis-Plus · JWT</span></div>
            <div class="arch-row"><span class="arch-label">存储</span><span>H2 内嵌库（演示）· 可扩展 MySQL/Oracle/达梦/金仓/GaussDB</span></div>
            <div class="arch-row"><span class="arch-label">核心引擎</span><span>单元格扩展引擎（父格/扩展方向）· 公式引擎 · SQL安全替换器</span></div>
            <div class="arch-row"><span class="arch-label">部署</span><span>前后端打包单 Jar，一条命令启动（M10-01）</span></div>
          </div>
          <div class="section-title">演示数据</div>
          <div class="hint">内置 demo_sales 销售明细 64 行，覆盖 5 大区、多省份、4 类产品的 12 个月数据，供设计器、图表、自助分析统一使用。</div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Coin, Files, Grid, TrendCharts, Setting } from '@element-plus/icons-vue'
import { api } from '../api'

const modules = [
  { code:'M01', name:'数据源', type:'primary' }, { code:'M02', name:'数据集', type:'success' },
  { code:'M03', name:'设计器', type:'danger' }, { code:'M04', name:'参数', type:'warning' },
  { code:'M05', name:'图表', type:'primary' }, { code:'M06', name:'填报', type:'success' },
  { code:'M07', name:'自助分析', type:'danger' }, { code:'M08', name:'指标', type:'warning' },
  { code:'M09', name:'平台管理', type:'primary' }, { code:'M10', name:'运维', type:'info' }
]
const stats = ref([
  { label:'报表模板', value:'-', color:'#2563eb', icon:Grid },
  { label:'数据集', value:'-', color:'#0891b2', icon:Files },
  { label:'数据源', value:'-', color:'#7c3aed', icon:Coin },
  { label:'可视化图表', value:'-', color:'#ea580c', icon:TrendCharts }
])

onMounted(async () => {
  try {
    const [reports, datasets, sources, charts] = await Promise.all([
      api.reports(), api.datasets(), api.datasources(), api.charts()
    ])
    stats.value[0].value = reports.data.length
    stats.value[1].value = datasets.data.length
    stats.value[2].value = sources.data.length
    stats.value[3].value = charts.data.length
  } catch (e) {}
})
</script>

<style scoped>
.hero { background: linear-gradient(120deg,#0f172a,#1e3a5f 55%,#0f766e); border-radius: 14px; padding: 28px 32px; color: #fff; display: flex; justify-content: space-between; align-items: center; position: relative; overflow: hidden; }
.hero h2 { margin: 0 0 10px; font-size: 24px; }
.hero p { margin: 0 0 16px; color: #cbd5e1; font-size: 13px; max-width: 720px; line-height: 1.7; }
.hero-tags { display: flex; gap: 6px; flex-wrap: wrap; }
.hero-tags .el-tag { border: none; }
.hero-illustration { display: flex; align-items: flex-end; gap: 10px; height: 120px; width: 200px; flex: 0 0 200px; opacity: .8; }
.hero-illustration .bar { flex: 1; background: linear-gradient(180deg,#38bdf8,#2563eb); border-radius: 6px 6px 0 0; animation: grow 1.2s ease-out both; }
@keyframes grow { from { height: 0; } }
.stats { margin-top: 16px; }
.arch { display: flex; flex-direction: column; gap: 2px; }
.arch-row { display: flex; gap: 10px; padding: 9px 0; border-bottom: 1px dashed #e2e8f0; font-size: 12px; align-items: flex-start; line-height: 1.5; }
.arch-label { flex: 0 0 64px; color: #2563eb; font-weight: 600; }
</style>
