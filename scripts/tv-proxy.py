#!/usr/bin/env python3
"""Loopback relay with an owned control socket; only /usr/bin/nc contacts the TV.

Keeping the remote connection in Apple's nc preserves the macOS local-network
workaround. Each ADB reconnect gets a fresh nc child; no FIFO or process-name kill.
"""
import argparse
import fcntl
import json
import os
from pathlib import Path
import selectors
import socket
import subprocess
import sys
import threading
import time


def control(path, command):
    try:
        with socket.socket(socket.AF_UNIX) as client:
            client.settimeout(1)
            client.connect(str(path))
            client.sendall(command.encode())
            return json.loads(client.recv(4096))
    except (OSError, ValueError):
        return None


def relay(client, host, port, nc):
    process = None
    try:
        process = subprocess.Popen([nc, '-G', '5', host, str(port)],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, bufsize=0)
        def upload():
            try:
                while True:
                    data = client.recv(65536)
                    if not data:
                        break
                    pending = memoryview(data)
                    while pending:
                        written = process.stdin.write(pending)
                        pending = pending[written:]
            except OSError:
                pass
            finally:
                # ADB disconnected: discard the remote session, ready for a fresh handshake.
                if process.poll() is None:
                    process.terminate()
        thread = threading.Thread(target=upload, daemon=True)
        thread.start()
        while True:
            data = process.stdout.read(65536)
            if not data:
                break
            client.sendall(data)
    except OSError as error:
        print(f'relay: {error}', file=sys.stderr, flush=True)
    finally:
        try:
            client.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        client.close()
        if process is not None:
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            process.stdin.close()
            process.stdout.close()


def serve(args):
    state = Path(args.state)
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (state / 'lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        path = state / 'control.sock'
        path.unlink(missing_ok=True)
        clients = []
        with socket.socket() as listener, socket.socket(socket.AF_UNIX) as commands:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('127.0.0.1', args.local_port))
            listener.listen(4)
            commands.bind(str(path))
            os.chmod(path, 0o600)
            commands.listen(2)
            selector = selectors.DefaultSelector()
            selector.register(listener, selectors.EVENT_READ)
            selector.register(commands, selectors.EVENT_READ)
            try:
                running = True
                while running:
                    for key, _ in selector.select():
                        if key.fileobj is commands:
                            client, _ = commands.accept()
                            with client:
                                client.settimeout(1)
                                try:
                                    command = client.recv(32).decode()
                                    client.sendall(json.dumps({'host': args.host, 'port': args.port,
                                                              'local_port': args.local_port}).encode())
                                    if command == 'stop':
                                        running = False
                                except OSError:
                                    pass
                        else:
                            client, _ = listener.accept()
                            thread = threading.Thread(target=relay,
                                                      args=(client, args.host, args.port, args.nc),
                                                      daemon=True)
                            clients = [(sock, job) for sock, job in clients if job.is_alive()]
                            clients.append((client, thread))
                            thread.start()
            finally:
                selector.close()
                for client, _ in clients:
                    try:
                        client.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                for _, thread in clients:
                    thread.join(timeout=3)
                path.unlink(missing_ok=True)


def start(args):
    state = Path(args.state)
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = state / 'control.sock'
    wanted = {'host': args.host, 'port': args.port, 'local_port': args.local_port}
    # Serialize starts, including a change of remote endpoint on the same local port.
    with (state / 'start.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        current = control(path, 'status')
        if current == wanted:
            return
        if current:
            control(path, 'stop')
            for _ in range(50):
                if not path.exists():
                    break
                time.sleep(0.1)
            else:
                raise RuntimeError('旧转发尚未退出，请稍后重试')
        with (state / 'proxy.log').open('a') as log:
            process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), 'serve',
                                        '--host', args.host, '--port', str(args.port),
                                        '--local-port', str(args.local_port), '--state', str(state),
                                        '--nc', args.nc], stdin=subprocess.DEVNULL,
                                       stdout=log, stderr=log, start_new_session=True)
        for _ in range(50):
            if control(path, 'status') == wanted:
                return
            if process.poll() is not None:
                break
            time.sleep(0.1)
        raise RuntimeError(f'转发启动失败（端口可能被占用），请查看 {state / "proxy.log"}')


def verify(args):
    serial = f'127.0.0.1:{args.local_port}'
    for _ in range(args.attempts):
        try:
            subprocess.run([args.adb, 'connect', serial], timeout=5,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            result = subprocess.run([args.adb, '-s', serial, 'get-state'], timeout=5,
                                    capture_output=True, text=True)
            if result.returncode == 0 and result.stdout.strip() == 'device':
                print(f'电视已连接：{serial}')
                return
        except subprocess.TimeoutExpired:
            pass
        time.sleep(0.5)
    raise RuntimeError('电视未就绪（离线或未授权）；请检查电视上的 USB 调试授权提示后重试')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['start', 'serve', 'stop', 'verify'])
    parser.add_argument('--host')
    parser.add_argument('--port', type=int, default=5555)
    parser.add_argument('--local-port', type=int, default=15555)
    parser.add_argument('--state', required=True)
    parser.add_argument('--nc', default='/usr/bin/nc')
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--attempts', type=int, default=3)
    args = parser.parse_args()
    if not (1 <= args.port <= 65535 and 1 <= args.local_port <= 65535):
        parser.error('端口必须在 1–65535 之间')
    if args.command in ('start', 'serve') and (not args.host or args.host.startswith('-')):
        parser.error('需要有效的电视地址')
    if args.command == 'stop':
        control(Path(args.state) / 'control.sock', 'stop')
    else:
        {'start': start, 'serve': serve, 'verify': verify}[args.command](args)


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
