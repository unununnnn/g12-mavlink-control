"""Run the actual Java direct controller against our owned, disarmed fixed SITL.

Requires an isolated loopback-only network namespace. No external endpoint option.
"""
import argparse
import queue
import subprocess
import threading
import time
from pathlib import Path
from lab.sitl_e2e import Experiment, ROOT, require
from pymavlink import mavutil


def run(source, output):
    experiment = Experiment(source.resolve(), output.resolve())
    java = None
    try:
        experiment.start()
        experiment.report['scope'] = 'actual x9-core Java session over stdio, owned disarmed stock Copter 4.7.1, no hardware'
        java = subprocess.Popen(['java', '-cp', str(ROOT/'artifacts/x9-sitl-classes'), 'com.g12.x9core.SitlHarness'],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, bufsize=1)
        lines = queue.Queue()
        def read_output():
            for line in java.stdout:
                lines.put(line.rstrip())
        threading.Thread(target=read_output, daemon=True).start()
        transcript, counts, passed = [], {}, False
        decoder = mavutil.mavlink.MAVLink(None)
        deadline = time.monotonic()+80
        while time.monotonic()<deadline:
            while not lines.empty():
                line = lines.get()
                if line.startswith('TX '):
                    data = bytes.fromhex(line[3:]); msg = decoder.parse_buffer(data)[0]
                    kind = msg.get_type()
                    require(kind in ('PARAM_REQUEST_READ', 'COMMAND_LONG', 'RC_CHANNELS_OVERRIDE'), 'Unexpected controller output')
                    require(msg.target_system == 1 and msg.target_component == 1, 'Unexpected target')
                    if kind == 'COMMAND_LONG':
                        require(msg.command == 512 and msg.param1 in (65, 148), 'Only telemetry reads allowed')
                    if kind == 'RC_CHANNELS_OVERRIDE':
                        require(all(getattr(msg, f'chan{i}_raw') == 65535 for i in range(5, 19)), 'Auxiliary channel changed')
                    counts[kind] = counts.get(kind, 0)+1
                    experiment.link.write(data)
                else:
                    transcript.append(line); print(line, flush=True)
                    if line.startswith('PASS '):
                        passed = True
            if java.poll() is not None and lines.empty():
                break
            msg = experiment.receive(0.01)
            if msg and msg.get_type() != 'BAD_DATA' and java.poll() is None:
                java.stdin.write(bytes(msg.get_msgbuf()).hex()+'\n'); java.stdin.flush()
        require(java.wait(timeout=2) == 0 and passed, 'Java controller did not pass; inspect transcript')
        after = {key: experiment.parameter(key) for key in experiment.safety}
        require(after == experiment.safety, 'Flight controller safety parameters changed')
        experiment.report.update(result='PASS', controller_counts=counts, controller_transcript=transcript, safety_after=after)
        experiment.record('actual_java_direct_session_continuous_stop_reconnect', **counts)
    except BaseException as error:
        experiment.report.update(result='FAIL', error=f'{type(error).__name__}: {error}')
        raise
    finally:
        if java is not None and java.poll() is None:
            java.terminate(); java.wait(timeout=5)
        experiment.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=ROOT/'artifacts/x9-sitl')
    args = parser.parse_args()
    run(args.source, args.output)
