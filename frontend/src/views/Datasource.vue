<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h3 class="page-title">数据源接入</h3>
        <div class="page-desc">多源连接、国产库适配、文件与API数据源，连接信息加密存储</div>
      </div>
      <div class="toolbar">
        <el-button type="primary" @click="openDialog()"><el-icon><Plus /></el-icon>新建数据源</el-button>
        <el-button @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" style="margin-bottom:14px">
      支持 JDBC 多驱动（MySQL / Oracle / SQL Server / PostgreSQL / 达梦 / 人大金仓 / GaussDB）+ 文件 + API；新建时可实时测试连通性，密码加密存储。
    </el-alert>

    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="数据源名称" min-width="160" />
      <el-table-column prop="type" label="类型" width="120">
        <template #default="{row}"><el-tag :type="typeTag(row.type)" effect="plain">{{ typeLabel(row.type) }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="driverClass" label="驱动类" min-width="200" show-overflow-tooltip />
      <el-table-column prop="jdbcUrl" label="JDBC URL" min-width="260" show-overflow-tooltip />
      <el-table-column prop="username" label="用户名" width="100" />
      <el-table-column label="密码" width="90"><template #default><span class="mono">******</span></template></el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="170" />
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{row}">
          <el-button link type="primary" @click="test(row)">测试连接</el-button>
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="danger" @click="del(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="form.id ? '编辑数据源' : '新建数据源'" width="640px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="数据源名称" required><el-input v-model="form.name" placeholder="如：生产库-MySQL" /></el-form-item>
        <el-form-item label="数据库类型" required>
          <el-select v-model="form.type" placeholder="请选择" style="width:100%" @change="onTypeChange">
            <el-option v-for="(cls,key) in drivers" :key="key" :label="typeLabel(key)" :value="key" />
            <el-option label="文件数据源(Excel/CSV)" value="file" />
            <el-option label="REST API 数据源" value="api" />
          </el-select>
        </el-form-item>
        <template v-if="form.type !== 'file' && form.type !== 'api'">
          <el-form-item label="驱动类名"><el-input v-model="form.driverClass" class="mono" /></el-form-item>
          <el-form-item label="JDBC URL" required><el-input v-model="form.jdbcUrl" class="mono" placeholder="jdbc:mysql://host:3306/db" /></el-form-item>
          <el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item>
          <el-form-item label="密码"><el-input v-model="form.passwordEnc" type="password" show-password placeholder="保存时加密存储" /></el-form-item>
        </template>
        <el-form-item v-else-if="form.type === 'file'" label="文件路径"><el-input v-model="form.filePath" placeholder="上传后服务端路径" /></el-form-item>
        <el-form-item v-else label="API 配置"><el-input v-model="form.apiConfig" type="textarea" :rows="3" placeholder='{"url":"...","method":"GET","auth":{}}' /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="test(form)" :loading="testing">测试连接</el-button>
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="save" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { api } from '../api'

const list = ref([]); const drivers = ref({})
const loading = ref(false); const dialog = ref(false)
const testing = ref(false); const saving = ref(false)
const form = ref({ type:'mysql' })

const typeLabels = { mysql:'MySQL', oracle:'Oracle', sqlserver:'SQL Server', postgresql:'PostgreSQL', dm:'达梦', kingbase:'人大金仓', gaussdb:'GaussDB', h2:'H2', file:'文件', api:'API' }
const typeLabel = k => typeLabels[k] || k
const typeTag = k => ({ mysql:'primary', dm:'danger', kingbase:'danger', gaussdb:'danger', h2:'success' }[k] || 'info')

async function load() {
  loading.value = true
  try { list.value = (await api.datasources()).data } finally { loading.value = false }
}
async function loadDrivers() { drivers.value = (await api.drivers()).data }

function onTypeChange(t) {
  form.value.driverClass = drivers.value[t] || ''
}

function openDialog(row) {
  form.value = row ? { ...row } : { type:'mysql', driverClass: drivers.value.mysql }
  dialog.value = true
}

async function test(row) {
  testing.value = true
  try { await api.testDatasource(row); ElMessage.success('连接成功') } catch (e) {} finally { testing.value = false }
}

async function save() {
  saving.value = true
  try { await api.saveDatasource(form.value); ElMessage.success('保存成功'); dialog.value=false; load() }
  catch(e){} finally { saving.value=false }
}

async function del(row) {
  await ElMessageBox.confirm(`确认删除数据源「${row.name}」？`, '提示', { type:'warning' })
  await api.delDatasource(row.id); ElMessage.success('已删除'); load()
}

onMounted(() => { load(); loadDrivers() })
</script>
