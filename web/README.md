# Remanence web

Separate browser client for the existing Remanence account, recovery, and
capsule contracts. It is not a second product and it does not decrypt on the server.

Install and checks run with Node 22.22.1. On the build VM the binary is
`/home/msi-workstation/remanence-web-t213-20261017/tools/node/bin`.

```sh
cd web
npm ci
npm test
npm run typecheck
npm run build
npm run dev
```

`npm run dev` listens on `127.0.0.1` only. Public settings belong in
`env.example`; do not commit `.env` or `.env.local`.

See `docs/web/IMPLEMENTATION.md` for the contract map and `docs/web/PROGRESS.md`
for what is actually verified.
