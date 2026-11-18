<template>
  <div>
    <div class="page-card">
      <div class="page-header">
        <div><h3 class="page-title">仪表板大屏</h3>
          <div class="page-desc">自由拖拽布局、网格吸附、组件缩放、背景设置</div></div>
        <div class="toolbar">
          <el-select v-model="currentId" style="width:180px" @change="loadLayout"><el-option v-for="d in dashboards" :key="d.id" :label="d.name" :value="d.id"/></el-select>
          <el-button @click="gridSnap=!gridSnap"><el-icon><Grid /></el-icon>网格吸附 {{ gridSnap?'开':'关' }}</el-button>
          <el-button type="primary" @click="saveLayout" :loading="saving"><el-icon><DocumentChecked /></el-icon>保存布局</el-button>
        </div>
      </div>
      <el-alert type="info" :closable="false" style="margin-bottom:12px">
        拖拽下方图表卡片标题可移动位置；右下角手柄可缩放尺寸。布局以百分比坐标持久化，支持网格吸附。
      </el-alert>
    </div>

    <div class="dashboard" ref="board" @mousemove="onMove" @mouseup="onUp">
      <div v-for="item in layout" :key="item.id" class="dash-item"
           :style="{left:item.x+'%',top:item.y+'%',width:item.w+'%',height:item.h+'%',zIndex:item.z||1}">
        <div class="dash-head" @mousedown="startDrag($event,item)">
          <span>{{ item.title }}</span>
          <span class="dash-actions"><el-icon><Rank /></el-icon></span>
        </div>
        <div :ref="el=>setChartRef(el,item.id)" class="dash-chart"></div>
        <div class="resize-handle" @mousedown="startResize($event,item)"></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { Grid, DocumentChecked, Rank } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { api } from '../api'

const dashboards=ref([]), currentId=ref(1), layout=ref([])
const gridSnap=ref(true), saving=ref(false), board=ref(null)
const chartEls={}, chartInstances={}
const chartTypes=ref({})
const drag=ref(null)
function setChartRef(el,id){ if(el) chartEls[id]=el }

function defaultLayout(){
  return [
    {id:'c1',title:'各大区销售额',chartId:1,x:1,y:2,w:48,h:45},
    {id:'c2',title:'产品类别占比',chartId:2,x:51,y:2,w:48,h:45},
    {id:'c3',title:'月度销售趋势',chartId:3,x:1,y:50,w:98,h:45}
  ]
}
async function loadDashboards(){
  dashboards.value=(await api.dashboards()).data
  try{
    const list=(await api.charts()).data||[]
    const m={}; list.forEach(c=>m[c.id]=c.type); chartTypes.value=m
  }catch(e){ chartTypes.value={} }
  if(dashboards.value.length) currentId.value=dashboards.value[0].id
}
async function loadLayout(){
  const d=dashboards.value.find(x=>x.id===currentId.value)
  let arr=defaultLayout()
  try{ const parsed=JSON.parse(d?.layoutJson||'null'); if(Array.isArray(parsed)&&parsed.length) arr=parsed }catch(e){}
  layout.value=arr
  await nextTick()
  await nextTick()
  renderAll()
}
async function renderAll(){
  for(const item of layout.value){
    const el=chartEls[item.id]; if(!el) continue
    let inst=chartInstances[item.id]
    if(!inst){ inst=echarts.init(el); chartInstances[item.id]=inst }
    const data=(await api.chartData(item.chartId)).data
    const ctype=chartTypes.value[item.chartId]||'bar'
    const isPie=['pie','rose','funnel'].includes(ctype)
    const isLine=['line','area'].includes(ctype)
    const isH=ctype==='barH'
    const names=data.map(d=>d.name)
    const values=data.map(d=>d.value)
    const axisLabel={fontSize:9,color:'#94a3b8'}
    let opt
    const palette=['#38bdf8','#22c55e','#f59e0b','#ef4444','#a855f7','#14b8a6','#eab308','#3b82f6','#ec4899']
    if(isPie){
      opt={ color:palette,
        tooltip:{trigger:'item'},
        series:[{type:'pie',data,radius:'62%',
          roseType:ctype==='rose'?'radius':undefined,
          label:{fontSize:9,color:'#94a3b8'}}] }
    } else {
      opt={ color:['#38bdf8'],
        tooltip:{trigger:'axis'},
        grid:{left:isH?74:45,right:18,bottom:30,top:20},
        xAxis:isH?{type:'value',axisLabel}:{type:'category',data:names,axisLabel},
        yAxis:isH?{type:'category',data:names,axisLabel}:{type:'value',axisLabel},
        series:[isLine
          ?{type:'line',smooth:true,data:values,symbolSize:5,
            areaStyle:ctype==='area'?{opacity:.25}:undefined,
            lineStyle:{color:'#38bdf8'},itemStyle:{color:'#38bdf8'}}
          :{type:'bar',data:values.map((v,i)=>({value:v,itemStyle:{color:palette[i%palette.length]}})),
            itemStyle:{borderRadius:isH?[0,3,3,0]:[3,3,0,0]}}]
      }
    }
    inst.setOption(opt,true)
  }
}
function snap(v){ return gridSnap.value?Math.round(v*2)/2:Math.round(v*10)/10 }
function startDrag(e,item){
  e.preventDefault()
  const rect=board.value.getBoundingClientRect()
  drag.value={type:'move',item,sx:e.clientX,sy:e.clientY,ox:item.x,oy:item.y,rect}
}
function startResize(e,item){
  e.preventDefault(); e.stopPropagation()
  const rect=board.value.getBoundingClientRect()
  drag.value={type:'resize',item,sx:e.clientX,sy:e.clientY,ow:item.w,oh:item.h,rect}
}
function onMove(e){
  if(!drag.value) return
  const d=drag.value, rect=d.rect
  const dx=(e.clientX-d.sx)/rect.width*100, dy=(e.clientY-d.sy)/rect.height*100
  if(d.type==='move'){
    d.item.x=Math.max(0,Math.min(100-d.item.w,snap(d.ox+dx)))
    d.item.y=Math.max(0,Math.min(100-d.item.h,snap(d.oy+dy)))
  }else{
    d.item.w=Math.max(15,Math.min(100-d.item.x,snap(d.ow+dx)))
    d.item.h=Math.max(15,Math.min(100-d.item.y,snap(d.oh+dy)))
  }
  for(const k in chartInstances) chartInstances[k].resize()
}
function onUp(){ if(drag.value){ drag.value=null } }
async function saveLayout(){
  saving.value=true
  try{ await api.saveDashboard({id:currentId.value,name:dashboards.value.find(x=>x.id===currentId.value)?.name||'驾驶舱',layoutJson:JSON.stringify(layout.value),background:'#0f172a'}); ElMessage.success('布局已保存') }
  finally{ saving.value=false }
}
window.addEventListener('mouseup',onUp)
onBeforeUnmount(()=>{ window.removeEventListener('mouseup',onUp); for(const k in chartInstances) chartInstances[k].dispose() })
onMounted(async()=>{ await loadDashboards(); await loadLayout() })
</script>

<style scoped>
.dashboard{position:relative;height:calc(100vh - 260px);min-height:480px;background:radial-gradient(ellipse at top,#1e3a5f,#0f172a);border-radius:12px;overflow:hidden;margin-top:12px}
.dash-item{position:absolute;background:rgba(255,255,255,.04);border:1px solid rgba(148,163,184,.25);border-radius:8px;display:flex;flex-direction:column;overflow:hidden}
.dash-head{height:30px;display:flex;align-items:center;justify-content:space-between;padding:0 10px;color:#e2e8f0;font-size:12px;font-weight:600;cursor:move;background:rgba(148,163,184,.1);user-select:none}
.dash-chart{flex:1;min-height:0}
.resize-handle{position:absolute;right:0;bottom:0;width:16px;height:16px;cursor:nwse-resize;background:linear-gradient(135deg,transparent 50%,#38bdf8 50%,#38bdf8 70%,transparent 70%)}
</style>
