"""Single-writer authority prototype; no transport or hardware implementation.

The lab uses a common monotonic clock across OS processes. Client source_ms and
source_valid are SIMULATED evidence, not proof of real joystick freshness.
Operator and target/primary evidence methods are trusted local inputs; they are
deliberately absent from the untrusted client packet protocol.
"""
import hashlib
import hmac
import json
import secrets
import threading


FIELDS = {"device", "boot", "epoch", "seq", "source_ms", "axes", "deadman", "source_valid"}


def signed_packet(key, **fields):
    body = json.dumps(fields, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return json.dumps({"body": fields, "mac": hmac.new(key, body.encode(), hashlib.sha256).hexdigest()},
                      separators=(",", ":")).encode()


class Arbiter:
    INPUT_MS = 150
    TARGET_MS = 1500
    PRIMARY_MS = 100
    LEASE_MS = 2000  # Bounded experiment; production lease renewal is not implemented.

    def __init__(self, device_keys, sink):
        if not device_keys or any(len(k) < 32 for k in device_keys.values()):
            raise ValueError("Per-device random keys of at least 32 bytes required")
        self._keys = dict(device_keys)
        self._sink = sink
        self._lock = threading.RLock()
        self.boot = secrets.token_hex(16)
        self.epoch = 0
        self.state = "READY"
        self.reason = "Explicit operator grant required"
        self.owner = None
        self._clock = -1
        self._target = -1
        self._primary = -1
        self._input = -1
        self._seq = -1
        self._sent = -1
        self._until = -1

    @staticmethod
    def _fresh(stamp, now, window):
        return type(stamp) is int and 0 <= stamp <= now and now - stamp <= window

    def _stop(self, reason):
        self.state, self.reason, self.owner = "STOPPED", reason, None
        self._target = self._primary = self._input = -1
        # On unconfirmed primary failure, stop sending. Do not blindly release.

    def _time(self, now):
        if type(now) is not int or now < self._clock or now < 0:
            self._stop("Clock regressed/invalid")
            return False
        self._clock = now
        return True

    def poll(self, now):
        with self._lock:
            if not self._time(now):
                return False
            if self.state == "ACTIVE" and (now >= self._until or
                    not self._fresh(self._input, now, self.INPUT_MS) or
                    not self._fresh(self._target, now, self.TARGET_MS)):
                self._stop("Lease, source input, or target expired")
            return self.state == "ACTIVE"

    def observe_target(self, now, matches):
        """Trusted lab validates the owned child/version before providing this."""
        with self._lock:
            self.poll(now)
            if not matches:
                self._stop("Target identity/link invalid")
            elif self.state != "STOPPED":
                self._target = now

    def observe_primary(self, observed_ms, now, valid):
        """Independent RC evidence only; overridden RC_CHANNELS is insufficient."""
        with self._lock:
            if self._time(now):
                self._primary = observed_ms if valid and self._fresh(observed_ms, now, self.PRIMARY_MS) else -1

    def acknowledge_stop(self, now):
        """Explicit local operator reset; it does not restore a lease or target."""
        with self._lock:
            if self._time(now) and self.state == "STOPPED":
                self.state, self.reason = "READY", "Fresh evidence and explicit grant required"
                return True
            return False

    def grant(self, device, now):
        with self._lock:
            self.poll(now)
            if (self.state != "READY" or device not in self._keys or
                    not self._fresh(self._target, now, self.TARGET_MS)):
                return None
            self.epoch += 1
            self.owner = device
            self.state = "ACTIVE"
            self.reason = "Await neutral first sample"
            self._seq = self._sent = -1
            self._input = now
            self._until = now + self.LEASE_MS
            return {"boot": self.boot, "epoch": self.epoch}

    def accept(self, packet, now):
        with self._lock:
            self.poll(now)
            if self.state != "ACTIVE":
                return "inactive"
            if not isinstance(packet, bytes) or len(packet) > 2048:
                return "malformed"
            try:
                # Reject duplicate keys rather than accepting ambiguous signed JSON.
                def unique(pairs):
                    result = {}
                    for k, v in pairs:
                        if k in result:
                            raise ValueError("duplicate key")
                        result[k] = v
                    return result
                wire = json.loads(packet, object_pairs_hook=unique)
                if set(wire) != {"body", "mac"} or not isinstance(wire["body"], dict):
                    return "malformed"
                body, mac = wire["body"], wire["mac"]
                if set(body) != FIELDS or not isinstance(mac, str) or not isinstance(body["device"], str):
                    return "malformed"
                key = self._keys.get(body["device"])
                if key is None:
                    return "unauthenticated"
                canonical = json.dumps(body, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
                if not hmac.compare_digest(mac, hmac.new(key, canonical, hashlib.sha256).hexdigest()):
                    return "unauthenticated"
            except (ValueError, TypeError, KeyError, UnicodeError, RecursionError):
                return "malformed"
            if body["device"] != self.owner:
                return "not-owner"
            if body["boot"] != self.boot or type(body["epoch"]) is not int or body["epoch"] != self.epoch:
                return "old-lease"
            if type(body["seq"]) is not int or body["seq"] <= self._seq:
                return "replay"
            axes = body["axes"]
            if (not isinstance(axes, list) or len(axes) != 4 or
                    any(type(v) is not int for v in axes) or
                    any(abs(axes[i]) > 1000 for i in (0, 1, 3)) or not 0 <= axes[2] <= 1000 or
                    body["deadman"] is not True or body["source_valid"] is not True or
                    not self._fresh(body["source_ms"], now, self.INPUT_MS) or
                    (self._seq >= 0 and body["source_ms"] <= self._input)):
                self._stop("Invalid/stale source or deadman released")
                return "stopped"
            if self._seq < 0 and (any(abs(axes[i]) > 50 for i in (0, 1, 3)) or axes[2] > 50):
                self._stop("First sample must be neutral and low throttle")
                return "stopped"
            self._seq, self._input = body["seq"], body["source_ms"]
            if self._sent >= 0 and now - self._sent < 50:
                return "rate-limited"
            try:
                # Only validated four-axis values leave the authority, never arbitrary MAVLink.
                self._sink("manual", tuple(axes))
            except Exception:
                self._stop("Sink failed")
                return "stopped"
            self._sent = now
            return "forwarded"

    def hand_back(self, now):
        with self._lock:
            if not self.poll(now):
                return False
            if not self._fresh(self._primary, now, self.PRIMARY_MS):
                self._stop("Primary not independently confirmed; no release sent")
                return False
            try:
                self._sink("release", None)
            except Exception:
                self._stop("Release sink failed")
                return False
            self.owner = None
            self.state, self.reason = "READY", "Released; no automatic regrant"
            self._input = self._primary = -1
            return True
