import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.request.use(config => {
  const token = localStorage.getItem('rbi_token')
  if (token) config.headers.Authorization = 'Bearer ' + token
  return config
})

request.interceptors.response.use(
  res => {
    const data = res.data
    if (data && data.code !== undefined && data.code !== 0) {
      ElMessage.error(data.msg || '请求失败')
      return Promise.reject(data)
    }
    return data
  },
  err => {
    ElMessage.error(err.response?.data?.msg || err.message || '网络错误')
    return Promise.reject(err)
  }
)

export default request

export const api = {
  // 认证
  login: (data) => request.post('/auth/login', data),
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
  executeSql: (data) => request.post('/sql/execute', data),
  // 报表
  reports: () => request.get('/reports'),
  report: (id) => request.get('/reports/' + id),
  saveReport: (data) => request.post('/reports', data),
  delReport: (id) => request.delete('/reports/' + id),
  renderReport: (id, params) => request.post('/reports/' + id + '/render', params || {}),
  previewReport: (data) => request.post('/reports/preview', data),
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
  formData: (id) => request.post('/forms/' + id + '/submit', { records: [] }).catch(() => null),
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
  schedules: () => request.get('/admin/schedules'),
  saveSchedule: (s) => request.post('/admin/schedules', s),
  delSchedule: (id) => request.delete('/admin/schedules/' + id),
  logs: () => request.get('/admin/logs'),
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
