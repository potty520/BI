<template>
  <div class="page-card">
    <div class="page-header">
      <div><h3 class="page-title"><span class="module-tag">M09</span>定时调度</h3>
        <div class="page-desc">定时刷新、定时推送（邮件/消息）、定时导出（M09-04）</div></div>
      <el-button type="primary" @click="openDialog()"><el-icon><Plus /></el-icon>新建任务</el-button>
    </div>
    <el-alert type="info" :closable="false" style="margin-bottom:14px">
      Cron 表达式示例：<code class="mono">0 0 9 * * ?</code> 表示每天 09:00 执行。生产环境建议引入 Quartz 集群调度；演示环境展示任务配置与状态管理。
    </el-alert>
    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70"/>
      <el-table-column prop="name" label="任务名称" min-width="180"/>
      <el-table-column prop="cron" label="Cron 表达式" width="150"><template #default="{row}"><code class="mono">{{ row.cron }}</code></template></el-table-column>
      <el-table-column label="类型" width="110"><template #default="{row}"><el-tag effect="plain">{{ typeLabel(row.type) }}</el-tag></template></el-table-column>
      <el-table-column prop="receiver" label="接收人" min-width="160"/>
      <el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.enabled===1?'success':'info'" size="small">{{ row.enabled===1?'启用':'停用' }}</el-tag></template></el-table-column>
      <el-table-column prop="lastRunTime" label="上次执行" width="170"/>
      <el-table-column prop="nextRunTime" label="下次执行" width="170"/>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{row}"><el-button link type="primary" @click="toggle(row)">{{ row.enabled===1?'停用':'启用' }}</el-button><el-button link type="primary" @click="openDialog(row)">编辑</el-button><el-button link type="danger" @click="del(row)">删除</el-button></template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="form.id?'编辑任务':'新建任务'" width="580px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="任务名称"><el-input v-model="form.name"/></el-form-item>
        <el-form-item label="关联报表"><el-input-number v-model="form.reportId" :min="1" style="width:100%"/></el-form-item>
        <el-form-item label="Cron 表达式"><el-input v-model="form.cron" class="mono"/></el-form-item>
        <el-form-item label="任务类型"><el-select v-model="form.type" style="width:100%"><el-option label="邮件推送" value="email"/><el-option label="消息推送" value="message"/><el-option label="定时导出" value="export"/><el-option label="定时刷新" value="refresh"/></el-select></el-form-item>
        <el-form-item label="接收人"><el-input v-model="form.receiver" placeholder="邮箱/消息接收人"/></el-form-item>
        <el-form-item label="状态"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api } from '../api'
const list=ref([]), dialog=ref(false), form=ref({type:'email',enabled:1,reportId:1})
const typeLabel=t=>({email:'邮件推送',message:'消息推送',export:'定时导出',refresh:'定时刷新'}[t]||t)
async function load(){ list.value=(await api.schedules()).data }
function openDialog(row){ form.value=row?{...row}:{type:'email',enabled:1,reportId:1,cron:'0 0 9 * * ?'}; dialog.value=true }
async function save(){ await api.saveSchedule(form.value); ElMessage.success('保存成功'); dialog.value=false; load() }
async function toggle(row){ row.enabled=row.enabled===1?0:1; await api.saveSchedule(row); ElMessage.success('状态已更新'); load() }
async function del(row){ await ElMessageBox.confirm('确认删除？','提示',{type:'warning'}); await api.delSchedule(row.id); ElMessage.success('已删除'); load() }
onMounted(load)
</script>
