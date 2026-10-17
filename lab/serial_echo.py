"""Explicitly operated GR00-to-PC serial capture/byte echo. NEVER connect its UART to a flight controller.
Only echoes CRC-valid, unsigned MAVLink2 #69/#70 with the exact configured source/target IDs.
No scanning, automatic serial opening, arming, commands, parameter writes, or heartbeat generation.
"""
import argparse
import json
import time
from pymavlink.dialects.v20 import common as mav

class Capture:
    def __init__(self, source_system, source_component, target_system, target_component, message):
        self.ids = source_system, source_component, target_system, target_component
        self.message = message
        self.parser = mav.MAVLink(None)
        self.parser.robust_parsing = True
        self.count = 0
        self.last = None

    def feed(self, data, now):
        if self.last is not None and (now < self.last or now - self.last > 0.25):
            self.parser = mav.MAVLink(None)
            self.parser.robust_parsing = True
        if not data:
            return []
        self.last = now
        result = []
        for msg in self.parser.parse_buffer(data) or []:
            if msg.get_type() == 'BAD_DATA' or msg.get_msgId() not in (69, 70):
                continue
            wire = bytes(msg.get_msgbuf())
            if wire[0] != 0xfd or wire[2] != 0 or wire[3] != 0 or msg.get_msgId() != self.message:
                continue
            source_system, source_component, target_system, target_component = self.ids
            if msg.get_srcSystem() != source_system or msg.get_srcComponent() != source_component:
                continue
            target = msg.target if msg.get_msgId() == 69 else msg.target_system
            if target != target_system or (msg.get_msgId() == 70 and msg.target_component != target_component):
                continue
            self.count += 1
            record = {'n': self.count, 'monotonic_s': now, 'source_system': source_system,
                      'source_component': source_component, 'sequence': msg.get_seq(),
                      'message': msg.to_dict(), 'hex': wire.hex(),
                      'meaning': 'PC UART bytes received; NOT flight-controller acceptance'}
            result.append((wire, record))
        return result

def nonzero_id(value):
    number = int(value)
    if not 1 <= number <= 255:
        raise argparse.ArgumentTypeError('ID must be 1..255')
    return number

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', required=True, help='Explicit PC serial port, e.g. COM7; no auto discovery')
    parser.add_argument('--baud', required=True, type=int, help='Must match actual GR00 UART setting')
    parser.add_argument('--source-system', required=True, type=nonzero_id)
    parser.add_argument('--source-component', required=True, type=nonzero_id)
    parser.add_argument('--target-system', required=True, type=nonzero_id)
    parser.add_argument('--target-component', required=True, type=nonzero_id, help='Only #70 contains this field')
    parser.add_argument('--message', required=True, choices=[69,70], type=int)
    parser.add_argument('--flight-controller-disconnected', required=True, action='store_true')
    parser.add_argument('--seconds', type=int, default=120, help='Bounded capture duration, 1..600 seconds')
    parser.add_argument('--log', help='Optional NEW JSONL file; refuses to overwrite')
    args = parser.parse_args()
    if not 9600 <= args.baud <= 2000000 or not 1 <= args.seconds <= 600:
        parser.error('baud must be 9600..2000000 and seconds 1..600')
    import serial  # Loaded only after the user supplies all configuration/confirmation.
    capture = Capture(args.source_system,args.source_component,args.target_system,args.target_component,args.message)
    log = open(args.log,'x',encoding='utf-8') if args.log else None
    try:
        with serial.Serial(args.port,args.baud,timeout=0.05,write_timeout=0.1) as port:
            port.reset_input_buffer()
            until = time.monotonic() + args.seconds
            print('PC CAPTURE ONLY. Exact valid frames will be echoed; Ctrl+C stops. No flight controller.',flush=True)
            while time.monotonic() < until:
                for wire, record in capture.feed(port.read(512),time.monotonic()):
                    # A short/blocked write is a terminal failure; never retry old joystick values.
                    if port.write(wire) != len(wire):
                        raise RuntimeError('Short serial echo write; stopped without retry')
                    line = json.dumps(record,ensure_ascii=False)
                    print(line,flush=True)
                    if log:
                        log.write(line+'\n'); log.flush()
    finally:
        if log: log.close()
    print(f'Captured/echoed {capture.count} frames. This is not a flight-controller ACK.')

if __name__ == '__main__':
    main()
