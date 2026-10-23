import pytest

from remanence.api.rate_limit import reset_rate_limiters


@pytest.fixture(autouse=True)
def _fresh_rate_limiters():
    reset_rate_limiters()
    yield
