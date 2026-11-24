"""What the relay logs."""

import logging


def test_outbound_requests_are_not_logged():
    """httpx logged every push and release check at INFO: noise, and a timeline of pushes."""
    import app.main  # noqa: F401

    for name in ("httpx", "httpcore"):
        assert logging.getLogger(name).getEffectiveLevel() >= logging.WARNING
