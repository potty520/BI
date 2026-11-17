<template>
  <div class="page-card">
    <div class="page-header">
      <div><h3 class="page-title">报表管理</h3>
        <div class="page-desc">报表模板列表、在线渲染预览、跳转到设计器</div></div>
      <div class="toolbar">
        <el-button type="primary" @click="$router.push('/designer')"><el-icon><Plus /></el-icon>去设计器新建</el-button>
        <el-button @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>
    </div>
    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column prop="id" label="ID" width="70"/>
      <el-table-column prop="name" label="报表名称" min-width="180"/>
      <el-table-column prop="datasetId" label="数据集ID" width="100"/>
      <el-table-column label="当前版本" width="100"><template #default="{row}"><el-tag effect="plain">v{{ row.currentVersion }}</el-tag></template></el-table-column>
      <el-table-column prop="updateTime" label="更新时间" width="180"/>
      <el-table-column label="操作" width="330" fixed="right">
        <template #default="{row}">
          <el-button link type="success" @click="preview(row)">渲染预览</el-button>
          <el-button link type="warning" @click="exportXlsx(row)">导出 Excel</el-button>
          <el-button link type="warning" @click="exportPdf(row)">导出 PDF</el-button>
          <el-button link type="primary" @click="edit(row)">设计</el-button>
          <el-button link type="danger" @click="del(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="previewDialog" :title="'报表预览 - '+current?.name" width="1000px" top="4vh">
      <div class="toolbar" style="margin-bottom:12px">
        <el-button size="small" @click="doRender" :loading="rendering"><el-icon><View /></el-icon>重新渲染</el-button>
        <span class="hint">由后端扩展引擎计算：父格分组 → 纵向/横向扩展 → 公式聚合</span>
      </div>
      <div v-if="matrix.length" class="render-grid">
        <div v-for="(row,ri) in matrix" :key="ri" class="render-row">
          <div v-for="(cell,ci) in row" :key="ci" class="render-cell" :style="cellStyle(cell)">{{ cell?.value }}</div>
        </div>
      </div>
      <el-empty v-else description="点击渲染预览" :image-size="70"/>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, View, Download } from '@element-plus/icons-vue'
import { api, downloadBlob } from '../api'

const router=useRouter()
const list=ref([]), loading=ref(false)
const previewDialog=ref(false), current=ref(null), matrix=ref([]), rendering=ref(false)
async function load(){ loading.value=true; try{ list.value=(await api.reports()).data }finally{loading.value=false} }
function edit(row){ router.push({path:'/designer',query:{id:row.id}}) }
async function preview(row){ current.value=row; previewDialog.value=true; await doRender() }
async function doRender(){ rendering.value=true; try{ const res=await api.renderReport(current.value.id,{}); matrix.value=res.data.matrix }catch(e){}finally{rendering.value=false} }
async function del(row){ await ElMessageBox.confirm(`确认删除报表「${row.name}」？`,'提示',{type:'warning'}); await api.delReport(row.id); ElMessage.success('已删除'); load() }
async function exportXlsx(row){
  try {
    const res = await api.exportReport(row.id, {})
    downloadBlob(res.data, `report-${row.id}.xlsx`)
    ElMessage.success('导出成功')
  } catch (e) { /* 拦截器已提示 */ }
}
async function exportPdf(row){
  try {
    const res = await api.exportReportPdf(row.id, {})
    downloadBlob(res.data, `report-${row.id}.pdf`)
    ElMessage.success('导出成功')
  } catch (e) { /* 拦截器已提示 */ }
}
function cellStyle(c){ if(!c)return{}; const s=c.style||{}; return {background:s.bg&&s.bg!=='#ffffff'?s.bg:'#fff',fontWeight:s.bold?'bold':'normal',textAlign:s.align||'left',color:s.color||'#1e293b',fontSize:(s.fontSize||12)+'px'} }
onMounted(load)
</script>

<style scoped>
.render-grid{display:flex;flex-direction:column;border:1px solid #cbd5e1;background:#fff;overflow:auto}
.render-row{display:flex}
.render-cell{min-width:96px;flex:1;border:1px solid #e2e8f0;padding:8px 10px;font-size:12px;min-height:34px;display:flex;align-items:center;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
</style>
