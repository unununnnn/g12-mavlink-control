import multiprocessing as mp
import secrets
import unittest
from lab.arbiter import Arbiter, signed_packet


def client(pipe, key, device, lease):
    """Each child has only its own credential and no reference to the arbiter."""
    for seq in range(2):
        now, axes = pipe.recv()
        pipe.send(signed_packet(key, device=device, **lease, seq=seq,
                                source_ms=now, axes=axes, deadman=True, source_valid=True))
    pipe.close()


class ArbitrationTests(unittest.TestCase):
    def setUp(self):
        self.keys = {name: secrets.token_bytes(32) for name in ("A", "B")}
        self.output = []
        self.gate = Arbiter(self.keys, lambda kind, axes: self.output.append((kind, axes)))
        self.gate.observe_target(100, True)
        self.lease = self.gate.grant("A", 100)

    def packet(self, seq=0, now=100, axes=None, device="A", **changes):
        body = dict(device=device, **self.lease, seq=seq, source_ms=now,
                    axes=axes or [0, 0, 0, 0], deadman=True, source_valid=True)
        body.update(changes)
        return signed_packet(self.keys[device], **body)

    def test_two_independent_clients_cannot_share_owner(self):
        ctx = mp.get_context("spawn")
        children = []
        try:
            for name in self.keys:
                parent, child = ctx.Pipe()
                process = ctx.Process(target=client, args=(child, self.keys[name], name, self.lease))
                process.start()
                child.close()
                children.append((parent, process))
            for seq, now in enumerate((100, 160)):
                for pipe, _ in children:
                    pipe.send((now, [0, 0, 0, 0] if seq == 0 else [100, 200, 0, 0]))
                results = []
                for pipe, _ in reversed(children):
                    self.assertTrue(pipe.poll(10), "client process timed out")
                    results.append(self.gate.accept(pipe.recv(), now))
                self.assertEqual(results, ["not-owner", "forwarded"])
            self.assertEqual(len(self.output), 2)
            self.assertIsNone(self.gate.grant("B", 160))
        finally:
            for pipe, process in children:
                pipe.close()
                process.join(3)
                if process.is_alive():
                    process.terminate()
                    process.join()
                self.assertEqual(process.exitcode, 0)

    def test_forgery_replay_old_epoch_and_restart(self):
        valid = self.packet()
        self.assertEqual(self.gate.accept(valid.replace(b'"A"', b'"B"'), 100), "unauthenticated")
        self.assertEqual(self.gate.accept(valid, 100), "forwarded")
        self.assertEqual(self.gate.accept(valid, 101), "replay")
        self.gate.observe_primary(101, 101, True)
        self.assertTrue(self.gate.hand_back(101))
        self.assertEqual(self.gate.accept(self.packet(seq=2, now=102), 102), "inactive")
        self.assertIsNotNone(self.gate.grant("A", 103))
        self.assertEqual(self.gate.accept(self.packet(seq=3, now=104), 104), "old-lease")
        restarted = Arbiter(self.keys, lambda *_: self.fail("Old boot must not forward"))
        restarted.observe_target(105, True)
        restarted.grant("A", 105)
        self.assertEqual(restarted.accept(valid, 105), "old-lease")

    def test_timeout_latches_and_no_automatic_retakeover(self):
        self.assertEqual(self.gate.accept(self.packet(), 100), "forwarded")
        self.assertFalse(self.gate.poll(251))
        self.assertEqual(self.gate.accept(self.packet(seq=1, now=252), 252), "inactive")
        self.gate.observe_target(252, True)
        self.assertIsNone(self.gate.grant("A", 253))
        self.assertTrue(self.gate.acknowledge_stop(254))
        self.assertIsNone(self.gate.grant("A", 254))
        self.gate.observe_target(255, True)
        self.assertIsNotNone(self.gate.grant("A", 255))
        self.assertEqual(len(self.output), 1)

    def test_bad_primary_does_not_send_release(self):
        self.gate.accept(self.packet(), 100)
        self.gate.observe_primary(0, 101, True)
        self.assertFalse(self.gate.hand_back(101))
        self.assertEqual(self.gate.state, "STOPPED")
        self.assertEqual([x[0] for x in self.output], ["manual"])

    def test_rate_limit_neutral_and_source_checks(self):
        self.assertEqual(self.gate.accept(self.packet(), 100), "forwarded")
        self.assertEqual(self.gate.accept(self.packet(seq=1, now=110), 110), "rate-limited")
        self.assertEqual(self.gate.accept(self.packet(seq=2, now=110), 111), "stopped")
        self.assertEqual(len(self.output), 1)
        for changes in ({"axes": [500, 0, 0, 0]}, {"deadman": False},
                        {"source_valid": False}, {"source_ms": 101}, {"axes": [0, 0, -1, 0]}):
            gate = Arbiter(self.keys, lambda *_: self.fail("Unsafe first sample"))
            gate.observe_target(100, True)
            lease = gate.grant("A", 100)
            packet = self.packet(**changes, boot=lease["boot"], epoch=lease["epoch"])
            self.assertEqual(gate.accept(packet, 100), "stopped")

    def test_clock_target_and_sink_failure(self):
        self.assertFalse(self.gate.poll(99))
        gate = Arbiter(self.keys, lambda *_: (_ for _ in ()).throw(OSError("closed")))
        gate.observe_target(100, True)
        lease = gate.grant("A", 100)
        self.assertEqual(gate.accept(self.packet(**lease), 100), "stopped")
        gate = Arbiter(self.keys, lambda *_: None)
        gate.observe_target(100, True)
        gate.grant("A", 100)
        gate.observe_target(101, False)
        self.assertEqual(gate.state, "STOPPED")

    def test_bounded_lease_and_malformed(self):
        for malformed in (b"{}", b"[]", b"null", b"x" * 2049, b'{"body":{},"body":{}}'):
            self.assertEqual(self.gate.accept(malformed, 100), "malformed")
        for now in range(100, 2100, 50):
            self.gate.observe_target(now, True)
            result = self.gate.accept(self.packet(seq=now, now=now), now)
            self.assertEqual(result, "forwarded")
        self.assertFalse(self.gate.poll(2100))
        self.assertEqual(len(self.output), 40)

    def test_late_target_observation_cannot_revive_expired_lease(self):
        self.gate.accept(self.packet(), 100)
        self.gate.observe_target(251, True)
        self.assertEqual(self.gate.state, "STOPPED")
        self.assertIsNone(self.gate.grant("B", 251))


if __name__ == "__main__":
    unittest.main()
