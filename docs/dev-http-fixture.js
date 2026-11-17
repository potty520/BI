// 本地 HTTP 夹具：用于验证 API 数据集（取值、分页、白名单）
// 用法：node docs/dev-http-fixture.js 18099
const http = require('http')

const port = Number(process.argv[2] || 18099)

const sales = [
  { region: '华北', province: '北京', amount: 12500, qty: 5 },
  { region: '华东', province: '上海', amount: 9800, qty: 4 },
  { region: '华南', province: '广东', amount: 17200, qty: 7 }
]

// 分页夹具：共 250 条，按 page/size 切页
const ALL = Array.from({ length: 250 }, (_, i) => ({
  id: i + 1,
  region: ['华北', '华东', '华南', '西北', '西南'][i % 5],
  amount: 1000 + i
}))

function pageOf(searchParams) {
  const page = Number(searchParams.get('page') || 1)
  const size = Number(searchParams.get('size') || 100)
  const from = (page - 1) * size
  return { rows: ALL.slice(from, from + size), size }
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${port}`)
  res.setHeader('Content-Type', 'application/json; charset=utf-8')

  if (url.pathname === '/sales') {
    res.end(JSON.stringify({ code: 0, data: sales }))
  } else if (url.pathname === '/flat') {
    res.end(JSON.stringify(sales))
  } else if (url.pathname === '/paged') {
    const { rows, size } = pageOf(url.searchParams)
    console.log(`[fixture] /paged page=${url.searchParams.get('page')} size=${size} -> ${rows.length} 行`)
    res.end(JSON.stringify({ code: 0, data: rows, total: ALL.length }))
  } else if (url.pathname === '/health') {
    res.end(JSON.stringify({ ok: true }))
  } else {
    res.statusCode = 404
    res.end(JSON.stringify({ error: 'not found' }))
  }
})

server.listen(port, '127.0.0.1', () => {
  console.log(`[fixture] HTTP 夹具监听 http://127.0.0.1:${port}（/sales /flat /paged）`)
})
