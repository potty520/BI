<template>
  <div>
    <div class="page-card">
      <div class="page-header">
        <div><h3 class="page-title">部署与运维</h3>
          <div class="page-desc">单体部署、集群、节点监控、健康检查、备份恢复</div></div>
        <el-button @click="load"><el-icon><Refresh /></el-icon>刷新监控</el-button>
      </div>
      <el-row :gutter="16">
        <el-col :span="8" v-for="c in cards" :key="c.label">
          <div class="stat-card">
            <div class="stat-icon" :style="{background:c.color}"><el-icon :size="22"><component :is="c.icon"/></el-icon></div>
            <div><div class="stat-value">{{ c.value }}</div><div class="stat-label">{{ c.label }}</div></div>
          </div>
        </el-col>
      </el-row>
    </div>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="14">
        <div class="page-card">
          <div class="section-title">部署形态说明</div>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="单体部署">前后端打包为单个可执行 Jar，内嵌 Tomcat，一条命令启动：<code class="mono">java -jar report-bi-platform.jar</code></el-descriptions-item>
            <el-descriptions-item label="集群部署">多节点部署，会话共享（Redis），配置集中存储（数据库）；当前演示为单节点。</el-descriptions-item>
            <el-descriptions-item label="节点监控">暴露 <code class="mono">/api/admin/health</code> 健康检查接口，展示 CPU/内存/在线用户/请求量。</el-descriptions-item>
            <el-descriptions-item label="备份恢复">模板、配置、权限数据定期备份；建议生产环境结合定时任务与对象存储。</el-descriptions-item>
          </el-descriptions>
          <div class="section-title">JVM 运行指标（实时）</div>
          <el-descriptions :column="2" border v-if="health">
            <el-descriptions-item label="节点">{{ health.node }}</el-descriptions-item>
            <el-descriptions-item label="状态"><el-tag type="success">{{ health.status }}</el-tag></el-descriptions-item>
            <el-descriptions-item label="CPU 核心数">{{ health.cpuCores }}</el-descriptions-item>
            <el-descriptions-item label="已运行">{{ health.uptimeSec }} 秒</el-descriptions-item>
            <el-descriptions-item label="最大堆内存">{{ health.maxMemoryMB }} MB</el-descriptions-item>
            <el-descriptions-item label="已用堆内存">{{ health.usedMemoryMB }} MB</el-descriptions-item>
          </el-descriptions>
        </div>
      </el-col>
      <el-col :span="10">
        <div class="page-card">
          <div class="section-title">健康检查接口返回</div>
          <div class="code-block">{{ JSON.stringify(healthRaw,null,2) }}</div>
          <div class="section-title">平台资源概览</div>
          <el-descriptions :column="1" border v-if="overview">
            <el-descriptions-item label="用户数">{{ overview.users }}</el-descriptions-item>
            <el-descriptions-item label="调度任务数">{{ overview.schedules }}</el-descriptions-item>
          </el-descriptions>
        </div>
      </el-col>
    </el-row>
  </div>
</template>
<script setup>
import { ref, computed, onMounted } from 'vue'
import { Refresh, Cpu, Coin, Monitor } from '@element-plus/icons-vue'
import { api } from '../api'
const health=ref(null), healthRaw=ref(null), overview=ref(null)
const cards=computed(()=>[
  {label:'节点状态',value:health.value?.status||'-',color:'#16a34a',icon:Monitor},
  {label:'CPU 核心',value:health.value?.cpuCores??'-',color:'#2563eb',icon:Cpu},
  {label:'已用堆内存(MB)',value:health.value?.usedMemoryMB??'-',color:'#ea580c',icon:Coin}
])
async function load(){
  const h=await api.health(); health.value=h.data; healthRaw.value=h.data
  overview.value=(await api.overview()).data
}
onMounted(load)
</script>
