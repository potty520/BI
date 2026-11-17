<template>
  <div class="designer">
    <!-- 顶部工具栏 -->
    <div class="designer-toolbar">
      <div class="tool-left">
        <el-select v-model="currentReportId" placeholder="新建报表或选择已有报表" style="width:230px" @change="loadReport">
          <el-option label="＋ 新建报表" :value="null" />
          <el-option v-for="r in reports" :key="r.id" :label="r.name" :value="r.id" />
        </el-select>
        <el-input v-model="reportName" placeholder="报表名称" style="width:170px" />
        <el-select v-model="datasetId" placeholder="绑定数据集" style="width:170px" @change="loadFields">
          <el-option v-for="d in datasets" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-divider direction="vertical" />
        <el-button-group>
          <el-button :disabled="!canUndo" @click="undo" title="撤销"><el-icon><RefreshLeft /></el-icon></el-button>
          <el-button :disabled="!canRedo" @click="redo" title="重做"><el-icon><RefreshRight /></el-icon></el-button>
        </el-button-group>
        <el-button-group>
          <el-button @click="mergeCells" title="合并单元格"><el-icon><Link /></el-icon>合并</el-button>
          <el-button @click="unmergeCells" title="取消合并"><el-icon><Refresh /></el-icon>拆分</el-button>
        </el-button-group>
      </div>
      <div class="tool-right">
        <el-radio-group v-model="mode">
          <el-radio-button value="design">设计</el-radio-button>
          <el-radio-button value="page">分页预览</el-radio-button>
          <el-radio-button value="fill">填报预览</el-radio-button>
          <el-radio-button value="analysis">分析预览</el-radio-button>
        </el-radio-group>
        <el-button type="primary" @click="saveReport" :loading="saving"><el-icon><DocumentChecked /></el-icon>保存</el-button>
        <el-button @click="showVersions" :disabled="!currentReportId"><el-icon><Clock /></el-icon>版本</el-button>
      </div>
    </div>

    <div class="designer-body">
      <!-- 左侧字段面板 -->
      <aside class="field-panel" v-show="mode==='design'">
        <div class="panel-title">数据集字段</div>
        <div class="hint" style="padding:0 12px 10px">拖拽字段到单元格即可绑定</div>
        <el-scrollbar height="42vh">
          <div class="field-group">
            <div class="field-group-title">维度字段</div>
            <div v-for="f in dimFields" :key="'d'+f.name" class="field-item dim" draggable="true" @dragstart="onDragField($event, f)">
              <el-icon><FolderOpened /></el-icon><span>{{ f.name }}</span>
            </div>
          </div>
          <div class="field-group">
            <div class="field-group-title">数值字段</div>
            <div v-for="f in numFields" :key="'n'+f.name" class="field-item num" draggable="true" @dragstart="onDragField($event, f)">
              <el-icon><TrendCharts /></el-icon><span>{{ f.name }}</span>
            </div>
          </div>
        </el-scrollbar>
        <div class="panel-title" style="border-top:1px solid #e2e8f0">函数速查（点击写入公式）</div>
        <div class="func-list">
          <div v-for="fn in funcs" :key="fn.n" class="func-item" @click="insertFormula(fn.t)">
            <b>{{ fn.n }}</b><span>{{ fn.d }}</span>
          </div>
        </div>
      </aside>

      <!-- 中间画布 -->
      <main class="canvas-area">
        <div class="formula-bar" v-show="mode==='design'">
          <span class="cell-ref">{{ selectedRef || '—' }}</span>
          <span class="fx">fx</span>
          <el-input v-model="formulaText" size="small" placeholder="输入文本或公式，如 SUM(amount)、IF(A1>100,&quot;高&quot;,&quot;低&quot;)" @change="applyFormula" />
        </div>

        <div v-show="mode==='design'" class="grid-scroll">
          <table class="grid-table">
            <thead>
              <tr>
                <th class="corner"></th>
                <th v-for="c in cols" :key="'h'+c" class="col-header" :style="{width: cellW+'px'}">{{ colLetter(c) }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in rows" :key="'r'+r">
                <th class="row-header">{{ r }}</th>
                <td v-for="c in cols" :key="'c'+c"
                    :class="['grid-cell',{selected:isSelected(r,c),covered:isCovered(r,c)}]"
                    :style="cellStyle(r,c)"
                    :colspan="spanOf(r,c,'col')" :rowspan="spanOf(r,c,'row')"
                    @mousedown="selectCell(r,c)" @dblclick="onDblClick(r,c)"
                    @dragover.prevent @drop.prevent="onDrop($event,r,c)">
                  <span v-if="!isCovered(r,c)" :class="cellClass(r,c)">{{ cellDisplay(r,c) }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-show="mode!=='design'" class="preview-wrap">
          <div class="preview-toolbar">
            <span class="hint">当前模式：<b>{{ modeLabel }}</b> · 由后端扩展引擎渲染</span>
            <el-button size="small" @click="doPreview" :loading="previewing"><el-icon><View /></el-icon>刷新数据</el-button>
            <el-button size="small" v-if="mode==='analysis'" @click="showSql=true">查看配置转SQL</el-button>
          </div>
          <div v-if="previewMatrix.length" class="render-grid">
            <div v-for="(row,ri) in previewMatrix" :key="ri" class="render-row">
              <div v-for="(cell,ci) in row" :key="ci" class="render-cell" :class="{empty:!cell}" :style="renderCellStyle(cell)">
                <template v-if="cell">{{ cell.value }}</template>
              </div>
            </div>
          </div>
          <el-empty v-else description="点击「刷新数据」用扩展引擎渲染报表" :image-size="70" />
        </div>
      </main>

      <!-- 右侧属性面板 -->
      <aside class="prop-panel" v-show="mode==='design'">
        <div class="panel-title">单元格属性</div>
        <el-scrollbar height="calc(100vh - 160px)">
          <div class="prop-body" v-if="selectedCell">
            <el-form label-width="82px" size="small">
              <el-form-item label="单元格"><b class="mono">{{ selectedRef }}</b></el-form-item>
              <el-form-item label="类型">
                <el-select v-model="selectedCell.type" @change="markChange">
                  <el-option label="文本" value="text" /><el-option label="数据集字段" value="dataset" /><el-option label="公式" value="formula" />
                </el-select>
              </el-form-item>
              <el-form-item label="绑定字段" v-if="selectedCell.type==='dataset'">
                <el-select v-model="selectedCell.field" filterable @change="markChange" placeholder="选择字段" style="width:100%">
                  <el-option v-for="f in allFields" :key="f.name" :label="f.name" :value="f.name" />
                </el-select>
              </el-form-item>
              <el-form-item label="扩展方向" v-if="selectedCell.type==='dataset'">
                <el-radio-group v-model="selectedCell.expand" @change="markChange">
                  <el-radio-button value="none">不扩展</el-radio-button>
                  <el-radio-button value="v">纵向</el-radio-button>
                  <el-radio-button value="h">横向</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="父格" v-if="selectedCell.type!=='text'">
                <el-select v-model="selectedCell.parent" clearable filterable @change="markChange" placeholder="无（顶层）" style="width:100%">
                  <el-option v-for="p in parentCandidates" :key="p" :label="p" :value="p" />
                </el-select>
              </el-form-item>
              <el-form-item label="公式内容" v-if="selectedCell.type==='formula'">
                <el-input v-model="selectedCell.value" @change="markChange" placeholder="SUM(amount)" />
              </el-form-item>
              <el-form-item label="显示格式"><el-input v-model="numberFormat" @change="setFormat" placeholder="#,##0.00" /></el-form-item>
              <el-divider content-position="left">样式</el-divider>
              <el-form-item label="加 bold"><el-switch v-model="style.bold" @change="markChange" /></el-form-item>
              <el-form-item label="字号"><el-input-number v-model="style.fontSize" :min="8" :max="36" @change="markChange" /></el-form-item>
              <el-form-item label="对齐">
                <el-select v-model="style.align" @change="markChange">
                  <el-option label="左" value="left" /><el-option label="居中" value="center" /><el-option label="右" value="right" />
                </el-select>
              </el-form-item>
              <el-form-item label="背景色"><el-color-picker v-model="style.bg" @change="markChange" /></el-form-item>
              <el-form-item label="文字色"><el-color-picker v-model="style.color" @change="markChange" /></el-form-item>
            </el-form>
            <div class="expert-tip">
              <b>扩展机制（核心）</b>
              <p>父格决定子格的数据分组：父格「大区」纵向扩展出多行后，父格指向它的「省份」格会在每个大区分组内再次展开，实现逐层下钻；公式格在父格上下文中聚合求值。</p>
            </div>
          </div>
          <el-empty v-else description="点击网格中的单元格进行配置" :image-size="60" />
        </el-scrollbar>
      </aside>
    </div>

    <el-dialog v-model="versionDialog" title="模板版本历史" width="640px">
      <el-table :data="versions" border size="small">
        <el-table-column prop="version" label="版本" width="72" />
        <el-table-column prop="remark" label="说明" min-width="160" />
        <el-table-column prop="createBy" label="操作人" width="96" />
        <el-table-column prop="createTime" label="时间" width="165" />
        <el-table-column label="操作" width="80"><template #default="{row}"><el-button link type="primary" @click="rollback(row)">回滚</el-button></template></el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="showSql" title="分析配置 → SQL 翻译" width="720px">
      <div class="code-block">SELECT region, province, category, SUM(amount) AS sum_amount
FROM dataset
WHERE 1=1 AND region = '华东'
GROUP BY region, province, category
ORDER BY sum_amount DESC</div>
      <div class="hint" style="margin-top:10px">到「自助分析」页可实际执行配置到 SQL 的翻译与聚合运算。</div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { RefreshLeft, RefreshRight, Link, Refresh, DocumentChecked, Clock, EditPen, FolderOpened, TrendCharts, View } from '@element-plus/icons-vue'
import { api } from '../api'

const rows = ref(18), cols = ref(7), cellW = 100
const colLetter = (c) => { let s=''; let n=c; while(n>0){ const r=(n-1)%26; s=String.fromCharCode(65+r)+s; n=Math.floor((n-1)/26); } return s }
const key = (r,c) => r+'-'+c
const cells = ref({})
function ensureCell(r,c) {
  const k = key(r,c)
  if (!cells.value[k]) cells.value[k] = { ref: colLetter(c)+r, type:'text', value:'', field:'', expand:'none', parent:'', style:{} }
  return cells.value[k]
}
const getCell = (r,c) => cells.value[key(r,c)]

const selected = ref({r:1,c:1})
const selectedRef = computed(()=> colLetter(selected.value.c)+selected.value.r)
const selectedCell = computed(()=> getCell(selected.value.r, selected.value.c))
const formulaText = ref('')
watch(selectedCell, c => { formulaText.value = c ? (c.value||'') : '' }, {immediate:true})

const style = ref({bold:false,fontSize:12,align:'left',bg:'#ffffff',color:'#1e293b'})
const numberFormat = ref('')
watch(selectedRef, () => {
  const s = selectedCell.value?.style || {}
  style.value = {bold:!!s.bold,fontSize:s.fontSize||12,align:s.align||'left',bg:s.bg||'#ffffff',color:s.color||'#1e293b'}
  numberFormat.value = s.format || ''
}, {immediate:true})

const history = ref([]), hIdx = ref(-1)
const canUndo = computed(()=> hIdx.value>0)
const canRedo = computed(()=> hIdx.value<history.value.length-1)
function snapshot() {
  history.value = history.value.slice(0,hIdx.value+1)
  history.value.push(JSON.parse(JSON.stringify(cells.value)))
  if (history.value.length>60) history.value.shift()
  hIdx.value = history.value.length-1
}
function undo(){ if(canUndo.value){ hIdx.value--; cells.value=JSON.parse(JSON.stringify(history.value[hIdx.value])) } }
function redo(){ if(canRedo.value){ hIdx.value++; cells.value=JSON.parse(JSON.stringify(history.value[hIdx.value])) } }
function markChange(){ snapshot() }

const dragField = ref(null)
const reports = ref([]), datasets = ref([]), sources = ref([])
const allFields = ref([])
const currentReportId = ref(null), reportName = ref('新建报表'), datasetId = ref(1)
const dimFields = computed(()=> allFields.value.filter(f=>f.type!=='number'))
const numFields = computed(()=> allFields.value.filter(f=>f.type==='number'))
const parentCandidates = computed(()=> Object.values(cells.value).filter(c=>c.type==='dataset').map(c=>c.ref))
const funcs = [
  {n:'SUM',t:'SUM(amount)',d:'求和'},{n:'AVG',t:'AVG(amount)',d:'平均'},
  {n:'COUNT',t:'COUNT(amount)',d:'计数'},{n:'IF',t:'IF(A1>100,"高","低")',d:'条件判断'},
  {n:'CONCAT',t:'CONCAT(A1,"-",B1)',d:'拼接'},{n:'ROUND',t:'ROUND(AVG(amount),2)',d:'四舍五入'}
]

const mode = ref('design')
const modeLabel = computed(()=>({design:'设计',page:'分页预览',fill:'填报预览',analysis:'分析预览'}[mode.value]))
const previewMatrix = ref([]), previewing = ref(false), previewCols = ref(1)
const showSql = ref(false)
const saving = ref(false), versionDialog = ref(false), versions = ref([])

function isSelected(r,c){ return selected.value.r===r && selected.value.c===c }
function spanOf(r,c,dir){
  const cell=getCell(r,c)
  if(!cell) return 1
  return dir==='col' ? (cell.colspan||1) : (cell.rowspan||1)
}
function isCovered(r,c){
  for (const k in cells.value){
    const cell=cells.value[k]
    if((cell.colspan>1||cell.rowspan>1) && k!==key(r,c)){
      const [cr,cc]=k.split('-').map(Number)
      if(r>=cr && r<cr+(cell.rowspan||1) && c>=cc && c<cc+(cell.colspan||1)) return true
    }
  }
  return false
}
function cellDisplay(r,c){
  const cell=getCell(r,c)
  if(!cell) return ''
  if(cell.type==='dataset') return cell.field ? ('${'+cell.field+'}') : '拖入字段'
  return cell.value||''
}
function cellClass(r,c){
  const cell=getCell(r,c); if(!cell) return ''
  return cell.type==='dataset'?'cell-bound':cell.type==='formula'?'cell-formula':''
}
function cellStyle(r,c){
  const s=getCell(r,c)?.style||{}
  return {fontSize:(s.fontSize||12)+'px',fontWeight:s.bold?'bold':'normal',textAlign:s.align||'left',background:s.bg||'#fff',color:s.color||'#1e293b'}
}
function selectCell(r,c){
  if(isCovered(r,c)) return
  ensureCell(r,c)
  selected.value={r,c}
  snapshot()
}
function onDblClick(r,c){
  const cell=ensureCell(r,c)
  const v=window.prompt('输入文本或公式', cell.value||'')
  if(v!==null){ cell.value=v; if(v.startsWith('=')||/^(SUM|AVG|COUNT|IF|CONCAT|ROUND)\(/i.test(v)) cell.type='formula'; snapshot() }
}
function onDragField(e,f){ dragField.value=f }
function onDrop(e,r,c){
  if(!dragField.value) return
  const cell=ensureCell(r,c)
  cell.type='dataset'; cell.field=dragField.value.name
  cell.expand=dragField.value.type==='number'?'none':'v'
  cell.value=''
  dragField.value=null
  snapshot()
  ElMessage.success(`已绑定字段 ${cell.field}`)
}
function applyFormula(){ if(selectedCell.value){ selectedCell.value.value=formulaText.value; if(formulaText.value) selectedCell.value.type='formula'; snapshot() } }
function insertFormula(t){
  if(!selectedCell.value) return
  selectedCell.value.type='formula'; selectedCell.value.value=t
  formulaText.value=t; snapshot()
  ElMessage.success('已写入公式：'+t)
}
function setFormat(){
  if(selectedCell.value){ selectedCell.value.style=selectedCell.value.style||{}; selectedCell.value.style.format=numberFormat.value; snapshot() }
}
function mergeCells(){
  const cell=ensureCell(selected.value.r,selected.value.c)
  cell.colspan=2; cell.rowspan=1; snapshot(); ElMessage.success('已横向合并 2 格')
}
function unmergeCells(){
  const cell=ensureCell(selected.value.r,selected.value.c)
  cell.colspan=1; cell.rowspan=1; snapshot()
}
function renderCellStyle(cell){
  if(!cell) return {}
  const s=cell.style||{}
  return {background:s.bg&&s.bg!=='#ffffff'?s.bg:'#fff',fontWeight:s.bold?'bold':'normal',textAlign:s.align||'left',color:s.color||'#1e293b',fontSize:(s.fontSize||12)+'px'}
}

async function loadBase(){
  const [r,d]=await Promise.all([api.reports(),api.datasets()])
  reports.value=r.data; datasets.value=d.data
}
async function loadFields(){
  if(!datasetId.value){ allFields.value=[]; return }
  const res=await api.datasetFields(datasetId.value)
  allFields.value=res.data
}
function buildDefaultLayout(){
  Object.assign(ensureCell(1,1), {ref:'A1',type:'text',value:'区域销售汇总表',style:{bold:true,fontSize:16,align:'center',colspan:4}})
  Object.assign(ensureCell(2,1), {ref:'A2',type:'text',value:'大区',style:{bold:true,bg:'#e8eaf6'}})
  Object.assign(ensureCell(2,2), {ref:'B2',type:'text',value:'省份',style:{bold:true,bg:'#e8eaf6'}})
  Object.assign(ensureCell(2,3), {ref:'C2',type:'text',value:'类别',style:{bold:true,bg:'#e8eaf6'}})
  Object.assign(ensureCell(2,4), {ref:'D2',type:'text',value:'销售额',style:{bold:true,bg:'#e8eaf6'}})
  Object.assign(ensureCell(3,1), {ref:'A3',type:'dataset',field:'region',expand:'v',parent:''})
  Object.assign(ensureCell(3,2), {ref:'B3',type:'dataset',field:'province',expand:'v',parent:'A3'})
  Object.assign(ensureCell(3,3), {ref:'C3',type:'dataset',field:'category',expand:'h',parent:''})
  Object.assign(ensureCell(3,4), {ref:'D3',type:'formula',value:'SUM(amount)',expand:'none',parent:'',style:{format:'#,##0.00'}})
}
async function loadReport(id){
  if(!id){
    currentReportId.value=null; reportName.value='新建报表'; cells.value={}
    buildDefaultLayout(); snapshot(); return
  }
  const res=await api.report(id)
  const r=res.data
  reportName.value=r.name; datasetId.value=r.datasetId
  await loadFields()
  const layout=JSON.parse(r.layoutJson||'{"cells":[]}')
  // 按 ref 重建单元格映射
  cells.value={}
  for(const c of (layout.cells||[])){
    const m=c.ref.match(/^([A-Z]+)(\d+)$/)
    if(m){ let col=0; for(const ch of m[1]) col=col*26+(ch.charCodeAt(0)-64); cells.value[key(Number(m[2]),col)]={...c,ref:c.ref,style:c.style||{}} }
  }
  snapshot()
}
function layoutPayload(){
  const list=[]
  for(const k in cells.value){ const c={...cells.value[k]}; list.push(c) }
  return {cols:cols.value,rows:rows.value,cells:list}
}
async function saveReport(){
  if(!reportName.value.trim()){ ElMessage.warning('请输入报表名称'); return }
  saving.value=true
  try{
    const report={id:currentReportId.value,name:reportName.value,datasetId:datasetId.value,
      layoutJson:JSON.stringify(layoutPayload()),status:1,createBy:'admin'}
    const res=await api.saveReport({report,remark:currentReportId.value?'编辑并保存':'创建报表'})
    currentReportId.value=res.data.id
    ElMessage.success('保存成功，已生成新版本')
    loadBase()
  } catch(e){} finally{ saving.value=false }
}
async function doPreview(){
  previewing.value=true
  try{
    let res
    if(currentReportId.value && mode.value==='page') res=await api.renderReport(currentReportId.value,{})
    else res=await api.previewReport({layout:layoutPayload(),datasetId:datasetId.value,params:{}})
    previewMatrix.value=res.data.matrix
    previewCols.value=res.data.cols
    ElMessage.success('渲染完成')
  } catch(e){} finally{ previewing.value=false }
}
async function showVersions(){
  versions.value=(await api.reportVersions(currentReportId.value)).data
  versionDialog.value=true
}
async function rollback(row){
  await ElMessageBox.confirm(`确认回滚到版本 v${row.version}？`,'提示',{type:'warning'})
  await api.rollbackReport(currentReportId.value,row.version)
  ElMessage.success('已回滚'); versionDialog.value=false; loadReport(currentReportId.value)
}
watch(mode, m=>{ if(m!=='design' && !previewMatrix.value.length) doPreview() })

const route = useRoute()
onMounted(async()=>{
  await loadBase()
  await loadFields()
  const qid = route.query.id
  if (qid) { currentReportId.value = Number(qid); await loadReport(Number(qid)) }
  else { buildDefaultLayout(); snapshot() }
})
</script>

<style scoped>
.designer{height:calc(100vh - 96px);display:flex;flex-direction:column;background:#fff;border-radius:10px;overflow:hidden;box-shadow:0 1px 3px rgba(15,23,42,.06)}
.designer-toolbar{padding:10px 14px;border-bottom:1px solid #e2e8f0;display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}
.tool-left,.tool-right{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.designer-body{display:flex;flex:1;min-height:0}
.field-panel{width:190px;flex:0 0 190px;border-right:1px solid #e2e8f0;background:#f8fafc;overflow:auto}
.prop-panel{width:250px;flex:0 0 250px;border-left:1px solid #e2e8f0;background:#f8fafc;overflow:auto}
.panel-title{padding:12px;font-weight:700;font-size:13px;color:#334155}
.field-group{margin:0 10px 12px}
.field-group-title{font-size:11px;color:#94a3b8;margin-bottom:6px;font-weight:600}
.field-item{display:flex;align-items:center;gap:8px;padding:7px 10px;background:#fff;border:1px solid #e2e8f0;border-radius:6px;margin-bottom:5px;cursor:grab;font-size:12px;user-select:none}
.field-item:active{cursor:grabbing}
.field-item.dim{border-left:3px solid #2563eb}
.field-item.num{border-left:3px solid #16a34a}
.func-list{padding:0 10px}
.func-item{display:flex;justify-content:space-between;font-size:11px;padding:6px 8px;cursor:pointer;border-radius:4px;color:#475569}
.func-item:hover{background:#e2e8f0}
.func-item b{color:#2563eb}
.canvas-area{flex:1;min-width:0;display:flex;flex-direction:column;background:#eef2f7}
.formula-bar{display:flex;align-items:center;gap:8px;padding:6px 12px;background:#fff;border-bottom:1px solid #e2e8f0}
.cell-ref{min-width:52px;font-weight:700;color:#2563eb;font-family:monospace;font-size:13px}
.fx{font-style:italic;color:#94a3b8;font-weight:700}
.grid-scroll{flex:1;overflow:auto;padding:10px}
.grid-table{border-collapse:collapse;background:#fff;table-layout:fixed}
.grid-table th,.grid-table td{border:1px solid #cbd5e1;height:30px;padding:0 5px;font-size:12px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
.corner,.row-header,.col-header{background:#f1f5f9;color:#64748b;font-weight:600;text-align:center;font-size:11px}
.corner{width:46px}
.row-header{width:46px}
.col-header{height:24px}
.grid-cell{cursor:cell;position:relative}
.grid-cell.selected{outline:2px solid #2563eb;outline-offset:-2px;z-index:2}
.grid-cell.covered{background:#f8fafc!important}
.cell-bound{color:#2563eb}
.cell-formula{color:#dc2626}
.prop-body{padding:12px}
.prop-body :deep(.el-form-item){margin-bottom:12px}
.expert-tip{margin:12px;background:#eff6ff;border:1px solid #bfdbfe;border-radius:8px;padding:10px;font-size:11px;color:#1e40af;line-height:1.6}
.expert-tip p{margin:6px 0 0}
.preview-wrap{flex:1;overflow:auto;padding:12px}
.preview-toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:12px;background:#fff;padding:8px 12px;border-radius:8px}
.render-grid{display:flex;flex-direction:column;gap:0;background:#fff;border:1px solid #cbd5e1}
.render-row{display:flex}
.render-cell{min-width:90px;flex:1;border:1px solid #e2e8f0;padding:8px 10px;font-size:12px;min-height:34px;display:flex;align-items:center;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
.render-cell.empty{background:#fafafa}
</style>
