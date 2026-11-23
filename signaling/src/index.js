// Tesla-Waze relay: pairs one phone and one screen per room and forwards
// messages between them. Binary frames go phone -> screen; JSON control
// messages go both ways. The room id is a 128-bit random secret generated
// on the phone, so knowing it is what grants access.

import diagHtml from './diag.html';

const ROOM_RE = /^\/room\/([A-Za-z0-9_-]{16,64})$/;
const ROLES = ['phone', 'screen'];
const otherRole = (role) => (role === 'phone' ? 'screen' : 'phone');

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    // Short, easy-to-type address for the browser diagnostics page (second host besides GitHub Pages).
    if (url.pathname === '/d' || url.pathname === '/d/') {
      return new Response(diagHtml, { headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' } });
    }
    const m = url.pathname.match(ROOM_RE);
    if (!m) {
      return new Response('tesla-waze relay', { status: url.pathname === '/' ? 200 : 404 });
    }
    if (request.headers.get('Upgrade') !== 'websocket') {
      return new Response('expected websocket', { status: 426 });
    }
    if (!ROLES.includes(url.searchParams.get('role'))) {
      return new Response('bad role', { status: 400 });
    }
    const stub = env.ROOMS.get(env.ROOMS.idFromName(m[1]));
    return stub.fetch(request);
  },
};

export class Room {
  constructor(ctx) {
    this.ctx = ctx;
  }

  async fetch(request) {
    const role = new URL(request.url).searchParams.get('role');
    // Only one connection per role: a reconnecting phone replaces its stale socket.
    for (const old of this.ctx.getWebSockets(role)) {
      try { old.close(4000, 'replaced'); } catch {}
    }
    const { 0: client, 1: server } = new WebSocketPair();
    this.ctx.acceptWebSocket(server, [role]);

    const peers = this.ctx.getWebSockets(otherRole(role));
    server.send(JSON.stringify({ type: 'peer', present: peers.length > 0 }));
    this.broadcast(otherRole(role), JSON.stringify({ type: 'peer', present: true }));
    return new Response(null, { status: 101, webSocket: client });
  }

  webSocketMessage(ws, message) {
    const role = this.ctx.getTags(ws)[0];
    this.broadcast(otherRole(role), message);
  }

  webSocketClose(ws, code, reason) {
    this.left(ws);
    try { ws.close(code, reason); } catch {}
  }

  webSocketError(ws) {
    this.left(ws);
  }

  left(ws) {
    const role = this.ctx.getTags(ws)[0];
    const stillThere = this.ctx.getWebSockets(role).some((s) => s !== ws);
    if (!stillThere) this.broadcast(otherRole(role), JSON.stringify({ type: 'peer', present: false }));
  }

  broadcast(role, message) {
    for (const ws of this.ctx.getWebSockets(role)) {
      try { ws.send(message); } catch {}
    }
  }
}
