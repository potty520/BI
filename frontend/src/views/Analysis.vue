<template>
  <div>
    <div class="page-card">
      <div class="page-header">
        <div><h3 class="page-title"><span class="module-tag">M07</span>自助分析</h3>
          <div class="page-desc">拖拽维度/度量生成交叉分析，自动聚合、过滤排序、快速计算（对标 FineBI）</div></div>
        <div class="toolbar">
          <el-select v-model="datasetId" style="width:180px" @change="loadFields"><el-option v-for="d in datasets" :key="d.id" :label="d.name" :value="d.id"/></el-select>
          <el-button type="primary" @click="run" :loading="loading"><el-icon><VideoPlay /></el-icon>运行分析</el-button>
          <el-button @click="showSql=true">查看翻译SQL</el-button>
          <el-button @click="saveAnalysis">保存分析</el-button>
        </div>
      </div>

      <el-row :gutter="14">
        <!-- 字段列表 -->
        <el-col :span="4">
          <div class="bi-panel">
            <div class="bi-title">字段列表</div>
            <div class="hint" style="padding:0 10px 8px">拖拽字段到右侧区域</div>
            <div class="field-cat">维度</div>
            <div v-for="f in dimFields" :key="'d'+f.name" class="bi-field dim" draggable="true" @dragstart="drag($event,f)"><el-icon><FolderOpened /></el-icon>{{ f.name }}</div>
            <div class="field-cat">度量</div>
            <div v-for="f in numFields" :key="'n'+f.name" class="bi-field num" draggable="true" @dragstart="drag($event,f)"><el-icon><TrendCharts /></el-icon>{{ f.name }}</div>
          </div>
        </el-col>

        <!-- 分析区 -->
        <el-col :span="20">
          <div class="drop-zones">
            <div class="zone" @dragover.prevent @drop.prevent="drop($event,'rows')">
              <div class="zone-title">行维度</div>
              <el-tag v-for="(f,i) in cfg.rows" :key="f" closable @close="cfg.rows.splice(i,1)" type="primary" effect="plain">{{ f }}</el-tag>
              <span v-if="!cfg.rows.length" class="zone-ph">拖入维度字段</span>
            </div>
            <div class="zone" @dragover.prevent @drop.prevent="drop($event,'cols')">
              <div class="zone-title">列维度</div>
              <el-tag v-for="(f,i) in cfg.cols" :key="f" closable @close="cfg.cols.splice(i,1)" type="success" effect="plain">{{ f }}</el-tag>
              <span v-if="!cfg.cols.length" class="zone-ph">拖入维度字段</span>
            </div>
            <div class="zone" @dragover.prevent @drop.prevent="drop($event,'values')">
              <div class="zone-title">度量值</div>
              <el-tag v-for="(m,i) in cfg.values" :key="i" closable @close="cfg.values.splice(i,1)" type="warning" effect="plain">
                {{ m.agg }}({{ m.field }})
              </el-tag>
              <span v-if="!cfg.values.length" class="zone-ph">拖入数值字段</span>
            </div>
          </div>

          <div class="bi-controls">
            <span>快速计算：</span>
            <el-radio-group v-model="cfg.quick" size="small" @change="run">
              <el-radio-button value="">无</el-radio-button>
              <el-radio-button value="ratio">占比</el-radio-button>
              <el-radio-button value="cumulative">累计</el-radio-button>
              <el-radio-button value="rank">排名</el-radio-button>
            </el-radio-group>
            <span style="margin-left:16px">排序：</span>
            <el-select v-model="sortField" size="small" style="width:150px" placeholder="排序字段" @change="applySort">
              <el-option v-for="c in columns" :key="c.name" :label="c.label" :value="c.name"/>
            </el-select>
            <el-select v-model="sortDir" size="small" style="width:90px" @change="applySort"><el-option label="升序" value="asc"/><el-option label="降序" value="desc"/></el-select>
          </div>

          <el-table :data="rows" border size="small" max-height="480" v-loading="loading" style="margin-top:12px">
            <el-table-column v-for="c in columns" :key="c.name" :prop="c.name" :label="c.label" min-width="120" sortable/>
          </el-table>
          <div class="hint" style="margin-top:8px">结果 {{ resultTotal }} 行 · 配置已自动翻译并执行聚合</div>
        </el-col>
      </el-row>
    </div>

    <el-dialog v-model="showSql" title="分析配置 → SQL 翻译引擎（M07 自研要点）" width="720px">
      <div class="code-block">{{ sqlText }}</div>
      <div class="hint" style="margin-top:10px">演示环境下聚合在本地引擎执行以保证跨库一致性；生成的 SQL 展示了维度/度量/过滤/分组的翻译规则。</div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { VideoPlay, FolderOpened, TrendCharts } from '@element-plus/icons-vue'
import { api } from '../api'

const datasets=ref([]), datasetId=ref(1), fields=ref([]), loading=ref(false)
const cfg=ref({rows:['region'],cols:[],values:[{field:'amount',agg:'SUM'}],filters:[],sorts:[],quick:''})
const columns=ref([]), rows=ref([]), resultTotal=ref(0), showSql=ref(false)
const sortField=ref(''), sortDir=ref('desc')
const dragField=ref(null)
const dimFields=computed(()=>fields.value.filter(f=>f.type!=='number'))
const numFields=computed(()=>fields.value.filter(f=>f.type==='number'))

function drag(e,f){ dragField.value=f }
function drop(e,zone){
  if(!dragField.value) return
  const f=dragField.value
  if(zone==='values'){
    if(f.type!=='number'){ ElMessage.warning('度量值需拖入数值字段'); return }
    cfg.value.values.push({field:f.name,agg:'SUM'})
  }else{
    if(!cfg.value[zone].includes(f.name)) cfg.value[zone].push(f.name)
  }
  dragField.value=null
}
function applySort(){
  cfg.value.sorts=sortField.value?[{field:sortField.value,dir:sortDir.value}]:[]
  run()
}
async function loadFields(){
  fields.value=(await api.datasetFields(datasetId.value)).data
}
async function run(){
  loading.value=true
  try{
    const res=await api.analysisRun({datasetId:datasetId.value,config:cfg.value})
    columns.value=res.data.columns; rows.value=res.data.rows; resultTotal.value=res.data.total
  }catch(e){}finally{loading.value=false}
}
async function saveAnalysis(){
  await api.saveAnalysis({name:'我的分析-'+new Date().toLocaleTimeString(),datasetId:datasetId.value,configJson:JSON.stringify(cfg.value)})
  ElMessage.success('分析视图已保存')
}
const sqlText=computed(()=>{
  let sql='SELECT '
  const dims=[...cfg.value.rows,...cfg.value.cols]
  sql+=dims.length?dims.join(', '):'*'
  if(dims.length&&cfg.value.values.length) sql+=', '
  sql+=cfg.value.values.map(m=>`${m.agg}(${m.field}) AS ${m.agg.toLowerCase()}_${m.field}`).join(', ')
  sql+='\nFROM dataset\nWHERE 1=1 '
  if(dims.length) sql+='\nGROUP BY '+dims.join(', ')
  if(cfg.value.sorts.length) sql+='\nORDER BY '+cfg.value.sorts[0].field+' '+cfg.value.sorts[0].dir.toUpperCase()
  return sql
})
onMounted(async()=>{ const d=await api.datasets(); datasets.value=d.data; await loadFields(); await run() })
</script>

<style scoped>
.bi-panel{background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;padding:10px;min-height:520px}
.bi-title{font-weight:700;font-size:13px;margin-bottom:6px}
.field-cat{font-size:11px;color:#94a3b8;font-weight:600;margin:10px 0 6px}
.bi-field{display:flex;align-items:center;gap:6px;background:#fff;border:1px solid #e2e8f0;border-radius:5px;padding:6px 8px;margin-bottom:5px;cursor:grab;font-size:12px}
.bi-field.dim{border-left:3px solid #2563eb}
.bi-field.num{border-left:3px solid #16a34a}
.drop-zones{display:flex;gap:10px;flex-wrap:wrap}
.zone{flex:1;min-width:180px;min-height:76px;border:2px dashed #cbd5e1;border-radius:8px;padding:10px;background:#f8fafc}
.zone-title{font-size:11px;color:#64748b;font-weight:700;margin-bottom:8px}
.zone-ph{font-size:11px;color:#cbd5e1}
.zone .el-tag{margin-right:6px;margin-bottom:4px}
.bi-controls{display:flex;align-items:center;gap:8px;margin-top:12px;font-size:12px;color:#475569;flex-wrap:wrap}
</style>
