import json
import unittest
from pathlib import Path
from pymavlink.dialects.v20 import common as mav
from lab.serial_echo import Capture

class SerialCaptureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.vectors = json.loads(Path('artifacts/sender-vectors.json').read_text())

    def test_independent_encoding(self):
        expected = {
            'manual': mav.MAVLink_manual_control_message(7,1000,1000,1000,1000,0),
            'override': mav.MAVLink_rc_channels_override_message(7,2,65535,2000,65535,2000,2000,65535,2000,65535),
            'release': mav.MAVLink_rc_channels_override_message(7,2,65535,0,65535,0,0,65535,0,65535),
        }
        for name,msg in expected.items():
            data=bytes.fromhex(self.vectors[name]); writer=mav.MAVLink(None,srcSystem=245,srcComponent=191);writer.seq=data[4]
            self.assertEqual(msg.pack(writer),data,name)

    def test_fragmentation_crc_and_exact_echo(self):
        data=bytes.fromhex(self.vectors['manual'])
        for split in range(len(data)+1):
            c=Capture(245,191,7,2,69)
            got=c.feed(data[:split],0)+c.feed(data[split:],0.1)
            self.assertEqual([item[0] for item in got],[data])
        corrupt=bytearray(data);corrupt[10]^=1
        self.assertEqual(Capture(245,191,7,2,69).feed(corrupt,0),[])

    def test_source_target_message_filters(self):
        data=bytes.fromhex(self.vectors['manual'])
        for ids in [(244,191,7,2,69),(245,190,7,2,69),(245,191,8,2,69),(245,191,7,2,70)]:
            self.assertEqual(Capture(*ids).feed(data,0),[])
        override=bytes.fromhex(self.vectors['override'])
        self.assertEqual(Capture(245,191,7,3,70).feed(override,0),[])
        self.assertEqual(len(Capture(245,191,7,2,70).feed(override,0)),1)

    def test_partial_timeout_and_noise(self):
        data=bytes.fromhex(self.vectors['manual']);c=Capture(245,191,7,2,69)
        self.assertEqual(c.feed(data[:5],0),[])
        self.assertEqual(c.feed(b'',0.1),[])
        self.assertEqual(c.feed(b'',0.2),[])
        self.assertEqual(c.feed(data[5:],0.3),[])
        self.assertEqual([r[0] for r in c.feed(b'noise'+data,0.4)],[data])

if __name__=='__main__': unittest.main()
