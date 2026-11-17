import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// 请求：自动带上 Bearer 令牌
request.interceptors.request.use(config => {
  const token = localStorage.getItem('rbi_token')
  if (token) config.headers.Authorization = 'Bearer ' + token
  return config
})

// 响应：统一错误提示；401 直接清理登录态并跳登录页
request.interceptors.response.use(
  res => {
    // 文件下载（blob）不做业务码校验
    if (res.config && res.config.responseType === 'blob') return res
    const data = res.data
    if (data && data.code !== undefined && data.code !== 0) {
      ElMessage.error(data.msg || '请求失败')
      return Promise.reject(data)
    }
    return data
  },
  err => {
    const status = err.response?.status
    const msg = err.response?.data?.msg || err.message || '网络错误'
    if (status === 401) {
      localStorage.removeItem('rbi_token')
      localStorage.removeItem('rbi_user')
      ElMessage.error(msg || '登录已过期，请重新登录')
      if (!location.hash.startsWith('#/login')) location.hash = '#/login'
      return Promise.reject(err)
    }
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

/** 触发浏览器下载 */
export function downloadBlob(data, fileName) {
  const blob = data instanceof Blob ? data : new Blob([data])
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

export default request

export const api = {
  // 认证
  login: (data) => request.post('/auth/login', data),
  loginMfa: (data) => request.post('/auth/login/mfa', data),
  logout: () => request.post('/auth/logout'),
  logoutAll: () => request.post('/auth/logout-all'),
  sessions: () => request.get('/auth/sessions'),
  revokeSession: (jti) => request.delete('/auth/sessions/' + jti),
  oidcConfig: () => request.get('/auth/oidc/config'),
  oidcAuthorize: () => request.get('/auth/oidc/authorize'),
  oidcCallback: (params) => request.get('/auth/oidc/callback', { params }),
  mfaSetup: () => request.post('/auth/mfa/setup'),
  mfaEnable: (data) => request.post('/auth/mfa/enable', data),
  mfaDisable: (data) => request.post('/auth/mfa/disable', data),
  forgotPassword: (data) => request.post('/auth/forgot', data),
  resetPassword: (data) => request.post('/auth/reset', data),
  me: () => request.get('/auth/me'),
  changePassword: (d) => request.post('/auth/password', d),
  updateProfile: (d) => request.put('/auth/profile', d),
  publicHealth: () => request.get('/health'),
  // 数据源
  datasources: () => request.get('/datasources'),
  saveDatasource: (d) => request.post('/datasources', d),
  delDatasource: (id) => request.delete('/datasources/' + id),
  testDatasource: (d) => request.post('/datasources/test', d),
  drivers: () => request.get('/datasource/drivers'),
  // 数据集
  datasets: () => request.get('/datasets'),
  saveDataset: (d) => request.post('/datasets', d),
  delDataset: (id) => request.delete('/datasets/' + id),
  dataset: (id) => request.get('/datasets/' + id),
  queryDataset: (id, params) => request.post('/datasets/' + id + '/query', params || {}),
  datasetFields: (id) => request.get('/datasets/' + id + '/fields'),
  exportDataset: (id, params) => request.get('/datasets/' + id + '/export.csv', { params: params || {}, responseType: 'blob' }),
  executeSql: (data) => request.post('/sql/execute', data),
  // 报表
  reports: () => request.get('/reports'),
  report: (id) => request.get('/reports/' + id),
  saveReport: (data) => request.post('/reports', data),
  delReport: (id) => request.delete('/reports/' + id),
  renderReport: (id, params) => request.post('/reports/' + id + '/render', params || {}),
  previewReport: (data) => request.post('/reports/preview', data),
  exportReport: (id, params) => request.get('/reports/' + id + '/export', { params: params || {}, responseType: 'blob' }),
  exportReportPdf: (id, params) => request.get('/reports/' + id + '/export.pdf', { params: params || {}, responseType: 'blob' }),
  reportVersions: (id) => request.get('/reports/' + id + '/versions'),
  rollbackReport: (id, version) => request.post('/reports/' + id + '/rollback', { version }),
  // 参数
  params: () => request.get('/params'),
  saveParam: (p) => request.post('/params', p),
  delParam: (id) => request.delete('/params/' + id),
  paramOptions: (code, parentValue) => request.get('/params/' + code + '/options', { params: { parentValue } }),
  // 图表
  charts: () => request.get('/charts'),
  saveChart: (c) => request.post('/charts', c),
  delChart: (id) => request.delete('/charts/' + id),
  chartData: (id) => request.post('/charts/' + id + '/data'),
  dashboards: () => request.get('/dashboards'),
  saveDashboard: (d) => request.post('/dashboards', d),
  // 填报
  form: (id) => request.get('/forms/' + id),
  submitForm: (id, records, op) => request.post('/forms/' + id + '/submit', { records, op }),
  formTableData: (id) => request.get('/forms/' + id + '/data'),
  // 分析
  analysisRun: (data) => request.post('/analysis/run', data),
  analysisToSql: (config) => request.post('/analysis/tosql', config),
  // 管理
  users: () => request.get('/admin/users'),
  saveUser: (u) => request.post('/admin/users', u),
  delUser: (id) => request.delete('/admin/users/' + id),
  resetPwd: (id) => request.post('/admin/users/' + id + '/resetpwd'),
  kickUser: (id) => request.post('/admin/users/' + id + '/kick'),
  roles: () => request.get('/admin/roles'),
  saveRole: (r) => request.post('/admin/roles', r),
  delRole: (id) => request.delete('/admin/roles/' + id),
  userRoles: (id) => request.get('/admin/users/' + id + '/roles'),
  assignRoles: (id, roleIds) => request.post('/admin/users/' + id + '/roles', roleIds),
  depts: () => request.get('/admin/depts'),
  directories: () => request.get('/admin/directories'),
  saveDirectory: (d) => request.post('/admin/directories', d),
  delDirectory: (id) => request.delete('/admin/directories/' + id),
  permissions: () => request.get('/admin/permissions'),
  savePermission: (p) => request.post('/admin/permissions', p),
  delPermission: (id) => request.delete('/admin/permissions/' + id),
  dataScopes: () => request.get('/admin/data-scopes'),
  saveDataScope: (s) => request.post('/admin/data-scopes', s),
  delDataScope: (id) => request.delete('/admin/data-scopes/' + id),
  columnPolicies: () => request.get('/admin/column-policies'),
  saveColumnPolicy: (p) => request.post('/admin/column-policies', p),
  delColumnPolicy: (id) => request.delete('/admin/column-policies/' + id),
  schedules: () => request.get('/admin/schedules'),
  saveSchedule: (s) => request.post('/admin/schedules', s),
  delSchedule: (id) => request.delete('/admin/schedules/' + id),
  runSchedule: (id) => request.post('/admin/schedules/' + id + '/run'),
  logs: (params) => request.get('/admin/logs', { params: params || {} }),
  exportLogs: (params) => request.get('/admin/logs/export', { params: params || {}, responseType: 'blob' }),
  metrics: () => request.get('/admin/metrics'),
  saveMetric: (m) => request.post('/admin/metrics', m),
  delMetric: (id) => request.delete('/admin/metrics/' + id),
  analyses: () => request.get('/admin/analyses'),
  saveAnalysis: (a) => request.post('/admin/analyses', a),
  delAnalysis: (id) => request.delete('/admin/analyses/' + id),
  health: () => request.get('/admin/health'),
  overview: () => request.get('/admin/overview'),
  demoSales: () => request.get('/demo/sales')
}
