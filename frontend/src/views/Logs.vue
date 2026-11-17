<template>
  <div class="page-card">
    <div class="page-header">
      <div><h3 class="page-title">操作日志</h3>
        <div class="page-desc">审计所有写操作：操作人、模块、接口、来源 IP、结果、耗时</div></div>
      <div class="toolbar">
        <el-input v-model="filters.username" placeholder="按用户筛选" clearable style="width:150px" @keyup.enter="load" />
        <el-input v-model="filters.module" placeholder="按模块筛选" clearable style="width:150px" @keyup.enter="load" />
        <el-button @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
        <el-button type="primary" @click="exportCsv"><el-icon><Download /></el-icon>导出审计日志</el-button>
      </div>
    </div>
    <el-table :data="list" border stripe size="small" v-loading="loading">
      <el-table-column prop="id" label="ID" width="70"/>
      <el-table-column prop="username" label="操作用户" width="120"/>
      <el-table-column prop="module" label="模块" width="120">
        <template #default="{row}"><el-tag size="small" effect="plain">{{ row.module }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="operation" label="接口 / 动作" min-width="260"/>
      <el-table-column label="结果" width="90">
        <template #default="{row}">
          <el-tag size="small" :type="row.status === 'FAIL' ? 'danger' : 'success'" effect="dark">
            {{ row.status === 'FAIL' ? '失败' : '成功' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="durationMs" label="耗时(ms)" width="100"/>
      <el-table-column prop="detail" label="详情" min-width="180" show-overflow-tooltip/>
      <el-table-column prop="ip" label="IP 地址" width="130"/>
      <el-table-column prop="createTime" label="操作时间" width="170"/>
    </el-table>
    <div class="hint" style="margin-top:10px">最多展示最近 500 条，可通过上方条件缩小范围。</div>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Download } from '@element-plus/icons-vue'
import { api, downloadBlob } from '../api'

const list = ref([])
const loading = ref(false)
const filters = ref({ username: '', module: '' })

async function load() {
  loading.value = true
  try {
    const params = {}
    if (filters.value.username) params.username = filters.value.username
    if (filters.value.module) params.module = filters.value.module
    list.value = (await api.logs(params)).data || []
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function exportCsv() {
  const params = {}
  if (filters.value.username) params.username = filters.value.username
  if (filters.value.module) params.module = filters.value.module
  try {
    const res = await api.exportLogs(params)
    downloadBlob(res.data, 'op-logs.csv')
    ElMessage.success('已导出（含导出人与时间水印）')
  } catch (e) { /* 拦截器已提示 */ }
}
</script>
