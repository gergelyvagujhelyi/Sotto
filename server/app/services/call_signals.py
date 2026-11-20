"""Call signal types the relay forwards between phones (REST and WebSocket alike)."""

ALLOWED_SIGNAL_TYPES = frozenset({
    "call_offer", "call_answer", "call_ice_candidate",
    "call_hangup", "call_busy", "call_reject", "call_ringing",
    # Mid-call ICE restart (renegotiation after a network change). Older apps ignore
    # signal types they don't know, so forwarding it to them is harmless.
    "call_ice_restart",
})
