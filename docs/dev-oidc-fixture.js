// 本地 OIDC 夹具：模拟 IdP 的 /authorize、/token、/userinfo，用于验证 SSO 链路
// 用法：node docs/dev-oidc-fixture.js 18100
const http = require('http')

const port = Number(process.argv[2] || 18100)
const CODE = 'code-abc-123'
const TOKEN = 'at-123456'

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${port}`)

  if (url.pathname === '/authorize') {
    const redirect = url.searchParams.get('redirect_uri')
    const state = url.searchParams.get('state')
    if (!redirect) {
      res.statusCode = 400
      return res.end('missing redirect_uri')
    }
    const sep = redirect.includes('?') ? '&' : '?'
    res.statusCode = 302
    res.setHeader('Location', `${redirect}${sep}code=${CODE}&state=${state}`)
    console.log(`[oidc] authorize -> ${res.getHeader('Location')}`)
    return res.end()
  }

  if (url.pathname === '/token') {
    let body = ''
    req.on('data', c => (body += c))
    req.on('end', () => {
      console.log(`[oidc] token request: ${body}`)
      res.setHeader('Content-Type', 'application/json')
      res.end(JSON.stringify({ access_token: TOKEN, token_type: 'Bearer', expires_in: 3600 }))
    })
    return
  }

  if (url.pathname === '/userinfo') {
    const auth = req.headers['authorization'] || ''
    if (!auth.includes(TOKEN)) {
      res.statusCode = 401
      res.setHeader('Content-Type', 'application/json')
      return res.end(JSON.stringify({ error: 'invalid_token' }))
    }
    res.setHeader('Content-Type', 'application/json')
    res.end(JSON.stringify({
      sub: 'u-001',
      preferred_username: 'analyst',
      name: '张分析',
      email: 'analyst@example.com'
    }))
    return
  }

  res.statusCode = 404
  res.end('not found')
})

server.listen(port, '127.0.0.1', () => {
  console.log(`[oidc] OIDC 夹具监听 http://127.0.0.1:${port}`)
})
