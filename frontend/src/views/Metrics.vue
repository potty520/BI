<template>
  <div class="page-card">
    <div class="page-header">
      <div><h3 class="page-title"><span class="module-tag">M08</span>指标管理</h3>
        <div class="page-desc">指标中心、统一口径、指标血缘（原子指标 / 派生指标）</div></div>
      <el-button type="primary" @click="openDialog()"><el-icon><Plus /></el-icon>新建指标</el-button>
    </div>
    <el-alert type="info" :closable="false" style="margin-bottom:14px">
      指标中心统一定义业务口径，报表与自助分析可直接引用指标而非手写聚合表达式；点击「血缘」查看指标依赖的底层表与计算逻辑（M08-03）。
    </el-alert>
    <el-table :data="list" border stripe>
      <el-table-column prop="name" label="指标名称" width="140"/>
      <el-table-column prop="code" label="编码" width="150"><template #default="{row}"><code class="mono">{{ row.code }}</code></template></el-table-column>
      <el-table-column label="类型" width="110"><template #default="{row}"><el-tag :type="row.type==='derived'?'warning':'success'" effect="plain">{{ row.type==='derived'?'派生指标':'原子指标' }}</el-tag></template></el-table-column>
      <el-table-column prop="expression" label="计算公式" min-width="200"><template #default="{row}"><code class="mono">{{ row.expression }}</code></template></el-table-column>
      <el-table-column prop="unit" label="单位" width="80"/>
      <el-table-column prop="caliber" label="口径说明" min-width="200" show-overflow-tooltip/>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{row}"><el-button link type="primary" @click="showLineage(row)">血缘</el-button><el-button link type="primary" @click="openDialog(row)">编辑</el-button><el-button link type="danger" @click="del(row)">删除</el-button></template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="form.id?'编辑指标':'新建指标'" width="600px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="指标名称"><el-input v-model="form.name"/></el-form-item>
        <el-form-item label="指标编码"><el-input v-model="form.code" placeholder="如 TOTAL_SALES"/></el-form-item>
        <el-form-item label="指标类型"><el-select v-model="form.type" style="width:100%"><el-option label="原子指标" value="atomic"/><el-option label="派生指标" value="derived"/></el-select></el-form-item>
        <el-form-item label="计算公式"><el-input v-model="form.expression" class="mono" placeholder="SUM(amount) 或 GROSS_PROFIT / TOTAL_SALES"/></el-form-item>
        <el-form-item label="单位"><el-input v-model="form.unit"/></el-form-item>
        <el-form-item label="口径说明"><el-input v-model="form.caliber" type="textarea" :rows="2"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="lineageDialog" title="指标血缘（M08-03）" width="560px">
      <template v-if="lineage">
        <div class="lineage-root">{{ lineage.name }} <el-tag size="small">{{ lineage.code }}</el-tag></div>
        <div class="lineage-arrow">↓ 依赖 ↓</div>
        <template v-if="lineage.lineageObj?.metrics">
          <el-tag v-for="m in lineage.lineageObj.metrics" :key="m" type="warning" effect="plain" style="margin:4px">{{ m }}</el-tag>
        </template>
        <template v-if="lineage.lineageObj?.tables">
          <div class="lineage-arrow">↓ 底层表与字段 ↓</div>
          <div v-for="t in lineage.lineageObj.tables" :key="t"><el-tag type="success" effect="plain">{{ t }}</el-tag></div>
          <div v-if="lineage.lineageObj.fields" style="margin-top:8px">
            <span class="hint">字段：</span><code class="mono">{{ lineage.lineageObj.fields.join(', ') }}</code>
          </div>
        </template>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api } from '../api'

const list=ref([]), dialog=ref(false), form=ref({type:'atomic'}), lineageDialog=ref(false), lineage=ref(null)
async function load(){ list.value=(await api.metrics()).data }
function openDialog(row){ form.value=row?{...row}:{type:'atomic'}; dialog.value=true }
async function save(){ await api.saveMetric(form.value); ElMessage.success('保存成功'); dialog.value=false; load() }
async function del(row){ await ElMessageBox.confirm('确认删除？','提示',{type:'warning'}); await api.delMetric(row.id); ElMessage.success('已删除'); load() }
function showLineage(row){
  try{ row.lineageObj=JSON.parse(row.lineageJson||'{}') }catch(e){ row.lineageObj={} }
  lineage.value=row; lineageDialog.value=true
}
onMounted(load)
</script>

<style scoped>
.lineage-root{font-size:16px;font-weight:700;text-align:center;padding:16px;background:#eff6ff;border-radius:8px}
.lineage-arrow{text-align:center;color:#94a3b8;font-size:12px;margin:10px 0}
</style>
