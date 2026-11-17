<template>
  <div>
    <div class="page-card">
      <div class="page-header">
        <div><h3 class="page-title"><span class="module-tag">M04</span>参数与查询</h3>
          <div class="page-desc">参数控件、参数联动（省→市）、钻取与报表跳转配置</div></div>
        <el-button type="primary" @click="openDialog()"><el-icon><Plus /></el-icon>新建参数</el-button>
      </div>
      <el-table :data="list" border stripe>
        <el-table-column prop="id" label="ID" width="70"/>
        <el-table-column prop="name" label="参数名称" width="140"/>
        <el-table-column prop="code" label="编码" width="120"><template #default="{row}"><code class="mono">{{ row.code }}</code></template></el-table-column>
        <el-table-column label="控件类型" width="120"><template #default="{row}"><el-tag effect="plain">{{ typeLabel(row.type) }}</el-tag></template></el-table-column>
        <el-table-column prop="linkedParam" label="联动依赖" width="120"><template #default="{row}">{{ row.linkedParam || '—' }}</template></el-table-column>
        <el-table-column prop="defaultValue" label="默认值" width="120"/>
        <el-table-column prop="optionsJson" label="可选项" min-width="200" show-overflow-tooltip/>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{row}"><el-button link type="primary" @click="openDialog(row)">编辑</el-button><el-button link type="danger" @click="del(row)">删除</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <div class="page-card" style="margin-top:16px">
      <div class="section-title">参数控件效果演示（含联动）</div>
      <el-alert type="info" :closable="false" style="margin-bottom:14px">
        选择「大区」后，「省份」下拉会联动加载该大区下的省份值——对应 M04-03 参数联动；这些值会作为参数注入 SQL 数据集动态刷新报表（M04-02）。
      </el-alert>
      <el-row :gutter="16">
        <el-col :span="6">
          <div class="param-demo">
            <div class="param-label">大区（下拉单选）</div>
            <el-select v-model="demo.region" placeholder="全部大区" clearable @change="onRegionChange" style="width:100%">
              <el-option v-for="o in regions" :key="o" :label="o" :value="o" />
            </el-select>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="param-demo">
            <div class="param-label">省份（联动大区）</div>
            <el-select v-model="demo.province" placeholder="全部省份" clearable :disabled="!demo.region" style="width:100%" @change="runQuery">
              <el-option v-for="o in provinces" :key="o" :label="o" :value="o" />
            </el-select>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="param-demo">
            <div class="param-label">开始日期（日期选择）</div>
            <el-date-picker v-model="demo.startDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width:100%" @change="runQuery" />
          </div>
        </el-col>
        <el-col :span="6">
          <div class="param-demo">
            <div class="param-label">产品名称（多选）</div>
            <el-select v-model="demo.products" multiple collapse-tags placeholder="全部产品" style="width:100%">
              <el-option v-for="o in ['笔记本电脑','显示器','服务器','打印机']" :key="o" :label="o" :value="o" />
            </el-select>
          </div>
        </el-col>
      </el-row>
      <div class="toolbar" style="margin-top:14px">
        <el-button type="primary" @click="runQuery" :loading="loading">应用参数查询</el-button>
        <span class="hint">生成 SQL：<code class="mono">{{ builtSql }}</code></span>
      </div>
      <el-table :data="result.slice(0,10)" border size="small" style="margin-top:12px" v-loading="loading" max-height="320">
        <el-table-column prop="order_date" label="日期" width="120"/>
        <el-table-column prop="region" label="大区" width="100"/>
        <el-table-column prop="province" label="省份" width="100"/>
        <el-table-column prop="category" label="类别" width="110"/>
        <el-table-column prop="product" label="产品" width="130"/>
        <el-table-column prop="amount" label="金额" width="120"/>
      </el-table>
      <div class="hint" style="margin-top:8px">共匹配 {{ result.length }} 行（数据集预览 LIMIT 5000）</div>
    </div>

    <el-dialog v-model="dialog" :title="form.id?'编辑参数':'新建参数'" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="参数名称"><el-input v-model="form.name"/></el-form-item>
        <el-form-item label="参数编码"><el-input v-model="form.code" placeholder="如 region"/></el-form-item>
        <el-form-item label="控件类型">
          <el-select v-model="form.type" style="width:100%">
            <el-option label="文本框" value="text"/><el-option label="下拉单选" value="select"/>
            <el-option label="下拉多选" value="multiselect"/><el-option label="日期选择器" value="date"/><el-option label="树形选择器" value="tree"/>
          </el-select>
        </el-form-item>
        <el-form-item label="默认值"><el-input v-model="form.defaultValue"/></el-form-item>
        <el-form-item label="联动依赖"><el-input v-model="form.linkedParam" placeholder="父参数编码，如 region"/></el-form-item>
        <el-form-item label="选项(JSON)"><el-input v-model="form.optionsJson" type="textarea" :rows="2" placeholder='["华北","华东"]'/></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api } from '../api'

const list=ref([]), dialog=ref(false), loading=ref(false)
const form=ref({type:'select'})
const regions=['华北','华东','华南','西北','西南']
const demo=ref({region:'',province:'',startDate:'',products:[]})
const provinces=ref([]), result=ref([])
const builtSql=computed(()=>{
  let sql='SELECT * FROM demo_sales WHERE 1=1 '
  if(demo.value.region) sql+=`AND region = '${demo.value.region}' `
  if(demo.value.province) sql+=`AND province = '${demo.value.province}' `
  if(demo.value.startDate) sql+=`AND order_date >= '${demo.value.startDate}' `
  if(demo.value.products.length) sql+=`AND product IN (${demo.value.products.map(p=>`'${p}'`).join(',')}) `
  return sql
})
const typeLabel=t=>({text:'文本框',select:'下拉单选',multiselect:'下拉多选',date:'日期选择器',tree:'树形选择器'}[t]||t)

async function load(){ list.value=(await api.params()).data }
function openDialog(row){ form.value=row?{...row}:{type:'select',optionsJson:'[]'}; dialog.value=true }
async function save(){ await api.saveParam(form.value); ElMessage.success('保存成功'); dialog.value=false; load() }
async function del(row){ await ElMessageBox.confirm('确认删除？','提示',{type:'warning'}); await api.delParam(row.id); ElMessage.success('已删除'); load() }
async function onRegionChange(){
  demo.value.province=''
  if(!demo.value.region){ provinces.value=[]; return }
  provinces.value=(await api.paramOptions('province',demo.value.region)).data
}
async function runQuery(){
  loading.value=true
  try{
    const ds=(await api.datasets()).data.find(d=>d.id===1)
    const params={}
    if(demo.value.region) params.region=demo.value.region
    if(demo.value.province) params.province=demo.value.province
    if(demo.value.startDate) params.startDate=demo.value.startDate
    result.value=(await api.queryDataset(1,params)).data
  }catch(e){}finally{loading.value=false}
}
onMounted(()=>{ load(); runQuery() })
</script>

<style scoped>
.param-demo{background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;padding:12px}
.param-label{font-size:12px;color:#475569;font-weight:600;margin-bottom:8px}
</style>
