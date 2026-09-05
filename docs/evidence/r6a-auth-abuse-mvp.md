# R6a auth-abuse MVP validation

Date: 2026-09-05

This change implements only the login timing-parity MVP. Early invalid login
paths for a missing user, disabled user, or missing credential perform one
normal `PasswordService.verify_password` call using the fixed server-side
Argon2id PHC string. The verification result is discarded. The stored hash is
never derived from request input, and no wall-clock timing assertion is used.

The following remains intentionally unimplemented: PostgreSQL counters and
rate limits, account lockouts, registration response changes, proxy
configuration, DDoS defenses, R8, Android, recognition, merge, push, and
release work. Postmark ports 55432 and 8000 were not used or changed.

## Validation selection

All PostgreSQL-backed runs were serialized and used the isolated database
endpoint on `127.0.0.1:55435`. No competing pytest process was present before
the full run.

Focused selection:

```text
tests/test_password_service.py tests/test_login_endpoint.py tests/test_r6a_base_red.py
-k 'not test_login_and_register_have_an_executable_rate_limit'
```

Result: **20 passed, 1 deselected**. The deselected test is the separate
rate-limit probe only.

Full in-scope server selection:

```text
uv run --locked pytest -q -W error -k 'not test_login_and_register_have_an_executable_rate_limit'
```

Result: **702 passed, 1 deselected** in 114.44 seconds. This is the complete
703-test server collection minus the one intentionally unimplemented
rate-limit probe; it is not a claim that the unfiltered collection is green.

Separate retained base-red probe:

```text
tests/test_r6a_base_red.py::test_login_and_register_have_an_executable_rate_limit
```

Result: **1 failed**, as expected: login responses were `[401, 401, 401]`
and registration responses were `[201, 201, 201]`, with no `429`. The existing
timing base-red probe is included in the green selections and passes.
