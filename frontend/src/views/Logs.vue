<template>
  <div class="page-card">
    <div class="page-header">
      <div><h3 class="page-title"><span class="module-tag">M09</span>操作日志</h3>
        <div class="page-desc">记录用户登录、报表访问、数据导出等操作（M09-05）</div></div>
      <el-button @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
    </div>
    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70"/>
      <el-table-column prop="username" label="操作用户" width="130"/>
      <el-table-column prop="module" label="模块" width="160"><template #default="{row}"><el-tag size="small" effect="plain">{{ row.module }}</el-tag></template></el-table-column>
      <el-table-column prop="operation" label="操作内容" min-width="260"/>
      <el-table-column prop="ip" label="IP 地址" width="140"/>
      <el-table-column prop="createTime" label="操作时间" width="180"/>
    </el-table>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { api } from '../api'
const list=ref([])
async function load(){ list.value=(await api.logs()).data }
onMounted(load)
</script>
