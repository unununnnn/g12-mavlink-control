"""Independent pymavlink oracle; no network or transport is opened."""
import importlib.metadata
import json
from pathlib import Path
from pymavlink.dialects.v20 import common as mav

root = Path(__file__).resolve().parents[1]
vectors = json.loads((root / "artifacts/vectors.json").read_text())
expected = {
    "manual": mav.MAVLink_manual_control_message(1, -1000, 1000, 500, -250, 0),
    "release": mav.MAVLink_rc_channels_override_message(1, 1, 0, 0, 0, 0, 65535, 65535, 65535, 65535),
    "heartbeat": mav.MAVLink_heartbeat_message(2, 3, 0, 0, 3, 3),
    "version": mav.MAVLink_autopilot_version_message(0, 0x040701FF, 0, 0, 0, [0]*8, [0]*8, [0]*8, 0, 0, 0x12345678),
}
for name, msg in expected.items():
    data = bytes.fromhex(vectors[name])
    writer = mav.MAVLink(None, srcSystem=data[5], srcComponent=data[6])
    writer.seq = data[4]
    packed = msg.pack(writer)
    assert packed == data, f"{name}: independent encoder mismatch {packed.hex()} != {data.hex()}"
    parser = mav.MAVLink(None)
    decoded = parser.parse_buffer(data)
    assert len(decoded) == 1 and decoded[0].get_msgId() == msg.get_msgId()
    print(f"PASS {name}: independent byte-for-byte encoder + decoder ({len(data)} bytes)")
print("pymavlink", importlib.metadata.version("pymavlink"))
