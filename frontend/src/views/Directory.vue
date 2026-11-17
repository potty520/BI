<template>
  <div>
    <div class="page-card">
      <div class="page-header">
        <div><h3 class="page-title">目录与权限</h3>
          <div class="page-desc">树形目录、资源可见性（查看/编辑）、行级数据范围、列级权限与脱敏</div></div>
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
          <div class="section-title">资源权限规则（rbi_permission）</div>
          <el-alert type="info" :closable="false" style="margin-bottom:10px">
            某资源一旦有规则，就只对被授权角色可见；没有规则视为公开。ADMIN 不受限。
          </el-alert>
          <el-table :data="perms" border size="small" max-height="240">
            <el-table-column label="角色" width="110"><template #default="{row}">{{ roleName(row.roleId) }}</template></el-table-column>
            <el-table-column prop="resourceType" label="资源类型" width="100"/>
            <el-table-column prop="resourceId" label="资源ID" width="80"/>
            <el-table-column label="权限" width="80"><template #default="{row}"><el-tag size="small">{{ row.perm }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="70"><template #default="{row}"><el-button link type="danger" @click="delPerm(row)">删</el-button></template></el-table-column>
          </el-table>
          <el-button size="small" style="margin-top:10px" @click="permDialog=true">新增权限规则</el-button>
        </el-col>
      </el-row>
    </div>

    <div class="page-card" style="margin-top:16px">
      <div class="page-header">
        <div><h3 class="page-title">数据权限</h3>
          <div class="page-desc">行级决定"能看哪些行"，列级决定"能看哪些列 / 值是否脱敏"，均按角色生效</div></div>
      </div>
      <el-row :gutter="16">
        <el-col :span="12">
          <div class="section-title">行级数据范围（rbi_data_scope）</div>
          <el-table :data="scopes" border size="small" max-height="240">
            <el-table-column label="角色" width="100"><template #default="{row}">{{ roleName(row.roleId) }}</template></el-table-column>
            <el-table-column label="数据集" width="90"><template #default="{row}">{{ datasetName(row.datasetId) }}</template></el-table-column>
            <el-table-column prop="columnName" label="列" width="110"/>
            <el-table-column prop="scopeValue" label="可见值（逗号分隔）"/>
            <el-table-column label="操作" width="70"><template #default="{row}"><el-button link type="danger" @click="delScope(row)">删</el-button></template></el-table-column>
          </el-table>
          <el-button size="small" style="margin-top:10px" @click="openScope">新增行级范围</el-button>
          <div class="hint" style="margin-top:8px">数据集 SQL 必须包含 <code class="mono">${row_filter}</code> 占位符，否则查询会被拒绝（fail-closed）。</div>
        </el-col>
        <el-col :span="12">
          <div class="section-title">列级权限 / 脱敏（rbi_column_policy）</div>
          <el-table :data="policies" border size="small" max-height="240">
            <el-table-column label="角色" width="100"><template #default="{row}">{{ roleName(row.roleId) }}</template></el-table-column>
            <el-table-column label="数据集" width="90"><template #default="{row}">{{ datasetName(row.datasetId) }}</template></el-table-column>
            <el-table-column prop="columnName" label="列" width="110"/>
            <el-table-column label="策略" width="110"><template #default="{row}"><el-tag size="small" type="warning">{{ policyLabel(row.policy) }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="70"><template #default="{row}"><el-button link type="danger" @click="delPolicy(row)">删</el-button></template></el-table-column>
          </el-table>
          <el-button size="small" style="margin-top:10px" @click="openPolicy">新增列策略</el-button>
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

    <el-dialog v-model="scopeDlg" title="新增行级数据范围" width="480px">
      <el-form :model="scopeForm" label-width="90px">
        <el-form-item label="角色"><el-select v-model="scopeForm.roleId" style="width:100%"><el-option v-for="r in roles" :key="r.id" :label="r.roleName" :value="r.id"/></el-select></el-form-item>
        <el-form-item label="数据集"><el-select v-model="scopeForm.datasetId" style="width:100%"><el-option v-for="d in datasets" :key="d.id" :label="d.name" :value="d.id"/></el-select></el-form-item>
        <el-form-item label="过滤列"><el-input v-model="scopeForm.columnName" placeholder="如 region"/></el-form-item>
        <el-form-item label="可见值"><el-input v-model="scopeForm.scopeValue" placeholder="如 华北,华东（逗号分隔）"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="scopeDlg=false">取消</el-button><el-button type="primary" @click="saveScope">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="policyDlg" title="新增列级策略" width="480px">
      <el-form :model="policyForm" label-width="90px">
        <el-form-item label="角色"><el-select v-model="policyForm.roleId" style="width:100%"><el-option v-for="r in roles" :key="r.id" :label="r.roleName" :value="r.id"/></el-select></el-form-item>
        <el-form-item label="数据集"><el-select v-model="policyForm.datasetId" style="width:100%"><el-option v-for="d in datasets" :key="d.id" :label="d.name" :value="d.id"/></el-select></el-form-item>
        <el-form-item label="列名"><el-input v-model="policyForm.columnName" placeholder="如 salesperson"/></el-form-item>
        <el-form-item label="策略">
          <el-select v-model="policyForm.policy" style="width:100%">
            <el-option label="隐藏该列" value="hidden"/>
            <el-option label="全掩码 ******" value="mask_all"/>
            <el-option label="手机号 138****1234" value="mask_phone"/>
            <el-option label="身份证 110****1234" value="mask_idcard"/>
            <el-option label="邮箱 a***@x.com" value="mask_email"/>
            <el-option label="姓名 张*" value="mask_name"/>
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="policyDlg=false">取消</el-button><el-button type="primary" @click="savePolicy">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Folder, Document, Monitor } from '@element-plus/icons-vue'
import { api } from '../api'

const dirs = ref([]), perms = ref([]), roles = ref([])
const scopes = ref([]), policies = ref([]), datasets = ref([])
const dirDialog = ref(false), dirForm = ref({ type: 'folder', parentId: 0 })
const permDialog = ref(false), permForm = ref({ resourceType: 'directory', perm: 'view' })
const scopeDlg = ref(false), scopeForm = ref({})
const policyDlg = ref(false), policyForm = ref({ policy: 'mask_all' })

const tree = computed(() => {
  const map = {}; dirs.value.forEach(d => map[d.id] = { ...d, children: [] })
  const roots = []
  dirs.value.forEach(d => { if (d.parentId && map[d.parentId]) map[d.parentId].children.push(map[d.id]); else roots.push(map[d.id]) })
  return roots
})
const roleName = id => roles.value.find(r => r.id === id)?.roleName || id
const datasetName = id => datasets.value.find(d => d.id === id)?.name || id
const policyLabel = p => ({ hidden: '隐藏', mask_all: '全掩码', mask_phone: '手机号', mask_idcard: '身份证', mask_email: '邮箱', mask_name: '姓名' }[p] || p)

async function load() {
  const [d, p, r, s, c, ds] = await Promise.all([
    api.directories(), api.permissions(), api.roles(),
    api.dataScopes(), api.columnPolicies(), api.datasets()
  ])
  dirs.value = d.data; perms.value = p.data; roles.value = r.data
  scopes.value = s.data; policies.value = c.data; datasets.value = ds.data
}

function openDir() { dirForm.value = { type: 'folder', parentId: 0, name: '' }; dirDialog.value = true }
async function saveDir() { await api.saveDirectory(dirForm.value); ElMessage.success('保存成功'); dirDialog.value = false; load() }
async function savePerm() { await api.savePermission(permForm.value); ElMessage.success('保存成功'); permDialog.value = false; load() }
async function delPerm(row) { await ElMessageBox.confirm('确认删除？', '提示', { type: 'warning' }); await api.delPermission(row.id); ElMessage.success('已删除'); load() }

function openScope() { scopeForm.value = { columnName: '', scopeValue: '' }; scopeDlg.value = true }
async function saveScope() {
  if (!scopeForm.value.roleId || !scopeForm.value.datasetId || !scopeForm.value.columnName) return ElMessage.warning('请输入角色/数据集/列名')
  await api.saveDataScope(scopeForm.value); ElMessage.success('保存成功'); scopeDlg.value = false; load()
}
async function delScope(row) { await ElMessageBox.confirm('确认删除该行级范围？', '提示', { type: 'warning' }); await api.delDataScope(row.id); ElMessage.success('已删除'); load() }

function openPolicy() { policyForm.value = { policy: 'mask_all', columnName: '' }; policyDlg.value = true }
async function savePolicy() {
  if (!policyForm.value.roleId || !policyForm.value.datasetId || !policyForm.value.columnName) return ElMessage.warning('请输入角色/数据集/列名')
  await api.saveColumnPolicy(policyForm.value); ElMessage.success('保存成功'); policyDlg.value = false; load()
}
async function delPolicy(row) { await ElMessageBox.confirm('确认删除该列策略？', '提示', { type: 'warning' }); await api.delColumnPolicy(row.id); ElMessage.success('已删除'); load() }

onMounted(load)
</script>
