# Cancel remediation focused PostgreSQL evidence

- UTC timestamp: `2026-09-12T08:39:08Z`
- HEAD: `d29cc8d727c7774409ca2e9baefb25b59f71a132`
- Base (`HEAD^`): `9e5c4b65d06d55214556258c485f866f614d0571`
- Pre-artifact intended path count: `40`
- Relevant diff hash: `4e4a3fac5036836fed2f3d79a8132b24a51a02ffefbd8e94880b2d27a5803a04`

The relevant diff hash is the SHA-256 of the sorted pre-artifact
`git status --porcelain=v1` path list paired with each path's
`git hash-object` content hash. The pre-artifact path set includes
`docs/acceptance-criteria.md`; this evidence file is intentionally excluded
from that manifest.

## Isolated gate

Disposable PostgreSQL identity:

- container: `remanence-cancel-remediation-pg`
- container ID: `2464738eec9fa7932b1e5340956e90cf14d4b0ecd075033a9ac8e8f3f08631d9`
- image: `postgres:16.13-bookworm`
- image ID: `sha256:472efd9a66f2b2f1a5aeb18b28de74332e6ef88c2b93a1a5d812fb6db67a5f60`
- database endpoint: loopback-only `127.0.0.1:55439`, mapped to container port `5432`
- test fixture: creates and drops random per-test databases inside this disposable instance
- production database: not used

Exact command:

```text
REMANENCE_TEST_DATABASE_URL='postgresql+psycopg://remanence@127.0.0.1:55439/postgres' uv run pytest -q tests/test_capsule_first_open_service.py tests/test_capsule_first_open_endpoint.py tests/test_capsule_revoke_service.py tests/test_capsule_revoke_endpoint.py tests/test_account_migration_offline.py tests/test_migrations.py
```

Result: exit code `0`; complete summary: **39 passed in 15.35s**.

Cleanup proof:

```text
sudo -n docker rm -f remanence-cancel-remediation-pg
remanence-cancel-remediation-pg
sudo -n docker ps -a --format '{{.Names}}' | rg -x 'remanence-cancel-remediation-pg'
<no matching container>
```

No commit, push, deploy, release, or production mutation was performed.
