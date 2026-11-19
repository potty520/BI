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
const chartEls={}, chartInstances={}, chartROs={}
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
    if(inst && inst.getDom()!==el){ inst.dispose(); inst=null }
    if(!inst){
      inst=echarts.init(el); chartInstances[item.id]=inst
      chartROs[item.id]?.disconnect()
      chartROs[item.id]=new ResizeObserver(()=>inst.resize())
      chartROs[item.id].observe(el)
    }
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
        tooltip:{trigger:'item',backgroundColor:'rgba(9,18,38,.92)',borderColor:'rgba(56,189,248,.35)',textStyle:{color:'#dbeafe',fontSize:11}},
        series:[{type:'pie',data,radius:['10%','75%'],center:['50%','50%'],
          roseType:ctype==='rose'?'radius':undefined,
          minShowLabelAngle:.5,
          itemStyle:{borderColor:'rgba(9,18,38,.9)',borderWidth:2},
          label:{fontSize:9,color:'#94a3b8',formatter:p=>{const s=String(p.name);return s.length>6?s.slice(0,5)+'…':s}},
          labelLine:{length:5,length2:4,lineStyle:{color:'rgba(148,163,184,.4)'}},
          labelLayout:{hideOverlap:true}}] }
    } else {
      const truncX=v=>{const s=String(v);return s.length>6?s.slice(0,5)+'…':s}
      const truncY=v=>{const s=String(v);return s.length>12?s.slice(0,11)+'…':s}
      const fmtNum=v=>{const n=Number(v);if(!isFinite(n))return v;const a=Math.abs(n);
        if(a>=1e8)return (n/1e8).toFixed(1)+'亿';if(a>=1e4)return (n/1e4).toFixed(a%1e4?1:0)+'万';return n}
      const rotX=!isLine&&names.length>4
      opt={ color:['#38bdf8'],
        tooltip:{trigger:'axis',backgroundColor:'rgba(9,18,38,.92)',borderColor:'rgba(56,189,248,.35)',textStyle:{color:'#dbeafe',fontSize:11}},
        grid:{left:6,right:isH?22:16,top:16,bottom:4,containLabel:true},
        xAxis:isH?{type:'value',axisLabel:{...axisLabel,formatter:fmtNum},splitLine:{lineStyle:{color:'rgba(148,163,184,.12)'}}}
          :{type:'category',data:names,axisLabel:{...axisLabel,rotate:rotX?40:0,interval:0,formatter:truncX},axisLine:{lineStyle:{color:'rgba(148,163,184,.3)'}}},
        yAxis:isH?{type:'category',data:names,axisLabel:{...axisLabel,hideOverlap:true,formatter:truncY},axisLine:{lineStyle:{color:'rgba(148,163,184,.3)'}}}
          :{type:'value',axisLabel:{...axisLabel,formatter:fmtNum},splitLine:{lineStyle:{color:'rgba(148,163,184,.12)'}}},
        series:[isLine
          ?{type:'line',smooth:true,data:values,symbolSize:5,
            areaStyle:{color:'rgba(56,189,248,.16)'},
            lineStyle:{color:'#38bdf8',width:2,shadowColor:'rgba(56,189,248,.55)',shadowBlur:9},
            itemStyle:{color:'#7dd3fc',borderColor:'#38bdf8',borderWidth:1}}
          :{type:'bar',barMaxWidth:26,
            data:values.map((v,i)=>({value:v,itemStyle:{
              color:{type:'linear',x:0,y:isH?0:1,x2:0,y2:isH?1:0,colorStops:[{offset:0,color:palette[i%palette.length]},{offset:1,color:'rgba(56,189,248,.35)'}]},
              borderRadius:isH?[0,3,3,0]:[3,3,0,0]}}))}]
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
function onWinResize(){ for(const k in chartInstances) chartInstances[k].resize() }
window.addEventListener('mouseup',onUp)
window.addEventListener('resize',onWinResize)
onBeforeUnmount(()=>{ window.removeEventListener('mouseup',onUp); window.removeEventListener('resize',onWinResize); for(const k in chartROs) chartROs[k]?.disconnect(); for(const k in chartInstances) chartInstances[k].dispose() })
onMounted(async()=>{ await loadDashboards(); await loadLayout() })
</script>

<style scoped>
.dashboard{
  position:relative;height:calc(100vh - 260px);min-height:480px;border-radius:12px;overflow:hidden;margin-top:12px;
  background:
    radial-gradient(ellipse at 12% 8%, rgba(56,189,248,.14), transparent 42%),
    radial-gradient(ellipse at 88% 92%, rgba(168,85,247,.12), transparent 42%),
    radial-gradient(ellipse at 50% 50%, rgba(14,42,80,.5), transparent 75%),
    linear-gradient(160deg,#0c1e3c 0%,#0f172a 55%,#0a1226 100%);
  box-shadow:inset 0 0 60px rgba(2,8,20,.6);
}
/* 网格底纹 */
.dashboard::before{content:'';position:absolute;inset:0;pointer-events:none;
  background-image:linear-gradient(rgba(56,189,248,.05) 1px,transparent 1px),linear-gradient(90deg,rgba(56,189,248,.05) 1px,transparent 1px);
  background-size:38px 38px;
  mask-image:radial-gradient(ellipse at center,black 40%,transparent 95%);
  -webkit-mask-image:radial-gradient(ellipse at center,black 40%,transparent 95%);
}
.dash-item{
  position:absolute;display:flex;flex-direction:column;overflow:hidden;border-radius:10px;
  background:linear-gradient(180deg,rgba(17,34,64,.88),rgba(9,18,38,.82));
  border:1px solid rgba(56,189,248,.22);
  box-shadow:0 10px 28px rgba(2,8,20,.5),inset 0 0 28px rgba(56,189,248,.045);
  transition:border-color .2s,box-shadow .2s;
}
.dash-item:hover{border-color:rgba(56,189,248,.5);box-shadow:0 12px 32px rgba(2,8,20,.6),0 0 18px rgba(56,189,248,.15),inset 0 0 28px rgba(56,189,248,.06)}
/* 四角科技感括号 */
.dash-item::before,.dash-item::after{content:'';position:absolute;width:12px;height:12px;pointer-events:none;opacity:.85}
.dash-item::before{left:-1px;top:-1px;border-left:2px solid #38bdf8;border-top:2px solid #38bdf8;border-top-left-radius:10px}
.dash-item::after{right:-1px;bottom:-1px;border-right:2px solid #38bdf8;border-bottom:2px solid #38bdf8;border-bottom-right-radius:10px}
.dash-head{
  height:32px;display:flex;align-items:center;justify-content:space-between;padding:0 12px;cursor:move;user-select:none;
  color:#dbeafe;font-size:12px;font-weight:600;letter-spacing:.06em;
  background:linear-gradient(90deg,rgba(56,189,248,.14),rgba(56,189,248,.02) 60%,transparent);
  border-bottom:1px solid rgba(56,189,248,.16);
}
.dash-head :deep(.el-icon){color:rgba(148,163,184,.55);font-size:13px}
.dash-chart{flex:1;min-height:0;padding:2px 4px 4px}
.resize-handle{position:absolute;right:0;bottom:0;width:16px;height:16px;cursor:nwse-resize;z-index:2;background:linear-gradient(135deg,transparent 50%,#38bdf8 50%,#38bdf8 70%,transparent 70%);opacity:.6}
.resize-handle:hover{opacity:1}
</style>
