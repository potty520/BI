<template>
  <div>
    <div class="page-card">
      <div class="page-header">
        <div><h3 class="page-title"><span class="module-tag">M09</span>目录与权限</h3>
          <div class="page-desc">树形目录、可见性控制、报表权限（查看/编辑/导出）、数据行级权限</div></div>
        <el-button type="primary" @click="openDir()"><el-icon><Plus /></el-icon>新建目录/资源</el-button>
      </div>
      <el-row :gutter="16">
        <el-col :span="12">
          <div class="section-title">资源目录树</div>
          <el-tree :data="tree" :props="{label:'name',children:'children'}" default-expand-all node-key="id">
            <template #default="{data}">
              <span>
                <el-icon><Folder v-if="data.type==='folder'"/><Document v-else-if="data.type==='report'"/><Monitor v-else/></el-icon>
                {{ data.name }}
                <el-tag size="small" effect="plain" style="margin-left:6px">{{ data.type }}</el-tag>
              </span>
            </template>
          </el-tree>
        </el-col>
        <el-col :span="12">
          <div class="section-title">权限规则（RBAC + 数据权限）</div>
          <el-alert type="info" :closable="false" style="margin-bottom:10px">
            采用 RBAC + 数据权限规则组合。数据权限示例：<code class="mono">dept_id = ${currentUserDept}</code> 实现行级数据隔离。
          </el-alert>
          <el-table :data="perms" border size="small">
            <el-table-column label="角色" width="120"><template #default="{row}">{{ roleName(row.roleId) }}</template></el-table-column>
            <el-table-column prop="resourceType" label="资源类型" width="100"/>
            <el-table-column prop="resourceId" label="资源ID" width="90"/>
            <el-table-column label="权限" width="90"><template #default="{row}"><el-tag size="small">{{ row.perm }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="70"><template #default="{row}"><el-button link type="danger" @click="delPerm(row)">删</el-button></template></el-table-column>
          </el-table>
          <el-button size="small" style="margin-top:10px" @click="permDialog=true">新增权限规则</el-button>
        </el-col>
      </el-row>
    </div>

    <el-dialog v-model="dirDialog" title="新建目录/资源" width="460px">
      <el-form :model="dirForm" label-width="90px">
        <el-form-item label="名称"><el-input v-model="dirForm.name"/></el-form-item>
        <el-form-item label="类型"><el-select v-model="dirForm.type" style="width:100%"><el-option label="文件夹" value="folder"/><el-option label="报表" value="report"/><el-option label="仪表板" value="dashboard"/></el-select></el-form-item>
        <el-form-item label="父目录ID"><el-input-number v-model="dirForm.parentId" :min="0" style="width:100%"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="dirDialog=false">取消</el-button><el-button type="primary" @click="saveDir">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="permDialog" title="新增权限规则" width="460px">
      <el-form :model="permForm" label-width="90px">
        <el-form-item label="角色"><el-select v-model="permForm.roleId" style="width:100%"><el-option v-for="r in roles" :key="r.id" :label="r.roleName" :value="r.id"/></el-select></el-form-item>
        <el-form-item label="资源类型"><el-select v-model="permForm.resourceType" style="width:100%"><el-option label="directory" value="directory"/><el-option label="report" value="report"/></el-select></el-form-item>
        <el-form-item label="资源ID"><el-input-number v-model="permForm.resourceId" :min="1" style="width:100%"/></el-form-item>
        <el-form-item label="权限"><el-select v-model="permForm.perm" style="width:100%"><el-option label="查看 view" value="view"/><el-option label="编辑 edit" value="edit"/><el-option label="导出 export" value="export"/></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="permDialog=false">取消</el-button><el-button type="primary" @click="savePerm">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Folder, Document, Monitor } from '@element-plus/icons-vue'
import { api } from '../api'

const dirs=ref([]), perms=ref([]), roles=ref([])
const dirDialog=ref(false), dirForm=ref({type:'folder',parentId:0})
const permDialog=ref(false), permForm=ref({resourceType:'directory',perm:'view'})
const tree=computed(()=>{
  const map={}; dirs.value.forEach(d=>map[d.id]={...d,children:[]})
  const roots=[]
  dirs.value.forEach(d=>{ if(d.parentId&&map[d.parentId]) map[d.parentId].children.push(map[d.id]); else roots.push(map[d.id]) })
  return roots
})
const roleName=id=>roles.value.find(r=>r.id===id)?.roleName||id
async function load(){
  const [d,p,r]=await Promise.all([api.directories(),api.permissions(),api.roles()])
  dirs.value=d.data; perms.value=p.data; roles.value=r.data
}
function openDir(){ dirForm.value={type:'folder',parentId:0,name:''}; dirDialog.value=true }
async function saveDir(){ await api.saveDirectory(dirForm.value); ElMessage.success('保存成功'); dirDialog.value=false; load() }
async function savePerm(){ await api.savePermission(permForm.value); ElMessage.success('保存成功'); permDialog.value=false; load() }
async function delPerm(row){ await ElMessageBox.confirm('确认删除？','提示',{type:'warning'}); await api.delPermission(row.id); ElMessage.success('已删除'); load() }
onMounted(load)
</script>
