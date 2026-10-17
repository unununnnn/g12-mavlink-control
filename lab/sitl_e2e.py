"""Owns a stock, disarmed Copter 4.7.1 child inside a loopback-only Linux netns.

There is deliberately no option to connect to an existing server or a device.
Only SIM_RC_FAIL may be written. No arm, mode, mission, or heartbeat is sent.
"""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import secrets
import socket
import struct
import subprocess
import tempfile
import threading
import time
from datetime import datetime, timezone

os.environ["MAVLINK20"] = "1"
from pymavlink import mavutil
from lab.arbiter import Arbiter, signed_packet

REVISION = "dbe792162d06cab66c3475fd5556bf7a120f119e"
ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def free_port(kind):
    with socket.socket(socket.AF_INET, kind) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


class Experiment:
    def __init__(self, source, output):
        self.source, self.output = source, output
        self.report = {"started_utc": datetime.now(timezone.utc).isoformat(), "cases": [],
                       "scope": "disarmed stock SITL; no physical G12/GR00/X9 verification",
                       "pymavlink": importlib.metadata.version("pymavlink")}
        self.primary = [1450, 1550, 1000, 1500, 1100, 1000, 1000, 1000] + [1500] * 8
        self.stop_rc = threading.Event()
        self.child = None
        self.links = []
        self.last_heartbeat = None
        self.status_text = []

    def record(self, name, **details):
        self.report["cases"].append(dict(name=name, result="PASS", **details))
        print("PASS", name, json.dumps(details), flush=True)

    def start(self):
        require(os.name == "posix" and hasattr(socket, "if_nameindex"), "Linux network namespace required")
        require([name for _, name in socket.if_nameindex()] == ["lo"], "Refuse network access: interfaces other than lo exist")
        require(os.readlink("/proc/self/ns/net") != os.readlink("/proc/1/ns/net"), "Run in a separate network namespace")
        require(subprocess.check_output(["git", "-C", str(self.source), "rev-parse", "HEAD"], text=True).strip() == REVISION,
                "Wrong source revision")
        require(not subprocess.check_output(["git", "-C", str(self.source), "status", "--porcelain", "--untracked-files=no"], text=True),
                "Tracked source/submodules must be unchanged")
        binary = self.source / "build/sitl/bin/arducopter"
        provenance = json.loads((self.source / "build/g12-provenance.json").read_text())
        require(provenance["revision"] == REVISION and provenance["binary_sha256"] == hashlib.sha256(binary.read_bytes()).hexdigest(),
                "Binary differs from the recorded build")
        self.report["build"] = provenance
        self.output.mkdir(parents=True, exist_ok=True)
        self.work = tempfile.TemporaryDirectory(prefix="g12-owned-sitl-")
        self.log = (self.output / "sitl.log").open("w")
        # Simulation calibration only. Safety/failsafe/fence values are left at stock defaults.
        defaults = "FRAME_CLASS 1\nSERIAL0_PROTOCOL 2\nSERIAL1_PROTOCOL 2\n"
        for channel in range(1, 5):
            defaults += f"RC{channel}_MIN 1000\nRC{channel}_MAX 2000\nRC{channel}_TRIM 1500\n"
        defaults_path = Path(self.work.name) / "lab.parm"
        defaults_path.write_text(defaults)
        self.report["initial_defaults"] = defaults
        ports = [free_port(socket.SOCK_STREAM) for _ in range(2)]
        for i, port in enumerate(ports):
            self.links.append(mavutil.mavlink_connection(f"tcpin:127.0.0.1:{port}",
                              source_system=255, source_component=190 + i))
        self.link, self.peer = self.links
        self.rc_port = free_port(socket.SOCK_DGRAM)
        command = [str(binary), "--model", "+", "--speedup", "1", "--home", "35.0,139.0,10,0",
                   "--defaults", str(defaults_path), "--rc-in-port", str(self.rc_port),
                   "--serial0", f"tcpclient:127.0.0.1:{ports[0]}",
                   "--serial1", f"tcpclient:127.0.0.1:{ports[1]}"]
        for i in range(2, 10):
            command += [f"--serial{i}", "none"]
        self.report["command"] = command
        self.child = subprocess.Popen(command, cwd=self.work.name, stdout=self.log, stderr=subprocess.STDOUT)
        self.rc_thread = threading.Thread(target=self.rc_input, daemon=True)
        self.rc_thread.start()
        self.wait_for(lambda m: m.get_type() == "HEARTBEAT", 30)
        self.peer.recv_match(blocking=True, timeout=3)
        for message_id, interval in ((65, 50000), (1, 100000)):
            self.link.mav.command_long_send(1, 1, mavutil.mavlink.MAV_CMD_SET_MESSAGE_INTERVAL, 0,
                                            message_id, interval, 0, 0, 0, 0, 0)
        self.link.mav.command_long_send(1, 1, mavutil.mavlink.MAV_CMD_REQUEST_MESSAGE, 0, 148, 0, 0, 0, 0, 0, 0)
        version = self.wait_for(lambda m: m.get_type() == "AUTOPILOT_VERSION", 5)
        require(version.flight_sw_version == 0x040701FF, "SITL did not report official Copter 4.7.1")
        self.report["autopilot_version"] = version.to_dict()
        self.safety = {name: self.parameter(name) for name in
                       ("ARMING_CHECK", "FS_THR_ENABLE", "FS_GCS_ENABLE", "FS_OPTIONS", "FENCE_ENABLE", "FENCE_ACTION",
                        "RC_OVERRIDE_TIME", "RC_FS_TIMEOUT", "RC_OPTIONS", "MAV_GCS_SYSID", "MAV_GCS_SYSID_HI", "SIM_RC_FAIL")}
        require(self.safety["RC_OVERRIDE_TIME"] == 3.0 and self.safety["FS_THR_ENABLE"] != 0,
                "Unexpected stock override timeout/failsafe")
        self.report["safety_before"] = self.safety
        self.wait_rc(self.primary[:4])
        self.record("owned_stock_identity_disarmed", revision=REVISION, flight_sw_version=version.flight_sw_version)

    def rc_input(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sender:
            while not self.stop_rc.wait(0.02):
                sender.sendto(struct.pack("<16H", *self.primary), ("127.0.0.1", self.rc_port))

    def receive(self, timeout=0.05):
        require(self.child.poll() is None, "Owned SITL child exited; inspect sitl.log")
        msg = self.link.recv_match(blocking=True, timeout=timeout)
        if msg and msg.get_type() != "BAD_DATA":
            require(msg.get_srcSystem() == 1 and msg.get_srcComponent() == 1, "Unexpected MAVLink source")
            if msg.get_type() == "HEARTBEAT":
                require(not msg.base_mode & mavutil.mavlink.MAV_MODE_FLAG_SAFETY_ARMED, "SITL unexpectedly armed")
                require(msg.autopilot == 3 and msg.type == 2, "Wrong autopilot/type")
                self.last_heartbeat = time.monotonic()
            if msg.get_type() == "STATUSTEXT":
                self.status_text.append(str(msg.text))
        return msg

    def wait_for(self, predicate, timeout=5, action=None):
        end, last_action = time.monotonic() + timeout, 0.0
        while time.monotonic() < end:
            if action and time.monotonic() - last_action >= 0.05:
                action()
                last_action = time.monotonic()
            msg = self.receive()
            if msg and predicate(msg):
                return msg
        raise AssertionError("Timed out waiting for expected telemetry")

    def wait_rc(self, expected, timeout=5, action=None):
        return self.wait_for(lambda m: m.get_type() == "RC_CHANNELS" and
                             [getattr(m, f"chan{i+1}_raw") for i in range(len(expected))] == expected,
                             timeout, action)

    def observe(self, seconds, action=None):
        messages, last_action = [], 0.0
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if action and time.monotonic() - last_action >= 0.05:
                action()
                last_action = time.monotonic()
            msg = self.receive()
            if msg:
                messages.append(msg)
        return messages

    def parameter(self, name, value=None):
        if value is not None:
            require(name == "SIM_RC_FAIL" and value in (0, 1, 2, 3), "Only simulation RC fault injection is permitted")
            action = lambda: self.link.mav.param_set_send(1, 1, name.encode(), value, mavutil.mavlink.MAV_PARAM_TYPE_INT8)
        else:
            action = lambda: self.link.mav.param_request_read_send(1, 1, name.encode(), -1)
        msg = self.wait_for(lambda m: m.get_type() == "PARAM_VALUE" and m.param_id == name and
                            (value is None or m.param_value == value), 5, action)
        return msg.param_value

    def manual(self, axes, link=None, target=1):
        (link or self.link).mav.manual_control_send(target, *axes, 0)

    def override(self, values, ext9=0):
        self.link.mav.rc_channels_override_send(1, 1, *values, ext9)

    def healthy_rc(self, healthy, timeout=5):
        bit = mavutil.mavlink.MAV_SYS_STATUS_SENSOR_RC_RECEIVER
        return self.wait_for(lambda m: m.get_type() == "SYS_STATUS" and bool(m.onboard_control_sensors_health & bit) == healthy,
                             timeout)

    def cases(self):
        vectors = json.loads((ROOT / "artifacts/vectors.json").read_text())
        java_manual, java_release = bytes.fromhex(vectors["manual"]), bytes.fromhex(vectors["release"])
        self.report["java_vectors_sha256"] = hashlib.sha256((ROOT / "artifacts/vectors.json").read_bytes()).hexdigest()
        self.wait_rc([2000, 2000, 1500, 1375], action=lambda: self.link.write(java_manual))
        self.record("java_manual_control_received", rc_channels=[2000, 2000, 1500, 1375])
        self.link.write(java_release)
        self.wait_rc(self.primary[:4])
        self.record("java_release_returns_primary_axes", rc_channels=self.primary[:4])

        for name, change_source, target in (("wrong_target_rejected", False, 42), ("wrong_gcs_sysid_rejected", True, 1)):
            if change_source:
                self.link.mav.srcSystem = 42
            messages = self.observe(0.4, lambda: self.manual([0, 800, 0, 0], target=target))
            self.link.mav.srcSystem = 255
            rc = [m for m in messages if m.get_type() == "RC_CHANNELS"]
            require(rc and all(m.chan1_raw == self.primary[0] for m in rc), name)
            self.record(name)

        self.wait_rc([1800, 1500, 1000, 1500], action=lambda: self.manual([0, 600, 0, 0]))
        self.wait_rc([1200, 1500, 1000, 1500], action=lambda: self.manual([0, -600, 0, 0], link=self.peer))
        self.record("second_serial_same_sysid_can_replace_control", consequence="sysid is not exclusive ownership or authentication")
        self.link.write(java_release)
        self.wait_rc(self.primary[:4])

        self.wait_rc([1750, 1550, 1000, 1500], action=lambda: self.override([1750] + [65535] * 7, 1800))
        self.wait_for(lambda m: m.get_type() == "RC_CHANNELS" and m.chan9_raw == 1800)
        self.override([65535] * 8, 0)
        messages = self.observe(0.3)
        require(any(m.get_type() == "RC_CHANNELS" and m.chan1_raw == 1750 and m.chan9_raw == 1800 for m in messages),
                "Ignore semantics did not preserve overrides")
        self.override([0] + [65535] * 7, 65534)
        self.wait_rc(self.primary[:4])
        self.wait_for(lambda m: m.get_type() == "RC_CHANNELS" and m.chan9_raw == 1500)
        self.record("override_ignore_and_extension_release", base_release=0, extension_release=65534, extension_zero="ignore")

        self.wait_rc([1800, 1500, 1000, 1500], action=lambda: self.manual([0, 600, 0, 0]))
        started = time.monotonic()
        self.wait_rc(self.primary[:4], timeout=4)
        elapsed = time.monotonic() - started
        require(2.5 <= elapsed <= 3.6, f"Unexpected timeout {elapsed}")
        self.record("default_override_timeout", observed_wall_seconds=round(elapsed, 3), parameter_seconds=3.0)

        self.parameter("SIM_RC_FAIL", 1)
        self.healthy_rc(False, timeout=5)
        self.record("primary_rc_no_pulses_sets_unhealthy", fault="SIM_RC_FAIL=1; simulated receiver input, not electrical SBUS")
        self.wait_rc([1800, 1500, 1000, 1500], action=lambda: self.manual([0, 600, 0, 0]))
        messages = self.observe(1.2, lambda: self.manual([0, 600, 0, 0]))
        health = [bool(m.onboard_control_sensors_health & mavutil.mavlink.MAV_SYS_STATUS_SENSOR_RC_RECEIVER)
                  for m in messages if m.get_type() == "SYS_STATUS"]
        require(health and health[-1], "Expected valid overrides to clear the RC failsafe indication")
        self.record("valid_override_stream_masks_primary_rc_failure", sim_rc_fail=1, rc_health_bit=True,
                    consequence="SYS_STATUS and overridden RC_CHANNELS cannot prove physical primary recovery")
        # A release with no valid primary must not be interpreted as a successful hand-back.
        self.link.write(java_release)
        self.observe(1.2)
        self.healthy_rc(False, timeout=5)
        self.record("release_during_primary_failure_does_not_restore_health")
        self.parameter("SIM_RC_FAIL", 0)
        self.wait_rc(self.primary[:4])
        self.healthy_rc(True)
        self.record("primary_restored_after_fault_removed")

        self.parameter("SIM_RC_FAIL", 3)
        self.healthy_rc(False, timeout=5)
        self.record("primary_protocol_failsafe_flag_sets_unhealthy", sim_rc_fail=3)
        self.parameter("SIM_RC_FAIL", 0)
        self.wait_rc(self.primary[:4])
        self.healthy_rc(True)

        self.arbitrated_sitl(java_release)
        after = {name: self.parameter(name) for name in self.safety}
        require(after == self.safety, "Safety/failsafe/fence parameters changed")
        self.report["safety_after"] = after
        self.record("safety_parameters_unchanged_and_disarmed")

    def arbitrated_sitl(self, release):
        keys = {device: secrets.token_bytes(32) for device in ("A", "B")}
        forwarded = []

        def sink(kind, axes):
            forwarded.append(kind)
            if kind == "manual":
                self.manual(axes)
            else:
                self.link.write(release)

        gate = Arbiter(keys, sink)
        now = lambda: int(time.monotonic() * 1000)
        require(self.last_heartbeat is not None and time.monotonic() - self.last_heartbeat < 1.5, "Target heartbeat stale")
        gate.observe_target(now(), True)
        lease = gate.grant("A", now())
        seq = 0

        def packet(device, axes):
            nonlocal seq
            seq += 1
            return signed_packet(keys[device], device=device, **lease, seq=seq,
                                 source_ms=now(), axes=axes, deadman=True, source_valid=True)

        require(gate.accept(packet("A", [0, 0, 0, 0]), now()) == "forwarded", "Initial neutral sample rejected")
        time.sleep(0.06)
        require(gate.accept(packet("B", [0, -600, 0, 0]), now()) == "not-owner", "Second device not excluded")
        require(gate.accept(packet("A", [0, 600, 0, 0]), now()) == "forwarded", "Owner rejected")
        self.wait_rc([1800, 1500, 1000, 1500])
        self.observe(0.17)
        require(not gate.poll(now()) and gate.state == "STOPPED", "Input timeout did not latch")
        count = len(forwarded)
        require(gate.accept(packet("A", [0, 600, 0, 0]), now()) == "inactive", "Automatic retakeover")
        self.wait_rc(self.primary[:4], timeout=4)
        require(len(forwarded) == count, "Expired gate sent more frames")
        self.record("arbiter_to_sitl_owner_exclusion_and_stale_stop", forwarded_before_stop=count,
                    input_timeout_threshold_ms=150, fc_retains_last_override_seconds=3.0)

        # Explicit reset/grant, then independent lab receiver evidence permits release.
        gate.acknowledge_stop(now())
        gate.observe_target(now(), True)
        lease = gate.grant("A", now())
        require(gate.accept(packet("A", [0, 0, 0, 0]), now()) == "forwarded", "Regrant failed")
        self.wait_rc([1500, 1500, 1000, 1500])
        gate.observe_primary(now(), now(), True)  # Lab producer + SIM_RC_FAIL=0, NOT RC_CHANNELS alone.
        require(gate.hand_back(now()), "Confirmed lab hand-back failed")
        self.wait_rc(self.primary[:4])
        require(gate.accept(packet("A", [0, 600, 0, 0]), now()) == "inactive", "Hand-back auto reacquired")
        self.record("arbiter_explicit_hand_back_without_reacquisition")

    def close(self):
        self.stop_rc.set()
        if hasattr(self, "rc_thread"):
            self.rc_thread.join(1)
        if self.child is not None and self.child.poll() is None:
            self.child.terminate()
            try:
                self.child.wait(5)
            except subprocess.TimeoutExpired:
                self.child.kill()
                self.child.wait()
        for link in self.links:
            link.close()
        if hasattr(self, "log"):
            self.log.close()
        if hasattr(self, "work"):
            self.work.cleanup()
        self.report["status_text"] = self.status_text
        self.output.mkdir(parents=True, exist_ok=True)
        (self.output / "report.json").write_text(json.dumps(self.report, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/sitl")
    args = parser.parse_args()
    experiment = Experiment(args.source.resolve(), args.output.resolve())
    try:
        experiment.start()
        experiment.cases()
        experiment.report["result"] = "PASS"
    except BaseException as error:
        experiment.report["result"] = "FAIL"
        experiment.report["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        experiment.close()


if __name__ == "__main__":
    main()
