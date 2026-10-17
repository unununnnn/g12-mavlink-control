"""Independent pymavlink wire oracle, with no device or network access."""
import json
import unittest
import re
from pathlib import Path
from pymavlink.dialects.v20 import common as v2
from pymavlink.dialects.v10 import common as v1


class DirectWireTests(unittest.TestCase):
    def test_incoming_version_fixture_is_independent(self):
        source = Path('x9-core/src/test/java/com/g12/x9core/DirectTests.java').read_text(encoding='utf-8')
        packet = bytes.fromhex(re.search(r'VERSION_FIXTURE="([0-9a-f]+)"', source)[1])
        version = v2.MAVLink_autopilot_version_message(2, 0x040701ff, 0, 0, 0, list(range(1, 9)), [0]*8, [0]*8, 0, 0, 0x123456789, [0]*18)
        self.assertEqual(version.pack(v2.MAVLink(None, srcSystem=7, srcComponent=1)), packet)

    def test_exact_independent_bytes_and_fields(self):
        packets = [bytes.fromhex(h) for h in json.loads(Path('artifacts/x9-vectors.json').read_text())]
        for i, packet in enumerate(packets):
            dialect = v1 if i == 1 else v2
            writer = dialect.MAVLink(None, srcSystem=255, srcComponent=191)
            writer.seq = 17 + i
            if i < 2:
                channels = [1500, 1300, 1200, 1700] + [65535] * (4 if i == 1 else 14)
                message = dialect.MAVLink_rc_channels_override_message(7, 1, *channels)
            elif i == 2:
                message = dialect.MAVLink_command_long_message(7, 1, 512, 0, 148, 0, 0, 0, 0, 0, 0)
            else:
                message = dialect.MAVLink_param_request_read_message(7, 1, b'RC_OVERRIDE_TIME', -1)
            self.assertEqual(message.pack(writer), packet)
            decoded = dialect.MAVLink(None).parse_buffer(packet)[0]
            self.assertEqual(decoded.get_srcSystem(), 255)
            self.assertEqual(decoded.get_srcComponent(), 191)
            self.assertEqual(decoded.target_system, 7)
            self.assertEqual(decoded.target_component, 1)
            if i < 2:
                self.assertEqual([getattr(decoded, f'chan{n}_raw') for n in range(1, 5)], [1500, 1300, 1200, 1700])
                self.assertTrue(all(getattr(decoded, f'chan{n}_raw') == 65535 for n in range(5, 9 if i == 1 else 19)))


if __name__ == '__main__':
    unittest.main()
