"""WebSocket connection manager for real-time message delivery.

Each connected client registers with their user_id. When a new message
arrives for a user, the server pushes a notification through the WebSocket.

Privacy note: WebSocket messages contain only a generic delivery signal —
never message content or sender identity. The client then fetches and
decrypts the envelope via the REST API.
"""

import asyncio
import json
import logging
import time
from collections import defaultdict, OrderedDict
from dataclasses import dataclass, field
from typing import Awaitable, Callable, Dict, Optional

from fastapi import WebSocket

from app.services.metrics import metrics

logger = logging.getLogger(__name__)


MAX_WS_CONNECTIONS_PER_USER = 5

# A socket can take a call offer without anybody reading it: Android pauses ("freezes")
# background apps that have no foreground service, and their sockets stay open. The
# relay then counted the offer as delivered and sent no push, so the call was lost. An
# offer whose app doesn't answer (ringing, busy, reject, hangup) within this many
# seconds is presumed unread: see park_unconfirmed_offer.
OFFER_CONFIRM_SECONDS = 3.0
_CONFIRMING_SIGNALS = frozenset({"call_ringing", "call_busy", "call_reject", "call_hangup"})
_UNCONFIRMED_MAX_AGE = 60.0  # seconds; a safety net, entries normally go within seconds
# While waiting for an app to show it reads its socket, how often to check whether that
# socket died meanwhile. A dead phone's kernel answers the relay's send with a reset, so
# a socket that only looked open is usually gone within a round trip.
SOCKET_CHECK_INTERVAL = 0.25


@dataclass
class _UnconfirmedOffer:
    """A call offer that went to open sockets whose app hasn't answered yet."""

    caller_id: str
    sockets: list
    # (signal_type, payload) as relayed, in order: the offer, then the caller's candidates.
    signals: list = field(default_factory=list)
    created: float = field(default_factory=time.monotonic)


_PENDING_SIGNAL_TTL = 30  # seconds — signals older than this are discarded
_MAX_PENDING_SIGNAL_USERS = 10_000  # global cap on distinct recipient entries
# Global cap on buffered signal bytes. Without it, signals to many (possibly
# non-existent) recipients could hold ~10k users x 20 signals x ~73 KB in memory.
_MAX_PENDING_SIGNAL_BYTES = 32 * 1024 * 1024
_MAX_PENDING_SIGNALS_PER_USER = 20
# Per-sender share of the buffer (bytes buffered within one TTL). Without it a
# few accounts could keep the global budget full, so every offline call offer
# (the one signal that must be buffered) would be dropped. A real call buffers
# an offer (~10 KB) plus a few small signals.
_MAX_PENDING_SIGNAL_BYTES_PER_SENDER = 256 * 1024
_SENDER_ACCOUNTING_HIGH_WATER = 2000
_ICE_SIGNAL = "call_ice_candidate"
# A peer that stops reading must not block senders (or the manager) forever.
_SEND_TIMEOUT = 5.0  # seconds


class WebSocketManager:
    """Manages active WebSocket connections per user."""

    def __init__(self) -> None:
        # user_id → insertion-ordered dict of active WebSocket connections
        # Using OrderedDict (instead of set) so eviction removes the oldest.
        self._connections: Dict[str, OrderedDict[WebSocket, None]] = defaultdict(OrderedDict)
        self._lock = asyncio.Lock()
        # Pending call signals for users who are not currently connected.
        # user_id → list of (timestamp, payload_json) tuples.
        self._pending_call_signals: Dict[str, list[tuple[float, str]]] = {}
        self._pending_signal_bytes = 0
        # sender_id → (timestamp, bytes) of signals it buffered within the TTL.
        self._buffered_by_sender: Dict[str, list[tuple[float, int]]] = {}
        # (callee_id, call_id) → an offer delivered to sockets whose app hasn't answered.
        self._unconfirmed: Dict[tuple[str, str], _UnconfirmedOffer] = {}
        self._tasks: set[asyncio.Task] = set()

    def spawn(self, job: Awaitable[None]) -> None:
        """Run a background job, keeping a reference until it ends; cancelled on shutdown."""
        task = asyncio.ensure_future(job)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    def offer_sockets_open(self, callee_id: str, call_id: str) -> bool:
        """True while an unanswered offer still has an open socket that took it."""
        entry = self._unconfirmed.get((callee_id, call_id))
        if entry is None:
            return False
        open_sockets = self._connections.get(callee_id, {})
        return any(ws in open_sockets for ws in entry.sockets)

    async def park_unconfirmed_offer(self, callee_id: str, call_id: str) -> Optional[str]:
        """The callee's app never answered the offer: nobody reads the sockets that
        took it. Close them, keep the call's signals for the app's next connection, and
        return the caller, so the app can be woken by push. None when the app answered,
        or the call ended, meanwhile."""
        entry = self._unconfirmed.pop((callee_id, call_id), None)
        if entry is None:
            return None
        logger.info("Call offer to %s unanswered: closing its unread socket, keeping the call", callee_id[:8])
        for ws in entry.sockets:
            await self.disconnect(callee_id, ws)
            await self._close(ws, code=4011, reason="Not reading")
        for signal_type, payload in entry.signals:
            await self._store_pending_signal(callee_id, payload, signal_type, entry.caller_id)
        return entry.caller_id

    async def connect(self, user_id: str, websocket: WebSocket) -> None:
        await websocket.accept()
        await self.register(user_id, websocket)

    async def register(self, user_id: str, websocket: WebSocket) -> None:
        """Register an already-accepted WebSocket, enforcing per-user cap.

        NOTE: Does NOT flush pending signals — the caller must send auth_ok
        first, then call flush_pending_signals(). Otherwise the client
        receives a call signal where it expects auth_ok and disconnects.
        """
        evicted = None
        async with self._lock:
            if len(self._connections[user_id]) >= MAX_WS_CONNECTIONS_PER_USER:
                # Evict the oldest (first-inserted) connection
                evicted, _ = self._connections[user_id].popitem(last=False)
            self._connections[user_id][websocket] = None
        # Close OUTSIDE the lock: closing a socket whose peer stopped reading can
        # block indefinitely, which would stall every user's delivery.
        if evicted is not None:
            await self._close(evicted, code=4008, reason="Too many connections")
        metrics.active_ws_connections = self.connection_count()
        logger.info("WebSocket connected: %s", user_id[:8])

    async def disconnect(self, user_id: str, websocket: WebSocket) -> None:
        async with self._lock:
            conns = self._connections.get(user_id)
            if conns is not None:
                conns.pop(websocket, None)
                if not conns:
                    del self._connections[user_id]
        metrics.active_ws_connections = self.connection_count()
        logger.info("WebSocket disconnected: %s", user_id[:8])

    async def disconnect_user(self, user_id: str, reason: str) -> None:
        """Close every connection of a user and drop their buffered signals.

        Called when the user's keys change or the identity is deleted: sockets
        authenticated with the old keys must not stay alive.
        """
        async with self._lock:
            sockets = list(self._connections.pop(user_id, {}))
            dropped = self._pending_call_signals.pop(user_id, [])
            self._pending_signal_bytes -= sum(len(p) for _, p in dropped)
        for ws in sockets:
            await self._close(ws, code=4001, reason=reason)
        metrics.active_ws_connections = self.connection_count()

    def connection_count(self) -> int:
        return sum(len(c) for c in self._connections.values())

    @staticmethod
    async def _close(ws: WebSocket, code: int, reason: str) -> None:
        try:
            await asyncio.wait_for(ws.close(code=code, reason=reason), timeout=_SEND_TIMEOUT)
        except Exception:
            pass

    async def _send(self, user_id: str, ws: WebSocket, payload: str) -> bool:
        """Send with a timeout; a socket that can't take the message is dropped."""
        try:
            await asyncio.wait_for(ws.send_text(payload), timeout=_SEND_TIMEOUT)
            return True
        except Exception:
            await self.disconnect(user_id, ws)
            await self._close(ws, code=1011, reason="Send failed")
            return False

    def is_online(self, user_id: str) -> bool:
        # Use get() with explicit default to avoid defaultdict creating empty entries
        return bool(self._connections.get(user_id, None))

    async def notify_new_message(
        self,
        recipient_id: str,
        sender_id: str,
    ) -> bool:
        """Push a new-message signal to the recipient if they're connected.

        Returns True if at least one WebSocket was notified.
        """
        return await self.notify(recipient_id, "new_message")

    async def notify(self, recipient_id: str, signal_type: str) -> bool:
        """Push a content-free ``{"type": signal_type}`` signal to a connected user."""
        async with self._lock:
            sockets = list(self._connections.get(recipient_id, {}))
        if not sockets:
            return False
        payload = json.dumps({"type": signal_type})
        results = await asyncio.gather(*(self._send(recipient_id, ws, payload) for ws in sockets))
        return any(results)

    async def broadcast(self, payload: str) -> int:
        """Send [payload] to every open socket; returns how many took it."""
        async with self._lock:
            targets = [(user_id, ws) for user_id, sockets in self._connections.items() for ws in sockets]
        results = await asyncio.gather(*(self._send(user_id, ws, payload) for user_id, ws in targets))
        return sum(results)

    async def shutdown(self) -> None:
        """Gracefully close all connections (called on server shutdown)."""
        async with self._lock:
            all_sockets = [
                (uid, ws)
                for uid, conns in self._connections.items()
                for ws in conns
            ]
            self._connections.clear()

        await asyncio.gather(*(self._close(ws, code=1012, reason="Server shutting down") for _, ws in all_sockets))
        for task in list(self._tasks):
            task.cancel()
        logger.info("All WebSocket connections closed for shutdown")

    async def flush_pending_signals(self, user_id: str, websocket: WebSocket) -> None:
        """Deliver pending call signals to a newly connected user."""
        now = time.monotonic()
        async with self._lock:
            signals = self._pending_call_signals.pop(user_id, [])
            self._pending_signal_bytes -= sum(len(p) for _, p in signals)
        for ts, payload in signals:
            if now - ts > _PENDING_SIGNAL_TTL:
                continue  # expired
            if not await self._send(user_id, websocket, payload):
                break
            logger.info("Delivered pending call signal to %s", user_id[:8])

    def _prune_expired_signals(self, now: float) -> None:
        """Drop expired buffered signals for all recipients. Caller holds the lock."""
        for uid in list(self._pending_call_signals):
            sigs = self._pending_call_signals[uid]
            kept = [(ts, p) for ts, p in sigs if now - ts <= _PENDING_SIGNAL_TTL]
            self._pending_signal_bytes -= sum(len(p) for _, p in sigs) - sum(len(p) for _, p in kept)
            if kept:
                self._pending_call_signals[uid] = kept
            else:
                del self._pending_call_signals[uid]

    def _charge_sender(self, sender_id: str, size: int, now: float) -> bool:
        """Count ``size`` buffered bytes against ``sender_id``; False if over its share.
        Caller holds the lock."""
        if len(self._buffered_by_sender) > _SENDER_ACCOUNTING_HIGH_WATER:
            for sid in [s for s, e in self._buffered_by_sender.items()
                        if not e or now - e[-1][0] > _PENDING_SIGNAL_TTL]:
                del self._buffered_by_sender[sid]
        entries = self._buffered_by_sender.setdefault(sender_id, [])
        entries[:] = [(ts, n) for ts, n in entries if now - ts <= _PENDING_SIGNAL_TTL]
        if sum(n for _, n in entries) + size > _MAX_PENDING_SIGNAL_BYTES_PER_SENDER:
            return False
        entries.append((now, size))
        return True

    async def _store_pending_signal(
        self, recipient_id: str, payload: str, signal_type: str = "", sender_id: str = "",
    ) -> None:
        """Buffer a call signal for a user who is currently offline.

        When the per-user buffer is full, ICE candidates give way to control
        signals: a dropped hangup would leave the callee ringing for a call
        that is already over, a dropped offer loses the call entirely.
        """
        now = time.monotonic()
        async with self._lock:
            if sender_id and not self._charge_sender(sender_id, len(payload), now):
                logger.warning("Signal buffer share used up by %s; dropping %s", sender_id[:8], signal_type)
                return
            over_users = (
                recipient_id not in self._pending_call_signals
                and len(self._pending_call_signals) >= _MAX_PENDING_SIGNAL_USERS
            )
            over_bytes = self._pending_signal_bytes + len(payload) > _MAX_PENDING_SIGNAL_BYTES
            if over_users or over_bytes:
                self._prune_expired_signals(now)
                if (
                    recipient_id not in self._pending_call_signals
                    and len(self._pending_call_signals) >= _MAX_PENDING_SIGNAL_USERS
                ) or self._pending_signal_bytes + len(payload) > _MAX_PENDING_SIGNAL_BYTES:
                    return  # Still full after pruning — drop the signal

            pending = self._pending_call_signals.setdefault(recipient_id, [])
            # Evict expired entries
            kept = [(ts, p) for ts, p in pending if now - ts <= _PENDING_SIGNAL_TTL]
            self._pending_signal_bytes -= sum(len(p) for _, p in pending) - sum(len(p) for _, p in kept)
            pending[:] = kept
            # Cap at a reasonable number to prevent abuse
            if len(pending) >= _MAX_PENDING_SIGNALS_PER_USER and signal_type != _ICE_SIGNAL:
                for i, (_, p) in enumerate(pending):
                    if _signal_type_of(p) == _ICE_SIGNAL:
                        self._pending_signal_bytes -= len(p)
                        del pending[i]
                        break
            if len(pending) < _MAX_PENDING_SIGNALS_PER_USER:
                pending.append((now, payload))
                self._pending_signal_bytes += len(payload)

    async def relay_call_signal(
        self,
        sender_id: str,
        recipient_id: str,
        signal_type: str,
        call_id: str,
        encrypted_payload: str,
        signature: str = "",
        pqc_signature: str = "",
        kem_ciphertext: str = "",
        recipient_exists: Optional[Callable[[], Awaitable[bool]]] = None,
        expect_answer: bool = False,
    ) -> bool:
        """Relay an opaque encrypted call signal to the recipient.

        Returns True if at least one WebSocket received the signal. An offline
        recipient's signals are buffered only if ``recipient_exists`` (when
        given) confirms the account: signals to made-up ids would only take
        buffer space from real calls.

        ``expect_answer`` (a call offer): remember the offer, and the caller's signals
        that follow, until the callee's app answers; the caller of this method then
        calls park_unconfirmed_offer after OFFER_CONFIRM_SECONDS.
        """
        # The callee's app answered (it reads its socket), or the call is over.
        if signal_type in _CONFIRMING_SIGNALS:
            self._unconfirmed.pop((sender_id, call_id), None)
        if signal_type == "call_hangup":
            self._unconfirmed.pop((recipient_id, call_id), None)

        async with self._lock:
            sockets = list(self._connections.get(recipient_id, {}))

        msg: dict[str, str] = {
            "type": signal_type,
            "sender_id": sender_id,
            "call_id": call_id,
            "encrypted_payload": encrypted_payload,
            "signature": signature,
        }
        if pqc_signature:
            msg["pqc_signature"] = pqc_signature
        if kem_ciphertext:
            msg["kem_ciphertext"] = kem_ciphertext
        payload = json.dumps(msg)

        if not sockets:
            # Recipient offline — buffer the signal for delivery when they connect
            if recipient_exists is not None and not await recipient_exists():
                return False
            await self._store_pending_signal(recipient_id, payload, signal_type, sender_id)
            return False

        results = await asyncio.gather(*(self._send(recipient_id, ws, payload) for ws in sockets))
        delivered = any(results)
        key = (recipient_id, call_id)
        if delivered and expect_answer and signal_type == "call_offer":
            now = time.monotonic()
            for stale in [k for k, e in self._unconfirmed.items() if now - e.created > _UNCONFIRMED_MAX_AGE]:
                del self._unconfirmed[stale]
            self._unconfirmed[key] = _UnconfirmedOffer(
                caller_id=sender_id,
                sockets=[ws for ws, ok in zip(sockets, results) if ok],
                signals=[(signal_type, payload)],
            )
        elif (entry := self._unconfirmed.get(key)) is not None and entry.caller_id == sender_id:
            entry.signals.append((signal_type, payload))  # e.g. the caller's ICE candidates
        return delivered


def _signal_type_of(payload: str) -> str:
    try:
        return json.loads(payload).get("type", "")
    except (ValueError, AttributeError):
        return ""


# Singleton instance
ws_manager = WebSocketManager()
