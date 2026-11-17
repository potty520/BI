<template>
  <div>
    <div class="page-card">
      <div class="page-header">
        <div><h3 class="page-title"><span class="module-tag">M06</span>数据填报</h3>
          <div class="page-desc">表单录入、必填/类型/正则校验、批量提交入库事务、暂存</div></div>
        <div class="toolbar">
          <el-select v-model="formId" style="width:180px"><el-option :value="1" label="销售订单填报"/></el-select>
          <el-button @click="loadTableData"><el-icon><Refresh /></el-icon>刷新数据</el-button>
        </div>
      </div>
      <el-tabs v-model="tab">
        <el-tab-pane label="填报录入" name="fill">
          <el-alert type="info" :closable="false" style="margin-bottom:14px">
            在下方表格直接录入（可增删行），点击「提交入库」后服务端先做必填/类型/范围校验，再以事务方式批量写入业务表；任一行失败则整体回滚。
          </el-alert>
          <el-table :data="records" border size="small">
            <el-table-column type="index" label="#" width="50"/>
            <el-table-column v-for="f in fields" :key="f.field" :label="f.label+(f.required?' *':'')" :min-width="f.type==='date'?150:130">
              <template #default="{row}">
                <el-select v-if="f.type==='select'" v-model="row[f.field]" style="width:100%" placeholder="请选择">
                  <el-option v-for="o in f.options||[]" :key="o" :label="o" :value="o"/>
                </el-select>
                <el-date-picker v-else-if="f.type==='date'" v-model="row[f.field]" type="date" value-format="YYYY-MM-DD" style="width:100%" placeholder="选择日期"/>
                <el-input-number v-else-if="f.type==='number'" v-model="row[f.field]" :min="f.min" style="width:100%" controls-position="right"/>
                <el-input v-else v-model="row[f.field]" placeholder="请输入"/>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80" fixed="right"><template #default="{row,$index}"><el-button link type="danger" @click="records.splice($index,1)">删除</el-button></template></el-table-column>
          </el-table>
          <div class="toolbar" style="margin-top:14px">
            <el-button @click="addRow"><el-icon><Plus /></el-icon>添加一行</el-button>
            <el-button @click="saveDraft"><el-icon><FolderChecked /></el-icon>暂存本地</el-button>
            <el-button type="primary" @click="submit" :loading="submitting"><el-icon><Upload /></el-icon>提交入库</el-button>
            <span class="hint">已录入 {{ records.length }} 行</span>
          </div>
        </el-tab-pane>
        <el-tab-pane label="已入库数据" name="data">
          <el-table :data="tableData.slice(0,100)" border size="small" max-height="560">
            <el-table-column v-for="c in tableCols" :key="c" :prop="c" :label="c" min-width="110" show-overflow-tooltip/>
          </el-table>
          <div class="hint" style="margin-top:8px">共 {{ tableData.length }} 条记录（LIMIT 200）</div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, FolderChecked, Upload } from '@element-plus/icons-vue'
import { api } from '../api'

const formId=ref(1), tab=ref('fill'), submitting=ref(false)
const fields=ref([]), records=ref([{}]), tableData=ref([]), tableCols=ref([])
function addRow(){ records.value.push({}) }
function saveDraft(){ localStorage.setItem('rbi_form_draft',JSON.stringify(records.value)); ElMessage.success('已暂存到浏览器本地') }
async function loadForm(){
  const res=await api.form(formId.value)
  fields.value=JSON.parse(res.data.fieldsJson||'[]')
  const draft=localStorage.getItem('rbi_form_draft')
  if(draft){ try{ const d=JSON.parse(draft); if(d.length) records.value=d }catch(e){} }
}
async function submit(){
  submitting.value=true
  try{
    const res=await api.submitForm(formId.value,records.value,'insert')
    ElMessage.success(res.data.message)
    localStorage.removeItem('rbi_form_draft')
    records.value=[{}]
    loadTableData()
  }catch(e){}finally{submitting.value=false}
}
async function loadTableData(){
  const res=await api.formTableData(formId.value)
  tableData.value=res.data.rows
  tableCols.value=res.data.rows.length?Object.keys(res.data.rows[0]):[]
}
onMounted(()=>{ loadForm(); })
</script>
