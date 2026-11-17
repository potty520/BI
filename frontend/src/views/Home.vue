<template>
  <div>
    <div class="hero">
      <div>
        <h2>自研报表与BI平台</h2>
        <p>从数据接入、数据集建模到复杂报表、可视化大屏、数据填报与自助分析，一套平台覆盖 BI 全链路；权限、水印、调度与审计开箱可用。</p>
        <div class="hero-tags">
          <el-tag v-for="m in modules" :key="m.name" size="small" effect="dark" :type="m.type">{{ m.name }}</el-tag>
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
            <el-step title="数据源接入" description="查看内置演示数据源，或新增 JDBC / 文件 / API 数据源并测试连通性" />
            <el-step title="数据集管理" description="SQL 数据集支持 ${param} 占位符，在线预览执行结果" />
            <el-step title="报表设计器（核心）" description="拖拽字段绑定单元格，设置纵向/横向扩展与父格，实时预览扩展效果与公式计算" />
            <el-step title="扩展能力" description="参数联动、图表大屏、填报入库、自助分析、指标、权限、调度、运维监控" />
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
            <div class="arch-row"><span class="arch-label">部署</span><span>前后端打包单 Jar，一条命令启动</span></div>
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
  { name:'数据源', type:'primary' }, { name:'数据集', type:'success' },
  { name:'设计器', type:'danger' }, { name:'参数', type:'warning' },
  { name:'图表', type:'primary' }, { name:'填报', type:'success' },
  { name:'自助分析', type:'danger' }, { name:'指标', type:'warning' },
  { name:'平台管理', type:'primary' }, { name:'运维', type:'info' }
]
const stats = ref([
  { label:'报表模板', value:'-', color:'#4f46e5', icon:Grid },
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
.hero {
  background: linear-gradient(120deg, #1e1b4b 0%, #312e81 55%, #4c1d95 100%);
  border-radius: 16px; padding: 28px 32px; color: #fff;
  display: flex; justify-content: space-between; align-items: center;
  position: relative; overflow: hidden;
  box-shadow: 0 24px 50px -30px rgba(49, 46, 129, .9);
}
.hero::after {
  content: ''; position: absolute; width: 420px; height: 420px; right: -140px; top: -160px;
  background: radial-gradient(circle, rgba(165, 180, 252, .28), transparent 62%);
}
.hero h2 { margin: 0 0 10px; font-size: 24px; letter-spacing: -.3px; }
.hero p { margin: 0 0 16px; color: #c7d2fe; font-size: 13px; max-width: 720px; line-height: 1.7; }
.hero-tags { display: flex; gap: 6px; flex-wrap: wrap; position: relative; z-index: 1; }
.hero-tags .el-tag { border: none; }
.hero-illustration { display: flex; align-items: flex-end; gap: 10px; height: 120px; width: 200px; flex: 0 0 200px; opacity: .9; position: relative; z-index: 1; }
.hero-illustration .bar { flex: 1; background: linear-gradient(180deg, #c7d2fe, #6366f1); border-radius: 6px 6px 0 0; animation: grow 1.2s ease-out both; }
@keyframes grow { from { height: 0; } }
.stats { margin-top: 16px; }
.arch { display: flex; flex-direction: column; gap: 2px; }
.arch-row { display: flex; gap: 10px; padding: 9px 0; border-bottom: 1px dashed #e6e9f2; font-size: 12px; align-items: flex-start; line-height: 1.5; }
.arch-label { flex: 0 0 64px; color: var(--brand-strong); font-weight: 600; }

/* 步骤条改用品牌色（默认的绿色与整体靛蓝配色冲突） */
:deep(.el-step__head.is-success .el-step__icon) { background: var(--brand); border-color: var(--brand); color: #fff; }
:deep(.el-step__head.is-process .el-step__icon) { border-color: var(--brand); color: var(--brand); }
:deep(.el-step__title.is-success), :deep(.el-step__title.is-finish) { color: var(--brand-strong); font-weight: 600; }
:deep(.el-step__description.is-success) { color: var(--ink-500); }
:deep(.el-step__line) { background-color: #e6e9f2 !important; }
:deep(.el-step__head.is-success .el-step__line),
:deep(.el-step__head.is-finish .el-step__line) { background-color: #d8deee !important; }
</style>
