<template>
  <div>
    <div class="page-card">
      <div class="page-header">
        <div><h3 class="page-title">可视化图表</h3>
          <div class="page-desc">基于 ECharts 封装，10+ 图表类型、数据绑定、样式配置、图表联动</div></div>
        <div class="toolbar"><el-select v-model="datasetId" style="width:170px"><el-option v-for="d in datasets" :key="d.id" :label="d.name" :value="d.id"/></el-select>
          <el-button type="primary" @click="openDialog()"><el-icon><Plus /></el-icon>新建图表</el-button></div>
      </div>
      <el-row :gutter="16">
        <el-col :span="8" v-for="c in charts" :key="c.id" style="margin-bottom:16px">
          <div class="chart-card">
            <div class="chart-head">
              <div><b>{{ c.name }}</b><el-tag size="small" effect="plain" style="margin-left:8px">{{ typeLabel(c.type) }}</el-tag></div>
              <div><el-button link type="primary" @click="openDialog(c)">配置</el-button><el-button link type="danger" @click="del(c)">删除</el-button></div>
            </div>
            <div :ref="el=>setChartRef(el,c.id)" class="chart-box"></div>
          </div>
        </el-col>
      </el-row>
    </div>

    <el-dialog v-model="dialog" :title="form.id?'配置图表':'新建图表'" width="640px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="图表名称"><el-input v-model="form.name"/></el-form-item>
        <el-form-item label="图表类型">
          <el-select v-model="form.type" style="width:100%">
            <el-option v-for="t in types" :key="t.v" :label="t.l" :value="t.v"/>
          </el-select>
        </el-form-item>
        <el-form-item label="数据集"><el-select v-model="form.datasetId" style="width:100%"><el-option v-for="d in datasets" :key="d.id" :label="d.name" :value="d.id"/></el-select></el-form-item>
        <el-form-item label="维度字段"><el-select v-model="cfg.dim" style="width:100%"><el-option v-for="f in fields" :key="f.name" :label="f.name" :value="f.name"/></el-select></el-form-item>
        <el-form-item label="度量字段"><el-select v-model="cfg.metric" style="width:100%"><el-option v-for="f in fields" :key="f.name" :label="f.name" :value="f.name"/></el-select></el-form-item>
        <el-form-item label="聚合方式"><el-select v-model="cfg.agg" style="width:100%"><el-option label="求和 SUM" value="SUM"/><el-option label="计数 COUNT" value="COUNT"/><el-option label="平均 AVG" value="AVG"/></el-select></el-form-item>
        <el-form-item label="标题"><el-input v-model="cfg.title"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="save">保存并渲染</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { api } from '../api'

const charts=ref([]), datasets=ref([]), fields=ref([]), datasetId=ref(1)
const dialog=ref(false), form=ref({type:'bar'}), cfg=ref({dim:'region',metric:'amount',agg:'SUM',title:''})
const chartEls={}, chartInstances={}
const types=[
  {v:'bar',l:'柱状图'},{v:'line',l:'折线图'},{v:'pie',l:'饼图'},{v:'area',l:'面积图'},
  {v:'scatter',l:'散点图'},{v:'gauge',l:'仪表盘'},{v:'barH',l:'横向条形图'},{v:'stack',l:'堆叠柱状图'},
  {v:'funnel',l:'漏斗图'},{v:'radar',l:'雷达图'},{v:'rose',l:'玫瑰图'},{v:'comb',l:'组合图'}
]
const typeLabel=t=>types.find(x=>x.v===t)?.l||t
function setChartRef(el,id){ if(el) chartEls[id]=el }
function parseCfg(c){ try{return JSON.parse(c.configJson||'{}')}catch(e){return {}} }

async function load(){
  const [c,d]=await Promise.all([api.charts(),api.datasets()])
  charts.value=c.data; datasets.value=d.data
  fields.value=(await api.datasetFields(datasetId.value)).data
  await nextTick()
  for(const ch of charts.value) renderChart(ch)
}
async function renderChart(c){
  const el=chartEls[c.id]; if(!el) return
  let inst=chartInstances[c.id]
  if(!inst){ inst=echarts.init(el); chartInstances[c.id]=inst }
  const conf=parseCfg(c)
  const data=(await api.chartData(c.id)).data
  inst.setOption(option(c.type,conf,data),true)
}
function option(type,conf,data){
  const names=data.map(d=>d.name), values=data.map(d=>d.value)
  const title=conf.title||''
  const palette=['#2563eb','#0891b2','#16a34a','#ea580c','#7c3aed','#db2777','#ca8a04','#059669']
  // 单系列柱状/折线图不显示底部图例（"企业数"之类是冗余信息，且会与旋转后的轴标签重叠）
  const hasLegend=['pie','rose','funnel','comb','stack'].includes(type)
  const needRot=!['pie','rose','funnel','gauge','radar'].includes(type) && names.length>6
  const base={color:palette,title:{text:title,left:'center',textStyle:{fontSize:13,color:'#334155'}},
    tooltip:{trigger:'axis'},grid:{left:50,right:20,bottom:needRot?70:(hasLegend?58:40),top:title?50:30},
    ...(hasLegend?{legend:{bottom:0,textStyle:{fontSize:10}}}:{})}
  // 轴标签防重叠：类目多时旋转，超长名称截断加省略号（悬停 tooltip 仍显示全名）
  const trunc=v=>{const s=String(v);return s.length>10?s.slice(0,9)+'…':s}
  if(type==='pie'||type==='rose'||type==='funnel'){
    delete base.grid; base.tooltip={trigger:'item'}
    return {...base,series:[{type:type==='rose'?'pie':type,name:conf.metric||'数值',data,radius:type==='rose'?['20%','70%']:'60%',
      ...(type==='rose'?{roseType:'area'}:{}),label:{fontSize:10}}]}
  }
  if(type==='gauge'){
    const v=values[0]||0
    return {...base,series:[{type:'gauge',detail:{formatter:'{value}'},data:[{value:v,name:conf.metric||''}],max:Math.max(v*1.5,100)}]}
  }
  if(type==='radar'){
    delete base.grid
    return {...base,radar:{indicator:names.map(n=>({name:n,max:Math.max(...values)*1.2}))},
      series:[{type:'radar',data:[{value:values,name:conf.metric||''}]}]}
  }
  if(type==='scatter'){
    return {...base,xAxis:{type:'category',data:names,axisLabel:{fontSize:10,rotate:20}},
      yAxis:{type:'value'},series:[{type:'scatter',data:values,symbolSize:12}]}
  }
  const seriesType=['line','area'].includes(type)?'line':'bar'
  // 柱状图按类目着色：每个地名/类目一个颜色（line 保持系列色不变）
  const barData = seriesType==='bar'
    ? values.map((v,i)=>({value:v, itemStyle:{color:palette[i%palette.length]}}))
    : values
  const st={type:seriesType,name:conf.metric||'数值',data:barData,
    ...(seriesType==='line'&&type==='area'?{areaStyle:{opacity:.25}}:{}),
    ...(type==='stack'?{stack:'total'}:{}),itemStyle:{borderRadius:type==='bar'?[4,4,0,0]:0}}
  let series=[st]
  if(type==='comb') series=[st,{type:'line',name:'趋势',data:values,yAxisIndex:0,smooth:true}]
  return {...base,xAxis:{type:'category',data:names,axisLabel:{fontSize:10,rotate:needRot?35:0,hideOverlap:true,
    margin:10,formatter:trunc}},
    yAxis:{type:'value'},series}
}
function openDialog(row){
  form.value=row?{...row}:{type:'bar',datasetId:datasetId.value,name:'新图表'}
  const c=parseCfg(row)
  cfg.value={dim:c.dim||'region',metric:c.metric||'amount',agg:c.agg||'SUM',title:c.title||''}
  dialog.value=true
}
async function save(){
  form.value.configJson=JSON.stringify(cfg.value)
  if(!form.value.datasetId) form.value.datasetId=datasetId.value
  await api.saveChart(form.value); ElMessage.success('保存成功'); dialog.value=false; load()
}
async function del(c){ await ElMessageBox.confirm(`确认删除「${c.name}」？`,'提示',{type:'warning'}); await api.delChart(c.id); ElMessage.success('已删除'); load() }
onMounted(load)
</script>

<style scoped>
.chart-card{background:#fff;border-radius:10px;padding:14px;box-shadow:0 1px 3px rgba(15,23,42,.06)}
.chart-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;font-size:13px}
.chart-box{height:260px;width:100%}
</style>
