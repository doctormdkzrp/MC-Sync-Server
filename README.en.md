# mc_sync_server

Sync a Minecraft modpack across machines **and** publish it to a live server — with a web console.

One Node service does two jobs:

1. **Sync API (protocol 2)** — hands a manifest to client agents, accepts their "union" contributions
   (key bindings, journeymap), so every machine converges on one modpack.
2. **Web console** ("发布台") — an authenticated page to manage mods, edit configs online, keep a
   recycle bin / per-file version history, push everything to the running MC server instance and
   restart it.

The server is the single source of truth; clients copy from it.

## Status

Works end-to-end: sync API, web console, client agent, and the self-updating shell — verified against a real 250-mod GTNH instance. See [`docs/PLAN.md`](docs/PLAN.md) for milestone state (it is the honest one).

## Layout

```
packages/server           Node + TypeScript + Fastify — sync API + console API + static file server
packages/web              Vue 3 + Vite console (built into packages/web/dist, served by the server)
packages/agent            Java agent: aligns your instance before launch, submits on exit
packages/agent-bootstrap  Tiny never-changing javaagent shell that self-updates the agent
deploy/                   Dockerfile, docker-compose, systemd unit, config example
docs/                     ARCHITECTURE.md (the contract), ADMIN_API.md, SPEC.md, PLAN.md
```

## Quick start (dev)

```bash
cd packages/server
npm install
npm test                 # unit + CLI integration tests
npm run build
node dist/server.js --config /path/to/config.json
```

Config example: [`deploy/config.example.json`](deploy/config.example.json). Tokens are read from
files (`submit_token_file`, `admin.token_file`) so they never sit in the config or in git.

## Console

Open `http://<host>:<port>/` in a browser, log in with the admin token. Four pages:
Mods / Config / Other dirs / Recycle bin.

## Client agent

See [`packages/agent/README.md`](packages/agent/README.md) and
[`packages/agent-bootstrap/README.md`](packages/agent-bootstrap/README.md).

## Deploy

See [`deploy/`](deploy/) — Docker or plain systemd. Requires Node >= 20.

## License

GPL-3.0 — see [`LICENSE`](LICENSE).

This project descends from [ServerSync](https://github.com/superzanti/ServerSync) (GPL-3.0): the client
agent's package name and the sync protocol's shape come from there. Thanks to its authors.
