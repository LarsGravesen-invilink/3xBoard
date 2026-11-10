import ast
import base64
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tarfile
import tempfile
import time
import types
import unittest
from unittest.mock import patch


HERE = Path(__file__).resolve().parent
SOURCE = HERE.parents[5] / 'main/java/app/invilink/xboard/ssh'
JOB_ID = '01234567-89ab-cdef-0123-456789abcdef'
UNIT = b'[Unit]\nDescription=fixture\n[Service]\nExecStart=/usr/local/x-ui/x-ui\n'
CLI = b'fixture CLI, never executed\n'
PINS = {
    '3.10.0': ('b647a3e7d6695600bdd18cbfd34959991be09d4b1ad1e05efa790c0cf69ca40d',
               'a0bb393989b96833c84460dff21c968a6263f4e09b7ceff93e0f92975aeadc16'),
    '3.11.0': ('0ddfa4702f595442e2962acd81addc768fdacaa4ff2672c971a0463dae744916',
               'f50b4c30fd919d018f2f4c3d8db53a4ca027503d581328727770afb116a79d90'),
}


class FixtureStat:
    def __init__(self, original, owner):
        self.original = original
        self.st_uid, self.st_gid = owner

    def __getattr__(self, key):
        return getattr(self.original, key)

    def __getitem__(self, key):
        return {4: self.st_uid, 5: self.st_gid}.get(key, self.original[key])


class Paths(ast.NodeTransformer):
    def __init__(self, fixture):
        self.fixture = fixture

    def visit_Compare(self, node):
        if isinstance(node.left, ast.Name) and node.left.id == 'parent':
            for value in node.comparators:
                if isinstance(value, ast.Constant) and value.value == '/':
                    value.value = str(self.fixture.root)
        return self.generic_visit(node)

    def visit_Constant(self, node):
        if isinstance(node.value, str):
            if node.value in self.fixture.pins:
                node.value = self.fixture.pins[node.value]
            else:
                for prefix in ('/root', '/usr', '/etc', '/proc', '/run', '/lib'):
                    if node.value == prefix or node.value.startswith(prefix + '/'):
                        node.value = str(self.fixture.root) + node.value
                        break
                else:
                    node.value = node.value.replace('path=/usr/local/x-ui', 'path=' + str(self.fixture.path('/usr/local/x-ui')))
                    node.value = node.value.replace('argv\\[\\]=/usr/local/x-ui', 'argv\\[\\]=' + str(self.fixture.path('/usr/local/x-ui')))
        elif isinstance(node.value, bytes) and node.value == b'/usr/bin/python3\0-I\0':
            node.value = str(self.fixture.path('/usr/bin/python3')).encode() + b'\0-I\0'
        return node


class Fixture:
    def __init__(self, root, producer, uid=1001, version='3.11.0', operation='REINSTALL', active=False, arch='amd64'):
        self.root = root
        self.producer = producer
        self.uid = uid
        self.version = version
        self.operation = operation
        self.active = active
        self.arch = arch
        self.owners = {}
        self.calls = []
        self.phases = []
        self.pins = {
            '26c229d5fb3b184e81581b4b223b801582ca7cf5908aaedeb8d1c7c70ad4cc25': hashlib.sha256(CLI).hexdigest(),
            '95cb6e39499601148404cadf3b4d75b0489301ff7e7ab565cd9db21d23be957c': hashlib.sha256(UNIT).hexdigest(),
        }
        for directory in ('root', 'usr/local/x-ui/bin', 'usr/bin', 'etc/x-ui', 'etc/systemd/system', 'proc/self'):
            self.path(directory).mkdir(parents=True, exist_ok=True, mode=0o755)
        for directory in self.root.rglob('*'):
            if directory.is_dir():
                directory.chmod(0o755)
        self.path('/root').chmod(0o700)
        self.job = self.path('/root/.xboard-maintenance/' + JOB_ID)
        self.live = self.path('/usr/local/x-ui')
        self.archives = {}
        for release in PINS:
            data = io.BytesIO()
            with tarfile.open(fileobj=data, mode='w:gz') as tar:
                for name, content, mode in self.release_files(release):
                    member = tarfile.TarInfo('x-ui' + ('/' + name if name else ''))
                    member.uid = member.gid = 1001
                    member.mode = mode
                    member.type = tarfile.DIRTYPE if content is None else tarfile.REGTYPE
                    member.size = len(content or b'')
                    tar.addfile(member, None if content is None else io.BytesIO(content))
            self.archives[release] = data.getvalue()
            for pin in PINS[release]:
                self.pins[pin] = hashlib.sha256(data.getvalue()).hexdigest()
        for name, content, mode in self.release_files(version):
            target = self.live / name
            if content is None:
                target.mkdir(exist_ok=True, mode=mode)
                target.chmod(mode)
            else:
                target.write_bytes(content)
                target.chmod(mode)
            self.owners[str(target)] = (uid, uid)
        self.write('/usr/local/x-ui/bin/config.json', b'{"original":"runtime configuration"}', 0o600, (uid, uid))
        self.write('/etc/systemd/system/x-ui.service', UNIT, 0o644)
        self.write('/usr/bin/x-ui', CLI, 0o755)
        self.write('/proc/self/mountinfo', b'1 0 0:1 / / rw - ext4 fixture rw\n')
        self.write('/root/cert/server.key', b'private original key')
        self.write('/etc/x-ui/extra.conf', b'original settings')
        self.db = self.path('/etc/x-ui/x-ui.db')
        with contextlib.closing(sqlite3.connect(self.db)) as db, db:
            db.executescript('CREATE TABLE users(id INTEGER PRIMARY KEY, username TEXT, password TEXT);'
                             'CREATE TABLE settings(key TEXT, value TEXT);'
                             'CREATE TABLE inbounds(id INTEGER, settings TEXT, port INTEGER, protocol TEXT, enable INTEGER);'
                             'CREATE TABLE extra(blob BLOB, count INTEGER);')
            db.execute('INSERT INTO users VALUES (1, ?, ?)', ('user', 'password-hash'))
            db.execute('INSERT INTO settings VALUES (?, ?)', ('webKeyFile', str(self.path('/root/cert/server.key'))))
            db.execute('INSERT INTO inbounds VALUES (1, ?, 443, ?, 1)', ('{"clients":[{"id":"original"}]}', 'vless'))
            db.execute('INSERT INTO extra VALUES (?, 42)', (b'\0original',))
        self.db.chmod(0o600)
        self.original_db = self.db.read_bytes()
        self.write('/proc/999/stat', ('999 (python3) S ' + ' '.join(['0'] * 18 + ['12345']) + '\n').encode())
        self.write('/proc/999/cmdline', str(self.path('/usr/bin/python3')).encode() + b'\0-I\0' + str(self.job / 'runner.py').encode() + b'\0')
        if active:
            self.write('/proc/100/environ', b'XUI_DB_TYPE=sqlite\0')
            self.path('/proc/100/exe').symlink_to(self.live / 'x-ui')
        self.real_lstat = os.lstat
        self.real_rename = os.rename
        self.real_replace = os.replace

    def path(self, value):
        return self.root / value.lstrip('/')

    def write(self, value, content=b'', mode=0o600, owner=(0, 0)):
        target = self.path(value)
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        target.write_bytes(content)
        target.chmod(mode)
        self.owners[str(target)] = owner
        return target

    def release_files(self, release):
        return [('', None, 0o755), ('bin', None, 0o755),
                ('x-ui', ('fixture binary ' + release).encode(), 0o755),
                ('bin/xray-linux-' + self.arch, b'fixture xray never executed', 0o755),
                ('x-ui.sh', CLI, 0o755), ('x-ui.service.debian', UNIT, 0o644)]

    def lstat(self, path, *args, **kwargs):
        return FixtureStat(self.real_lstat(path, *args, **kwargs), self.owners.get(os.fspath(path), (0, 0)))

    def chown(self, path, uid, gid, **kwargs):
        self.real_lstat(path)
        self.owners[os.fspath(path)] = (uid, gid)

    def move(self, source, target, replace=False):
        source, target = os.fspath(source), os.fspath(target)
        result = (self.real_replace if replace else self.real_rename)(source, target)
        moved = {target + p[len(source):]: owner for p, owner in self.owners.items() if p == source or p.startswith(source + '/')}
        self.owners = {p: owner for p, owner in self.owners.items() if not (p == source or p.startswith(source + '/'))}
        self.owners.update(moved)
        if target == str(self.job / 'state'):
            self.phases.append(json.loads(Path(target).read_text()))
        return result

    def command(self, args, **kwargs):
        self.calls.append(args)
        name = Path(args[0]).name
        if name == 'curl':
            release = next(v for v in PINS if '/v' + v + '/' in args[-1])
            Path(args[args.index('--output') + 1]).write_bytes(self.archives[release])
            return types.SimpleNamespace(stdout=b'')
        if name != 'systemctl':
            raise AssertionError('Unexpected executable: ' + repr(args))
        if args[1] == 'show-environment':
            result = 'LANG=C'
        elif args[1] in ('stop', 'start'):
            self.active = args[1] == 'start'
            executable = self.path('/proc/100/exe')
            if executable.is_symlink():
                executable.unlink()
            if self.active:
                executable.symlink_to(self.live / 'x-ui')
            result = ''
        else:
            key = next(a.split('=', 1)[1] for a in args if a.startswith('--property='))
            props = {'FragmentPath': str(self.path('/etc/systemd/system/x-ui.service')),
                     'LoadState': 'loaded', 'NeedDaemonReload': 'no', 'WorkingDirectory': str(self.live),
                     'ExecStart': '{ path=' + str(self.live / 'x-ui') + ' ; argv[]=' + str(self.live / 'x-ui') + ' ; }',
                     'KillMode': 'control-group', 'User': 'root', 'Group': 'root',
                     'ActiveState': 'active' if self.active else 'inactive', 'MainPID': '100' if self.active else '0'}
            result = props.get(key, '')
        return types.SimpleNamespace(stdout=result.encode())

    def popen(self, args, **kwargs):
        state = json.loads((self.job / 'state').read_text())
        if (state['status'], state['phase'], state['operation']) != ('RUNNING', 'QUEUED', self.operation):
            raise AssertionError('State must exist atomically before spawning')
        if args != [str(self.path('/usr/bin/python3')), '-I', str(self.job / 'runner.py')]:
            raise AssertionError('Unexpected runner command')
        if not kwargs['start_new_session'] or not kwargs['close_fds']:
            raise AssertionError('Runner must be detached')
        if getattr(self, 'spawn_error', False):
            raise OSError('injected spawn failure')
        return types.SimpleNamespace(pid=999)

    @contextlib.contextmanager
    def mocked(self):
        with contextlib.ExitStack() as stack:
            for name, value in {
                'os.lstat': self.lstat, 'os.geteuid': lambda: 0, 'os.getegid': lambda: 0,
                'os.getpid': lambda: 999, 'os.chown': self.chown, 'os.umask': lambda _: 0o077,
                'os.rename': lambda a, b: self.move(a, b), 'os.replace': lambda a, b: self.move(a, b, True),
                'os.uname': lambda: types.SimpleNamespace(machine='x86_64' if self.arch == 'amd64' else 'aarch64'),
                'subprocess.run': self.command, 'subprocess.Popen': self.popen,
                'signal.signal': lambda *a: None, 'signal.alarm': lambda *a: None, 'time.sleep': lambda _: None,
            }.items():
                stack.enter_context(patch(name, value))
            yield

    def execute(self, name, **extra):
        code = ast.fix_missing_locations(Paths(self).visit(ast.parse(self.producer[name])))
        namespace = dict(JOB=str(self.job), OPERATION=self.operation, **extra)
        output = io.StringIO()
        with self.mocked(), contextlib.redirect_stdout(output):
            exec(compile(code, name, 'exec'), namespace)
        return json.loads(output.getvalue()) if output.getvalue() else json.loads((self.job / 'state').read_text())

    def launch(self, payload=None):
        return self.execute('LAUNCH', PAYLOAD=payload if payload is not None else self.producer['RUNNER'])


class MaintenanceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='maintenance-producer-', dir='/tmp/opencode')
        cls.output = Path(cls.temporary.name)
        cache = Path.home() / '.gradle/caches/modules-2/files-2.1'

        def jar(group, name, version):
            return str(next((cache / group / name / version).glob('*/*.jar')))

        compiler = [jar(*parts) for parts in (
            ('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.0.21'),
            ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.0.21'),
            ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.0.21'),
            ('org.jetbrains.kotlin', 'kotlin-reflect', '2.0.20'),
            ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
            ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.6.4'),
            ('org.jetbrains', 'annotations', '13.0'))]
        deps = [jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.0.21'),
                jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.7.3'),
                os.environ.get('XBOARD_TEST_JSON_JAR', '/tmp/opencode/android-all.jar')]
        (cls.output / 'Fakes.kt').write_text('''package app.invilink.xboard.ssh
data class SshCommandResult(val exitCode: Int, val stdout: String, val stderr: String = "")
fun shellQuote(value: String) = "'" + value.replace("'", "'\\\"'\\\"'") + "'"
class SshConnection(val events: MutableList<String>, val terminal: String = "FAILED", val failConnect: Boolean = false) {
    var inputs = 0
    var submitted = ""
    suspend fun connect(credentials: SshCredentials): SshHostIdentity {
        events.add("connect")
        if (failConnect) throw MaintenanceException("connect failed")
        return SshHostIdentity(credentials.host, 22, "ssh-ed25519", "key", "fingerprint")
    }
    fun close() {}
    suspend fun preflight(): ServerPreflight { events.add("preflight"); return ServerPreflight("Debian", "amd64", emptyList(), true, true, existingInstallation=true) }
    suspend fun execute(command: String, timeoutMillis: Long, maxBytes: Int) = SshCommandResult(0, "READY\\n")
    suspend fun executeInput(command: String, bytes: ByteArray, timeoutMillis: Long, maxBytes: Int): SshCommandResult {
        inputs++
        if (inputs == 1) {
            events.add("launch")
            submitted = bytes.toString(Charsets.UTF_8)
        }
        val data = if (inputs == 1 && terminal.startsWith("START")) "{\\"status\\":\\"FAILED\\",\\"phase\\":\\"QUEUED\\",\\"operation\\":\\"REINSTALL\\",\\"reason\\":\\"LAUNCH\\",\\"safeToRetry\\":" + (terminal == "STARTSAFE") + "}" else if (inputs == 1) "{\\"status\\":\\"STARTED\\",\\"phase\\":\\"QUEUED\\",\\"operation\\":\\"REINSTALL\\"}" else if (terminal == "SUCCEEDED" && inputs <= 5) "{\\"status\\":\\"RUNNING\\",\\"phase\\":\\"" + listOf("QUEUED", "DOWNLOAD", "VERIFY", "COMPLETE")[inputs - 2] + "\\",\\"operation\\":\\"REINSTALL\\"}" else when (terminal) {
            "SUCCEEDED" -> "{\\"status\\":\\"SUCCEEDED\\",\\"phase\\":\\"COMPLETE\\",\\"operation\\":\\"REINSTALL\\",\\"source\\":\\"3.11.0\\",\\"target\\":\\"3.11.0\\",\\"active\\":false,\\"runtimeVerified\\":false}"
            "UNKNOWN" -> "{\\"status\\":\\"FAILED\\",\\"phase\\":\\"INVALID\\",\\"operation\\":\\"REINSTALL\\"}"
            else -> "{\\"status\\":\\"FAILED\\",\\"phase\\":\\"QUEUED\\",\\"operation\\":\\"REINSTALL\\",\\"reason\\":\\"RUNNER\\",\\"safeToRetry\\":false}"
        }
        return SshCommandResult(0, data)
    }
}
''')
        (cls.output / 'Harness.kt').write_text('''package app.invilink.xboard.ssh
import java.io.File
import kotlinx.coroutines.runBlocking
fun main(args: Array<String>) = runBlocking {
    for ((name, data) in mapOf("LAUNCH" to ThreeXMaintenance.LAUNCH, "POLL" to ThreeXMaintenance.POLL, "RUNNER" to ThreeXMaintenance.RUNNER)) File(args[0], name).writeText(data)
    check(listOf("QUEUED", "DOWNLOAD", "LAYOUT", "BACKUP", "STAGE", "SWAP", "VERIFY", "COMPLETE").map(ThreeXMaintenance::progressForPhase) == listOf(3, 12, 35, 48, 62, 78, 90, 99))
    val credentials = SshCredentials(host="192.0.2.1", password="fixture")
    val identity = SshHostIdentity(credentials.host, 22, "ssh-ed25519", "key", "fingerprint")
    for (terminal in listOf("FAILED", "SUCCEEDED", "UNKNOWN", "CONNECT", "STARTSAFE", "STARTUNSAFE")) {
        val events = mutableListOf<String>()
        val connection = SshConnection(events, terminal, terminal == "CONNECT")
        val maintenance = ThreeXMaintenance(connection)
        val progress = mutableListOf<InstallProgress>()
        try {
            maintenance.run(credentials, identity, InstallOperation.REINSTALL, false) {
                progress.add(it); events.add("progress:" + it.percent)
            }
            check(terminal == "SUCCEEDED")
        } catch (e: MaintenanceException) {
            check(terminal != "SUCCEEDED")
            check(e.message!!.isNotBlank())
        }
        check(events.take(2) == listOf("progress:3", "connect"))
        if (terminal != "CONNECT") check(events.take(6) == listOf("progress:3", "connect", "progress:7", "preflight", "progress:12", "launch"))
        check(progress.none { it.percent == 0 })
        check(progress.zipWithNext().all { (before, after) -> before.percent <= after.percent })
        check(progress.any { it.percent == 100 } == (terminal == "SUCCEEDED"))
        if (terminal == "FAILED") check(maintenance.pendingJobId != null && connection.inputs == 2)
        if (terminal.startsWith("START")) {
            check(connection.inputs == 1)
            check((maintenance.pendingJobId == null) == (terminal == "STARTSAFE"))
        }
        if (terminal == "SUCCEEDED") {
            check(progress.map { it.percent } == listOf(3, 7, 12, 12, 12, 90, 99, 100))
            check(!progress.last().running && maintenance.pendingJobId == null)
            File(args[0], "SUBMITTED").writeText(connection.submitted)
        }
    }
    println("Production maintenance Kotlin compilation, callbacks and milestone mapping passed")
}
''')
        target = cls.output / 'producer.jar'
        subprocess.run(['java', '-cp', os.pathsep.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                        '-no-stdlib', '-no-reflect', '-classpath', os.pathsep.join(deps), '-d', str(target),
                        str(SOURCE / 'ThreeXMaintenance.kt'), str(SOURCE / 'ServerModels.kt'),
                        str(cls.output / 'Fakes.kt'), str(cls.output / 'Harness.kt')], check=True)
        subprocess.run(['java', '-cp', os.pathsep.join([str(target)] + deps),
                        'app.invilink.xboard.ssh.HarnessKt', str(cls.output)], check=True)
        cls.producer = {name: (cls.output / name).read_text() for name in ('LAUNCH', 'POLL', 'RUNNER')}
        for name, code in cls.producer.items():
            compile(code, name, 'exec')
        transport = '\n'.join((cls.output / 'SUBMITTED').read_text().splitlines()[3:-1])
        tree = ast.parse(transport)
        compile(tree, 'actual launch transport', 'exec')
        value = next(node.value for node in tree.body if isinstance(node, ast.Assign)
                     and any(isinstance(target, ast.Name) and target.id == 'PAYLOAD' for target in node.targets))
        payload = eval(compile(ast.Expression(value), 'payload expression', 'eval'), {'base64': base64})
        if not payload.endswith(cls.producer['RUNNER']):
            raise AssertionError('Submitted runner was corrupted in transport')
        compile(payload, 'actual submitted runner', 'exec')

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def fixture(self, **kwargs):
        temporary = tempfile.TemporaryDirectory(prefix='maintenance-flow-', dir='/tmp/opencode')
        self.addCleanup(temporary.cleanup)
        return Fixture(Path(temporary.name), self.producer, **kwargs)

    def test_supported_official_metadata_and_root_full_producer(self):
        for uid in (0, 1001):
            for version in PINS:
                for operation in ('REINSTALL', 'UPDATE'):
                    for active in (False, True):
                        with self.subTest(uid=uid, version=version, operation=operation, active=active):
                            fixture = self.fixture(uid=uid, version=version, operation=operation, active=active)
                            self.assertEqual(fixture.launch()['status'], 'STARTED')
                            result = fixture.execute('RUNNER')
                            self.assertEqual(result['status'], 'SUCCEEDED', result)
                            self.assertEqual(result['target'], version if operation == 'REINSTALL' else '3.11.0')
                            self.assertEqual(result['runtimeVerified'], active)
                            self.assertEqual([s['phase'] for s in fixture.phases],
                                             ['QUEUED', 'DOWNLOAD', 'LAYOUT', 'BACKUP', 'STAGE', 'SWAP', 'VERIFY', 'COMPLETE'])
                            self.assertTrue(all(s['operation'] == operation for s in fixture.phases))
                            self.assertEqual(fixture.db.read_bytes(), fixture.original_db)
                            self.assertEqual((fixture.live / 'bin/config.json').read_bytes(), b'{"original":"runtime configuration"}')
                            self.assertEqual(fixture.path('/root/cert/server.key').read_bytes(), b'private original key')
                            for base in (fixture.live, fixture.job / 'backup/installation'):
                                self.assertEqual(fixture.owners[str(base)], (uid, uid))
                                self.assertEqual(fixture.owners[str(base / 'x-ui')], (uid, uid))
                            self.assertTrue((fixture.job / 'backup/snapshot.db').is_file())
                            self.assertTrue(fixture.path('/usr/local/.xboard-old-' + JOB_ID).is_dir())
                            self.assertEqual(fixture.execute('POLL')['status'], 'SUCCEEDED')
                            self.assertFalse(any(Path(c[0]).name not in ('curl', 'systemctl') for c in fixture.calls))

    def test_arm64_archive_and_producer(self):
        fixture = self.fixture(arch='arm64')
        fixture.launch()
        self.assertEqual(fixture.execute('RUNNER')['status'], 'SUCCEEDED')

    def test_strict_protected_paths_and_identity(self):
        for case in ('parent_owner', 'parent_mode', 'program_owner', 'program_mode', 'program_content',
                     'program_symlink', 'program_hardlink', 'db_owner', 'cert_owner', 'job_owner', 'mount', 'unit'):
            with self.subTest(case=case):
                fixture = self.fixture()
                fixture.launch()
                if case.endswith('_owner'):
                    target = {'parent_owner': fixture.path('/usr/local'), 'program_owner': fixture.live / 'x-ui',
                              'db_owner': fixture.db, 'cert_owner': fixture.path('/root/cert/server.key'),
                              'job_owner': fixture.job}[case]
                    fixture.owners[str(target)] = (1001, 1001) if case != 'program_owner' else (1002, 1002)
                elif case == 'parent_mode':
                    fixture.path('/usr/local').chmod(0o775)
                elif case == 'program_mode':
                    (fixture.live / 'x-ui').chmod(0o644)
                elif case == 'program_content':
                    (fixture.live / 'x-ui').write_bytes(b'unknown fork')
                elif case == 'program_symlink':
                    (fixture.live / 'x-ui').unlink()
                    (fixture.live / 'x-ui').symlink_to(fixture.path('/root/cert/server.key'))
                elif case == 'program_hardlink':
                    os.link(fixture.live / 'x-ui', fixture.path('/root/foreign'))
                elif case == 'mount':
                    fixture.path('/proc/self/mountinfo').write_text('1 0 0:1 / ' + str(fixture.live / 'bin') + ' rw - ext4 fixture rw\n')
                elif case == 'unit':
                    fixture.path('/etc/systemd/system/x-ui.service').write_bytes(b'[Service]\nExecStart=/bin/false\n')
                if case == 'job_owner':
                    with self.assertRaises(RuntimeError):
                        fixture.execute('RUNNER')
                else:
                    result = fixture.execute('RUNNER')
                    self.assertEqual(result['status'], 'FAILED', result)
                    self.assertNotIn('SWAP', [s['phase'] for s in fixture.phases])
                self.assertEqual(fixture.db.read_bytes(), fixture.original_db)
                self.assertFalse(any(c[1] in ('stop', 'start') for c in fixture.calls))

    def test_payload_syntax_failure_is_terminal_before_dispatch(self):
        fixture = self.fixture()
        receipt = fixture.launch('not valid Python :')
        self.assertEqual(receipt['status'], 'FAILED')
        self.assertTrue(receipt['safeToRetry'])
        state = fixture.execute('POLL')
        self.assertEqual((state['phase'], state['status'], state['operation'], state['reason']),
                         ('QUEUED', 'FAILED', 'REINSTALL', 'LAUNCH'))
        self.assertFalse((fixture.job / 'runner').exists())

    def test_spawn_exception_writes_terminal_state_without_retry(self):
        fixture = self.fixture()
        fixture.spawn_error = True
        receipt = fixture.launch()
        state = fixture.execute('POLL')
        self.assertEqual((receipt['status'], receipt['phase'], receipt['operation']), ('FAILED', 'QUEUED', 'REINSTALL'))
        self.assertEqual(state['status'], 'FAILED')
        self.assertEqual(state['reason'], 'LAUNCH')
        self.assertFalse(state['safeToRetry'])
        self.assertFalse((fixture.job / 'runner').exists())
        self.assertTrue(fixture.path('/root/.xboard-maintenance/active').is_dir())
        self.assertEqual(fixture.db.read_bytes(), fixture.original_db)

    def test_dead_zombie_and_reused_pid_are_terminal_without_mutations(self):
        for case in ('dead', 'zombie', 'reused', 'wrong_command', 'swap'):
            with self.subTest(case=case):
                fixture = self.fixture()
                receipt = fixture.launch()
                self.assertEqual((receipt['status'], receipt['phase'], receipt['operation']), ('STARTED', 'QUEUED', 'REINSTALL'))
                if case in ('dead', 'swap'):
                    fixture.path('/proc/999/stat').unlink()
                elif case == 'zombie':
                    target = fixture.path('/proc/999/stat')
                    target.write_text(target.read_text().replace(') S ', ') Z '))
                elif case == 'reused':
                    target = fixture.path('/proc/999/stat')
                    target.write_text(target.read_text().replace('12345', '67890'))
                else:
                    fixture.path('/proc/999/cmdline').write_bytes(b'unrelated process\0')
                if case == 'swap':
                    state = fixture.job / 'state'
                    data = json.loads(state.read_text())
                    state.write_text(json.dumps(dict(data, phase='SWAP')))
                before = (fixture.job / 'state').read_bytes()
                result = fixture.execute('POLL')
                self.assertEqual(result['status'], 'FAILED')
                self.assertEqual(result['reason'], 'RUNNER')
                self.assertFalse(result['safeToRetry'])
                self.assertEqual((fixture.job / 'state').read_bytes(), before)
                self.assertEqual(fixture.db.read_bytes(), fixture.original_db)
                self.assertEqual(fixture.calls, [])
                self.assertTrue(fixture.path('/root/.xboard-maintenance/active').is_dir())

    def test_queued_deadline_and_missing_runner_record(self):
        for missing in (False, True):
            fixture = self.fixture()
            fixture.launch()
            if missing:
                (fixture.job / 'runner').unlink()
            os.utime(fixture.job / 'state', (time.time() - 65, time.time() - 65))
            result = fixture.execute('POLL')
            self.assertEqual(result['status'], 'FAILED')
            self.assertEqual(result['reason'], 'RUNNER' if missing else 'STALLED')
            self.assertFalse(result['safeToRetry'])

    def test_swap_failure_restores_original_ownership_without_overwriting_data(self):
        for active in (False, True):
            with self.subTest(active=active):
                fixture = self.fixture(active=active, version='3.10.0', operation='UPDATE')
                fixture.launch()
                rename = fixture.real_rename
                failed = False

                def fail_swap(source, target):
                    nonlocal failed
                    if str(source).endswith('/.xboard-stage-' + JOB_ID) and not failed:
                        failed = True
                        raise OSError('injected swap failure')
                    return rename(source, target)

                fixture.real_rename = fail_swap
                result = fixture.execute('RUNNER')
                self.assertEqual((result['status'], result['phase'], result['reason']), ('FAILED', 'SWAP', 'RESTORED'))
                self.assertFalse(result['safeToRetry'])
                self.assertEqual(fixture.active, active)
                self.assertEqual((fixture.live / 'x-ui').read_bytes(), b'fixture binary 3.10.0')
                self.assertEqual(fixture.owners[str(fixture.live)], (1001, 1001))
                self.assertEqual(fixture.owners[str(fixture.live / 'x-ui')], (1001, 1001))
                self.assertEqual(fixture.db.read_bytes(), fixture.original_db)
                self.assertTrue((fixture.job / 'backup/installation/x-ui').is_file())

    def test_concurrent_data_change_is_never_overwritten(self):
        for resource in ('database', 'certificate'):
            with self.subTest(resource=resource):
                fixture = self.fixture()
                fixture.launch()
                replace = fixture.real_replace
                changed = False

                def change_before_stage(source, target):
                    nonlocal changed
                    result = replace(source, target)
                    if not changed and str(target) == str(fixture.job / 'state') and json.loads(Path(target).read_text())['phase'] == 'STAGE':
                        changed = True
                        if resource == 'database':
                            with contextlib.closing(sqlite3.connect(fixture.db)) as db, db:
                                db.execute('INSERT INTO extra VALUES (?, 99)', (b'external change',))
                        else:
                            fixture.path('/root/cert/server.key').write_bytes(b'external key change')
                    return result

                fixture.real_replace = change_before_stage
                result = fixture.execute('RUNNER')
                self.assertTrue(changed)
                self.assertEqual(result['status'], 'FAILED')
                self.assertNotIn('SWAP', [state['phase'] for state in fixture.phases])
                if resource == 'database':
                    with contextlib.closing(sqlite3.connect(fixture.db)) as db:
                        self.assertEqual(db.execute('SELECT count FROM extra ORDER BY count').fetchall(), [(42,), (99,)])
                else:
                    self.assertEqual(fixture.path('/root/cert/server.key').read_bytes(), b'external key change')
                self.assertTrue((fixture.job / 'backup/snapshot.db').is_file())

    def test_live_runner_and_terminal_state_are_not_reset(self):
        fixture = self.fixture()
        fixture.launch()
        self.assertEqual(fixture.execute('POLL')['status'], 'RUNNING')
        result = fixture.execute('RUNNER')
        fixture.path('/proc/999/stat').unlink()
        self.assertEqual(fixture.execute('POLL'), result)


if __name__ == '__main__':
    unittest.main()
