// 极简 SMTP sink：仅用于本地验证邮件推送链路（不做转发、不做认证）
// 用法：node docs/dev-smtp-sink.js 1025 D:\bi\.tmp\mail
const net = require('net')
const fs = require('fs')
const path = require('path')

const port = Number(process.argv[2] || 1025)
const outDir = process.argv[3] || path.join(process.cwd(), 'mail-out')

fs.mkdirSync(outDir, { recursive: true })

const server = net.createServer(socket => {
  let buffer = ''
  let inData = false
  let data = ''
  let rcpts = []

  socket.setEncoding('utf8')
  socket.write('220 localhost ESMTP rbi-sink\r\n')

  socket.on('data', chunk => {
    buffer += chunk
    let idx
    while ((idx = buffer.indexOf('\r\n')) >= 0) {
      const line = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)

      if (inData) {
        if (line === '.') {
          inData = false
          const stamp = new Date().toISOString().replace(/[:.]/g, '-')
          const file = path.join(outDir, `mail-${stamp}.eml`)
          fs.writeFileSync(file, `X-Rcpt: ${rcpts.join(', ')}\r\n${data}`)
          console.log(`[sink] 收到邮件 ${file} (${data.length} bytes, rcpt=${rcpts.join(',')})`)
          data = ''
          rcpts = []
          socket.write('250 OK queued\r\n')
        } else {
          data += (line.startsWith('..') ? line.slice(1) : line) + '\r\n'
        }
        continue
      }

      const cmd = line.toUpperCase()
      if (cmd.startsWith('EHLO') || cmd.startsWith('HELO')) {
        socket.write('250-localhost\r\n250 8BITMIME\r\n')
      } else if (cmd.startsWith('MAIL FROM')) {
        socket.write('250 OK\r\n')
      } else if (cmd.startsWith('RCPT TO')) {
        const m = line.match(/<([^>]*)>/)
        if (m) rcpts.push(m[1])
        socket.write('250 OK\r\n')
      } else if (cmd === 'DATA') {
        inData = true
        socket.write('354 End data with <CR><LF>.<CR><LF>\r\n')
      } else if (cmd === 'QUIT') {
        socket.write('221 Bye\r\n')
        socket.end()
      } else if (cmd === 'RSET') {
        data = ''
        rcpts = []
        socket.write('250 OK\r\n')
      } else {
        socket.write('250 OK\r\n')
      }
    }
  })

  socket.on('error', () => {})
})

server.listen(port, '127.0.0.1', () => {
  console.log(`[sink] SMTP sink 监听 127.0.0.1:${port}，邮件写入 ${outDir}`)
})
