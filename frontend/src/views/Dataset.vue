<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h3 class="page-title">数据集管理</h3>
        <div class="page-desc">SQL 数据集 / 可视化数据集 / 数据集参数 / 查询缓存</div>
      </div>
      <div class="toolbar">
        <el-button type="primary" @click="openDialog()"><el-icon><Plus /></el-icon>新建数据集</el-button>
        <el-button @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>
    </div>

    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="数据集名称" min-width="160" />
      <el-table-column label="类型" width="110"><template #default="{row}"><el-tag effect="plain">{{ row.type }}</el-tag></template></el-table-column>
      <el-table-column label="数据源" width="140"><template #default="{row}">{{ sourceName(row.datasourceId) }}</template></el-table-column>
      <el-table-column prop="sqlText" label="SQL / 配置" min-width="320" show-overflow-tooltip />
      <el-table-column label="缓存" width="90"><template #default="{row}">{{ row.cacheSeconds ? row.cacheSeconds+'s' : '关闭' }}</template></el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{row}">
          <el-button link type="success" @click="preview(row)">预览数据</el-button>
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="danger" @click="del(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 编辑数据集 -->
    <el-dialog v-model="dialog" :title="form.id?'编辑数据集':'新建数据集'" width="820px" top="6vh">
      <el-form :model="form" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="数据集名称" required><el-input v-model="form.name" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="类型">
            <el-select v-model="form.type" style="width:100%">
              <el-option label="SQL 数据集" value="sql" /><el-option label="可视化数据集" value="visual" /><el-option label="API 数据集" value="api" /><el-option label="文件数据集" value="file" />
            </el-select>
          </el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="数据源">
            <el-select v-model="form.datasourceId" style="width:100%" clearable>
              <el-option v-for="s in sources" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
          </el-form-item></el-col>
          <el-col :span="12"><el-form-item label="缓存(秒)"><el-input-number v-model="form.cacheSeconds" :min="0" :max="86400" style="width:100%" /></el-form-item></el-col>
        </el-row>

        <template v-if="form.type==='sql'">
          <el-form-item label="SQL 语句">
            <el-input v-model="form.sqlText" type="textarea" :rows="7" class="mono" />
          </el-form-item>
          <el-alert type="info" :closable="false" style="margin-bottom:12px">
            <div class="hint">
              <b>参数占位符</b>：<code>${region}</code> 会按参数值安全转义为字面量；<code>${region_filter}</code> 等 <b>*_filter</b> 片段由白名单模板生成（如 <code>AND region='华东'</code>），杜绝SQL注入。<br/>
              示例：<code>SELECT * FROM demo_sales WHERE 1=1 ${region_filter} ${date_filter}</code>
            </div>
          </el-alert>
          <el-form-item label="数据集参数"><el-input v-model="form.paramsJson" type="textarea" :rows="3" class="mono" placeholder='[{"name":"region","label":"大区","type":"select","defaultValue":""}]' /></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="配置JSON"><el-input v-model="form.configJson" type="textarea" :rows="8" class="mono" /></el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="runInDialog" :loading="previewLoading">试运行</el-button>
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="save" :loading="saving">保存</el-button>
      </template>
      <div v-if="previewRows.length" style="margin-top:10px">
        <div class="section-title">试运行结果（前 8 行）</div>
        <el-table :data="previewRows.slice(0,8)" border size="small" max-height="260">
          <el-table-column v-for="c in previewCols" :key="c" :prop="c" :label="c" min-width="110" show-overflow-tooltip />
        </el-table>
      </div>
    </el-dialog>

    <!-- 预览 -->
    <el-dialog v-model="previewDialog" title="数据集预览" width="900px" top="5vh">
      <div class="toolbar" style="margin-bottom:12px">
        <span class="hint">共 {{ previewRows.length }} 行</span>
      </div>
      <el-table :data="previewRows.slice(0,100)" border size="small" max-height="560">
        <el-table-column type="index" label="#" width="50" />
        <el-table-column v-for="c in previewCols" :key="c" :prop="c" :label="c" min-width="110" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { api } from '../api'

const list = ref([]); const sources = ref([]); const loading = ref(false)
const dialog = ref(false); const previewDialog = ref(false)
const saving = ref(false); const previewLoading = ref(false)
const previewRows = ref([]); const previewCols = ref([])
const form = ref({ type:'sql', cacheSeconds:0 })

const sourceName = id => sources.value.find(s=>s.id===id)?.name || '内置演示库'

async function load() {
  loading.value = true
  try {
    const [d, s] = await Promise.all([api.datasets(), api.datasources()])
    list.value = d.data; sources.value = s.data
  } finally { loading.value=false }
}
function openDialog(row) {
  form.value = row ? {...row} : { type:'sql', cacheSeconds:0, sqlText:'', paramsJson:'[]' }
  previewRows.value = []
  dialog.value = true
}
async function runInDialog() {
  previewLoading.value = true
  try {
    let rows
    if (form.value.id) rows = (await api.queryDataset(form.value.id, {})).data
    // 新建时也要带上所选数据源，否则会落到平台默认测试库
    else rows = (await api.executeSql({ sql: form.value.sqlText, params:{}, datasourceId: form.value.datasourceId })).data
    previewRows.value = rows
    previewCols.value = rows.length ? Object.keys(rows[0]) : []
    ElMessage.success('执行成功')
  } catch(e){} finally { previewLoading.value=false }
}
async function preview(row) {
  const res = await api.queryDataset(row.id, {})
  previewRows.value = res.data
  previewCols.value = res.data.length ? Object.keys(res.data[0]) : []
  previewDialog.value = true
}
async function save() {
  saving.value = true
  try { await api.saveDataset(form.value); ElMessage.success('保存成功'); dialog.value=false; load() }
  catch(e){} finally { saving.value=false }
}
async function del(row) {
  await ElMessageBox.confirm(`确认删除数据集「${row.name}」？`,'提示',{type:'warning'})
  await api.delDataset(row.id); ElMessage.success('已删除'); load()
}
onMounted(load)
</script>
