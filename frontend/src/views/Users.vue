<template>
  <div>
    <div class="page-card">
      <div class="page-header">
        <div><h3 class="page-title"><span class="module-tag">M09</span>用户与角色管理</h3>
          <div class="page-desc">用户 CRUD、角色定义、用户-角色关联（RBAC）</div></div>
        <div class="toolbar"><el-button @click="loadRoles"><el-icon><Refresh /></el-icon>刷新</el-button><el-button type="primary" @click="openUser()"><el-icon><Plus /></el-icon>新建用户</el-button></div>
      </div>
      <el-row :gutter="16">
        <el-col :span="16">
          <el-table :data="users" border size="small">
            <el-table-column prop="id" label="ID" width="60"/>
            <el-table-column prop="username" label="用户名" width="120"/>
            <el-table-column prop="realName" label="姓名" width="120"/>
            <el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.status===1?'success':'danger'" size="small">{{ row.status===1?'启用':'禁用' }}</el-tag></template></el-table-column>
            <el-table-column prop="createTime" label="创建时间" width="170"/>
            <el-table-column label="操作" min-width="220">
              <template #default="{row}">
                <el-button link type="primary" @click="assign(row)">分配角色</el-button>
                <el-button link type="primary" @click="openUser(row)">编辑</el-button>
                <el-button link type="warning" @click="resetPwd(row)">重置密码</el-button>
                <el-button link type="danger" @click="delUser(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-col>
        <el-col :span="8">
          <div style="background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;padding:14px">
            <div class="page-header" style="margin-bottom:10px"><b style="font-size:14px">角色列表</b><el-button size="small" @click="openRole()">新建角色</el-button></div>
            <div v-for="r in roles" :key="r.id" class="role-item">
              <div><b>{{ r.roleName }}</b><el-tag size="small" effect="plain" style="margin-left:8px">{{ r.roleCode }}</el-tag></div>
              <div class="hint">{{ r.remark }}</div>
              <el-button link type="danger" size="small" @click="delRole(r)">删除</el-button>
            </div>
          </div>
        </el-col>
      </el-row>
    </div>

    <el-dialog v-model="userDialog" :title="userForm.id?'编辑用户':'新建用户'" width="480px">
      <el-form :model="userForm" label-width="90px">
        <el-form-item label="用户名"><el-input v-model="userForm.username"/></el-form-item>
        <el-form-item label="姓名"><el-input v-model="userForm.realName"/></el-form-item>
        <el-form-item label="状态"><el-select v-model="userForm.status" style="width:100%"><el-option :value="1" label="启用"/><el-option :value="0" label="禁用"/></el-select></el-form-item>
        <el-form-item label="初始密码"><el-input value="123456" disabled/></el-form-item>
      </el-form>
      <template #footer><el-button @click="userDialog=false">取消</el-button><el-button type="primary" @click="saveUser">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="roleDialog" title="新建角色" width="420px">
      <el-form :model="roleForm" label-width="90px">
        <el-form-item label="角色编码"><el-input v-model="roleForm.roleCode"/></el-form-item>
        <el-form-item label="角色名称"><el-input v-model="roleForm.roleName"/></el-form-item>
        <el-form-item label="备注"><el-input v-model="roleForm.remark"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="roleDialog=false">取消</el-button><el-button type="primary" @click="saveRole">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="assignDialog" title="分配角色" width="420px">
      <div class="hint" style="margin-bottom:10px">为用户「{{ currentUser?.realName }}」选择角色：</div>
      <el-checkbox-group v-model="checkedRoles">
        <el-checkbox v-for="r in roles" :key="r.id" :value="r.id" style="display:block;margin:8px 0">{{ r.roleName }} ({{ r.roleCode }})</el-checkbox>
      </el-checkbox-group>
      <template #footer><el-button @click="assignDialog=false">取消</el-button><el-button type="primary" @click="saveAssign">确定</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { api } from '../api'

const users=ref([]), roles=ref([])
const userDialog=ref(false), userForm=ref({status:1})
const roleDialog=ref(false), roleForm=ref({})
const assignDialog=ref(false), currentUser=ref(null), checkedRoles=ref([])

async function load(){ users.value=(await api.users()).data }
async function loadRoles(){ roles.value=(await api.roles()).data }
function openUser(row){ userForm.value=row?{...row}:{status:1,username:'',realName:''}; userDialog.value=true }
function openRole(){ roleForm.value={}; roleDialog.value=true }
async function saveUser(){ await api.saveUser(userForm.value); ElMessage.success('保存成功'); userDialog.value=false; load() }
async function saveRole(){ await api.saveRole(roleForm.value); ElMessage.success('保存成功'); roleDialog.value=false; loadRoles() }
async function resetPwd(row){ await api.resetPwd(row.id); ElMessage.success('密码已重置为 123456') }
async function delUser(row){ await ElMessageBox.confirm('确认删除？','提示',{type:'warning'}); await api.delUser(row.id); ElMessage.success('已删除'); load() }
async function delRole(row){ await ElMessageBox.confirm('确认删除？','提示',{type:'warning'}); await api.delRole(row.id); ElMessage.success('已删除'); loadRoles() }
async function assign(row){
  currentUser.value=row; checkedRoles.value=(await api.userRoles(row.id)).data; assignDialog.value=true
}
async function saveAssign(){ await api.assignRoles(currentUser.value.id,checkedRoles.value); ElMessage.success('角色已分配'); assignDialog.value=false }
onMounted(()=>{ load(); loadRoles() })
</script>

<style scoped>
.role-item{padding:10px 0;border-bottom:1px dashed #e2e8f0;font-size:12px}
</style>
