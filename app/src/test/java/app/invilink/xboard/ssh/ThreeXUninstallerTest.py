import ast
import base64
import contextlib
import fcntl
import grp
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import signal
import sqlite3
import stat
import subprocess
import sys
import tarfile
import tempfile
import textwrap
import types
import unittest
from urllib.parse import quote
from unittest.mock import patch


HERE = Path(__file__).resolve().parent
SOURCE = HERE.parents[5] / 'main/java/app/invilink/xboard/ssh/ThreeXUninstaller.kt'
PANEL_ID = '01234567-89ab-cdef-0123-456789abcdef'
PIN = '95cb6e39499601148404cadf3b4d75b0489301ff7e7ab565cd9db21d23be957c'
UNIT_FIXTURE = b'[Unit]\nDescription=isolated fixture\n[Service]\nExecStart=/usr/local/x-ui/x-ui\n[Install]\nWantedBy=multi-user.target\n'
ARCHIVE_PINS = ('0ddfa4702f595442e2962acd81addc768fdacaa4ff2672c971a0463dae744916',
                'f50b4c30fd919d018f2f4c3d8db53a4ca027503d581328727770afb116a79d90')
ARCHIVE_FIXTURE = b'pinned release fixture, never extracted'
ADDITIONAL = ('/etc/default/x-ui', '/etc/sysconfig/x-ui', '/var/log/x-ui',
              '/usr/local/x-ui-linux-amd64.tar.gz', '/usr/local/x-ui-linux-arm64.tar.gz')
# Group names are mocked: fixtures must not depend on the host's Ubuntu group IDs.
PARENT_GROUPS = {0: 'root', 2001: 'staff', 2002: 'adm', 2003: 'syslog', 2004: 'other'}
SAFE_PARENTS = {
    'parent_staff_local': ('/usr/local', 0o2775, 0, 2001),
    'parent_adm_var': ('/var', 0o775, 0, 2002),
    'parent_syslog_var': ('/var', 0o775, 0, 2003),
    'parent_adm_log': ('/var/log', 0o2775, 0, 2002),
    'parent_syslog_log': ('/var/log', 0o775, 0, 2003),
    'parent_readonly_default': ('/etc/default', 0o755, 0, 2002),
    'parent_readonly_sysconfig': ('/etc/sysconfig', 0o755, 0, 2001),
    'parent_readonly_unknown': ('/usr/bin', 0o755, 0, 2999),
}
UNSAFE_PARENTS = {
    'parent_staff_local_link': ('/usr/local', 0o2775, 0, 2001),
    'parent_adm_log_link': ('/var/log', 0o775, 0, 2002),
    'parent_root_local': ('/usr/local', 0o775, 0, 0),
    'parent_other_local': ('/usr/local', 0o775, 0, 2004),
    'parent_adm_local': ('/usr/local', 0o775, 0, 2002),
    'parent_staff_var': ('/var', 0o775, 0, 2001),
    'parent_root_log': ('/var/log', 0o775, 0, 0),
    'parent_staff_log': ('/var/log', 0o775, 0, 2001),
    'parent_unknown_log': ('/var/log', 0o775, 0, 2999),
    'parent_staff_bin': ('/usr/bin', 0o775, 0, 2001),
    'parent_adm_default': ('/etc/default', 0o775, 0, 2002),
    'parent_syslog_sysconfig': ('/etc/sysconfig', 0o775, 0, 2003),
    'parent_root_systemd': ('/etc/systemd/system', 0o775, 0, 0),
    'parent_world_local': ('/usr/local', 0o777, 0, 2001),
    'parent_world_var': ('/var', 0o777, 0, 2002),
    'parent_world_log': ('/var/log', 0o777, 0, 2003),
    'parent_nonroot_local': ('/usr/local', 0o775, 1001, 2001),
    'parent_nonroot_log': ('/var/log', 0o755, 1001, 2002),
}
PARENT_FIXTURES = {**SAFE_PARENTS, **UNSAFE_PARENTS}


def producer():
    return textwrap.dedent(SOURCE.read_text().split('val PYTHON = """', 1)[1].split('""".trimIndent()', 1)[0]).strip()


class Paths(ast.NodeTransformer):
    def __init__(self, root):
        self.root = str(root)

    def visit_Constant(self, node):
        if isinstance(node.value, str):
            if any(node.value == prefix or node.value.startswith(prefix + '/')
                   for prefix in ('/root', '/usr', '/etc', '/run', '/lib', '/proc', '/var')):
                node.value = self.root + node.value
            elif node.value == PIN:
                node.value = hashlib.sha256(UNIT_FIXTURE).hexdigest()
            elif node.value in ARCHIVE_PINS:
                node.value = hashlib.sha256(ARCHIVE_FIXTURE).hexdigest()
        return node


class FixtureStat:
    def __init__(self, original, uid=0, gid=0):
        self.original = original
        self.st_uid = uid
        self.st_gid = gid

    def __getattr__(self, name):
        return getattr(self.original, name)

    def __getitem__(self, index):
        return {4: self.st_uid, 5: self.st_gid}.get(index, self.original[index])


def worker(case):
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix='uninstaller-3-', dir='/tmp/opencode') as temporary:
        root = Path(temporary)

        def path(value):
            return root / value.lstrip('/')

        def write(value, content=b'', mode=0o600):
            target = path(value)
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            target.write_bytes(content)
            target.chmod(mode)
            return target

        for directory in ('root', 'usr/local/x-ui/bin', 'usr/bin', 'etc/x-ui', 'etc/systemd/system', 'proc/self'):
            path(directory).mkdir(parents=True, exist_ok=True, mode=0o700)
        job = '/root/.xboard-install/' + PANEL_ID
        write(job + '/status', b'SUCCEEDED\n')
        write(job + '/phase', b'COMPLETE\n')
        started = write(job + '/started')
        os.utime(started, ns=(1000000000, 1000000000))
        write(job + '/install.sh', b'private installer credentials')
        write(job + '/verified.sh', b'private verifier credentials')
        write(job + '/install-result.env', b'old-password old-token')
        write(job + '/cookies', b'private login cookie')
        write(job + '/verify-abc123.cookies', b'private verification cookie')
        path('/root/.xboard-install/active').mkdir(mode=0o700)
        write('/root/.xboard-install/active/job', (str(path(job)) + '\n').encode())
        write('/root/.xboard-install/active/pid', b'999\n')
        write('/etc/default/x-ui', b'# sqlite only\nXUI_DB_TYPE=sqlite\n', 0o644)
        write('/etc/sysconfig/x-ui', b'; sqlite only\n  XUI_DB_TYPE = "sqlite"  \n', 0o644)
        for arch in ('amd64', 'arm64'):
            write('/usr/local/x-ui-linux-' + arch + '.tar.gz', ARCHIVE_FIXTURE, 0o644)
        write('/var/log/x-ui/access.log', b'panel logs')
        write('/var/log/x-ui/nested/error.log', b'panel errors')
        expected = {'panelId': PANEL_ID, 'port': '2053', 'path': 'selected', 'url': 'https://panel.invalid:2053/selected/'}
        values = {'XUI_USERNAME': 'old-user', 'XUI_PASSWORD': 'old-password', 'XUI_API_TOKEN': 'old-token',
                  'XUI_DB_TYPE': 'sqlite', 'XUI_PANEL_PORT': '2053', 'XUI_WEB_BASE_PATH': '/selected/',
                  'XUI_ACCESS_URL': expected['url']}
        result = 'XBOARD_RESULT_V1\n' + ''.join(k + '\t' + base64.b64encode(v.encode()).decode() + '\n'
                                                  for k, v in values.items()) + 'END\n'
        write(job + '/result', result.encode())
        write(job + '/result.next', result.encode())
        unit = write('/etc/systemd/system/x-ui.service', UNIT_FIXTURE, 0o644)
        cli = write('/usr/bin/x-ui', b'fixture cli not executed', 0o755)
        binary = write('/usr/local/x-ui/x-ui', b'fixture executable not executed', 0o755)
        write('/usr/local/x-ui/bin/xray', b'owned xray not executed', 0o755)
        asset = write('/usr/local/x-ui/user-assets/custom.json', b'panel user asset')
        foreign = {
            '/root/cert/shared.pem': b'shared certificate',
            '/root/.acme.sh/account.key': b'shared acme private key',
            '/etc/nginx/nginx.conf': b'foreign nginx',
            '/usr/local/xray/xray': b'foreign standalone xray',
            '/var/log/nginx/access.log': b'foreign nginx logs',
            '/usr/local/x-ui-linux-unknown.tar.gz': b'unknown archive must survive',
            '/root/.xboard-maintenance/old-history/snapshot': b'maintenance backup must survive',
        }
        for name, data in foreign.items():
            write(name, data)
        path('/etc/x-ui/cert-link').symlink_to(path('/root/cert/shared.pem'))
        path('/etc/x-ui/dangling').symlink_to(path('/root/nonexistent'))
        path('/usr/local/x-ui/outside-link').symlink_to(path('/etc/nginx'), target_is_directory=True)
        path('/var/log/x-ui/external-directory').symlink_to(path('/var/log/nginx'), target_is_directory=True)
        path('/var/log/x-ui/external-certificate').symlink_to(path('/root/cert/shared.pem'))
        db = path('/etc/x-ui/x-ui.db')
        with sqlite3.connect(db) as con:
            con.execute('CREATE TABLE settings (key TEXT, value TEXT)')
            con.executemany('INSERT INTO settings VALUES (?, ?)', [('webPort', '2053'), ('webBasePath', '/selected/')])
            con.execute('CREATE TABLE clients (data TEXT)')
            con.execute('INSERT INTO clients VALUES (?)', ('all clients recovery data',))
        db.chmod(0o600)
        wal_connection = None
        if case == 'wal':
            wal_connection = sqlite3.connect(db)
            wal_connection.execute('PRAGMA journal_mode=WAL')
            wal_connection.execute('UPDATE settings SET value=value')
            wal_connection.commit()
        write('/proc/self/mountinfo', b'1 0 0:1 / / rw - ext4 fixture rw\n')
        active = case != 'inactive'
        if active:
            write('/proc/100/cgroup', b'0::/system.slice/x-ui.service\n')
            write('/proc/100/environ', b'XUI_DB_TYPE=sqlite\0')
            path('/proc/100/exe').symlink_to(binary)
        enable = path('/etc/systemd/system/multi-user.target.wants/x-ui.service')
        enable.parent.mkdir(mode=0o700)
        enable.symlink_to(unit)
        drop = '/etc/systemd/system/x-ui.service.d/10-xui-sandbox.conf'
        if case == 'dropin' or case.startswith(('fault_', 'resume_')):
            write(drop, b'[Service]\nReadWritePaths=-/etc/x-ui -/var/log/x-ui -/usr/local/x-ui/bin -/usr/local/x-ui\nReadWriteDirectories=-/etc/x-ui -/var/log/x-ui -/usr/local/x-ui/bin -/usr/local/x-ui\nSystemCallFilter=@system-service\nSystemCallErrorNumber=EPERM\n', 0o644)
        state = {'active': active, 'disabled': False, 'reloaded': False}
        calls = []
        props = {
            'FragmentPath': str(unit), 'DropInPaths': str(path(drop)) if path(drop).exists() else '',
            'LoadState': 'loaded', 'NeedDaemonReload': 'no', 'User': 'root', 'Group': 'root',
            'WorkingDirectory': str(path('/usr/local/x-ui')), 'KillMode': 'control-group',
            'Environment': 'XRAY_VMESS_AEAD_FORCED=false', 'EnvironmentFiles': '',
            'ExecStart': '{ path=' + str(binary) + ' ; argv[]=' + str(binary) + ' ; ignore_errors=no ; }',
        }
        if case in ('default_env_service', 'sysconfig_env_service'):
            props['EnvironmentFiles'] = str(path('/etc/default/x-ui' if case == 'default_env_service'
                                                 else '/etc/sysconfig/x-ui')) + ' (ignore_errors=yes)'
        if case == 'empty_environments':
            write('/etc/default/x-ui', b'# no overrides\n')
            write('/etc/sysconfig/x-ui', b'\n')
        if case == 'no_additional_assets':
            for name in ADDITIONAL:
                if path(name).is_dir():
                    shutil.rmtree(path(name))
                else:
                    path(name).unlink()
        if case == 'missing_unused_parents':
            for name in ('/etc/default/x-ui', '/etc/sysconfig/x-ui'):
                path(name).unlink()
                path(name).parent.rmdir()
            shutil.rmtree(path('/var/log/x-ui'))
            del foreign['/var/log/nginx/access.log']
            shutil.rmtree(path('/var/log/nginx'))
            path('/var/log').rmdir()
            path('/var').rmdir()
        if case == 'usrmerge':
            for alias, destination in (('/bin', '/usr/bin'), ('/sbin', '/usr/sbin'), ('/lib', '/usr/lib')):
                path(destination).mkdir(parents=True, exist_ok=True, mode=0o755)
                path(alias).symlink_to(path(destination), target_is_directory=True)
        if case in ('port', 'dbpath'):
            with sqlite3.connect(db) as con:
                con.execute('UPDATE settings SET value=? WHERE key=?',
                            ('9999', 'webPort') if case == 'port' else ('/other/', 'webBasePath'))
        if case in ('hardlink', 'hardlink_binary', 'hardlink_db'):
            source = {'hardlink': asset, 'hardlink_binary': binary, 'hardlink_db': db}[case]
            os.link(source, path('/root/external-hardlink'))
            foreign['/root/external-hardlink'] = source.read_bytes()
        if case == 'file_modes':
            binary.chmod(0o644)
            asset.chmod(0o666)
        if case == 'groupwrite':
            for directory in ('/usr/local/x-ui', '/usr/local/x-ui/user-assets', '/etc/x-ui'):
                path(directory).chmod(0o775)
            for item in (asset, binary, db):
                item.chmod(0o776)
        if case == 'archive_uid':
            archive_data = io.BytesIO()
            with tarfile.open(fileobj=archive_data, mode='w') as archive:
                for name, data in (('x-ui', None), ('x-ui/x-ui', binary.read_bytes()), ('x-ui/bin', None)):
                    member = tarfile.TarInfo(name)
                    member.uid = member.gid = 1001
                    member.mode = 0o755
                    member.type = tarfile.DIRTYPE if data is None else tarfile.REGTYPE
                    member.size = len(data or b'')
                    archive.addfile(member, None if data is None else io.BytesIO(data))
            archive_data.seek(0)
            with tarfile.open(fileobj=archive_data) as archive:
                for member in archive:
                    target = path('/usr/local/' + member.name)
                    if member.isfile():
                        target.write_bytes(archive.extractfile(member).read())
                    target.chmod(member.mode)
        if case in ('mount', 'root_mount', 'cli_mount', 'job_mount'):
            mount = {'mount': '/usr/local/x-ui/user-assets', 'root_mount': '/etc/x-ui',
                     'cli_mount': '/usr/bin/x-ui', 'job_mount': job}[case]
            write('/proc/self/mountinfo', ('1 0 0:1 / ' + str(path(mount)) + ' rw - ext4 fixture rw\n').encode())
        if case in ('parentlink', 'tree_link'):
            original = path('/usr/local' if case == 'parentlink' else '/usr/local/x-ui')
            moved = original.with_name(original.name + '-moved')
            original.rename(moved)
            original.symlink_to(moved, target_is_directory=True)
        if case in PARENT_FIXTURES:
            parent, mode, _, _ = PARENT_FIXTURES[case]
            path(parent).chmod(mode)
            if case.endswith('_link'):
                original = path(parent)
                moved = original.with_name(original.name + '-external')
                original.rename(moved)
                original.symlink_to(moved, target_is_directory=True)
        if case == 'parent_mode':
            path('/usr/local').chmod(0o775)
        if case == 'dblink':
            db.rename(path('/root/foreign.db'))
            db.symlink_to(path('/root/foreign.db'))
        if case == 'wal_link':
            path('/etc/x-ui/x-ui.db-wal').symlink_to(path('/root/cert/shared.pem'))
        if case == 'standard_service_variants':
            unit.write_bytes(UNIT_FIXTURE + b'\n# ordinary service revision\n')
            props['WorkingDirectory'] = str(path('/usr/local/x-ui')) + '/'
            props['Environment'] = 'XRAY_VMESS_AEAD_FORCED=false XUI_LOG_LEVEL=info'
            write(drop, b'[Service]\nReadWritePaths=-/etc/x-ui -/usr/local/x-ui -/var/log/x-ui\n')
            extra_drop = '/etc/systemd/system/x-ui.service.d/20-local.conf'
            write(extra_drop, b'[Service]\nRestartSec=10\n')
            props['DropInPaths'] = str(path(drop)) + ' ' + str(path(extra_drop))
        if case == 'stop_hook':
            props['ExecStop'] = '{ path=/bin/sh ; argv[]=/bin/sh -c arbitrary ; }'
        if case == 'start_args':
            props['ExecStart'] = props['ExecStart'].replace(' ; ignore_errors', ' --db /other ; ignore_errors')
        if case == 'foreign_db_env':
            write('/proc/100/environ', b'XUI_DB_FOLDER=/foreign\0')
        if case == 'stop_dependency':
            props['ConsistsOf'] = 'nginx.service'
        if case == 'job_missing':
            path(job + '/result').unlink()
        if case == 'job_wrong':
            write(job + '/result', result.replace('MjA1Mw==', 'OTk5OQ==').encode())
        if case == 'running':
            write(job + '/runner', b'running')
        if case == 'maintenance':
            write('/root/.xboard-maintenance/active/job', b'running')
        if case == 'attempted':
            write(job + '/uninstall-started', b'XBOARD_NATIVE_REMOVE_V1\n')
        if case == 'unsafe_unit':
            unit.write_bytes(b'[Install]\nAlso=nginx.service\n')
            props['ExecStart'] = ''
        if case == 'foreign_process':
            write('/proc/200/cgroup', b'0::/system.slice/foreign.service\n')
            path('/proc/200/exe').symlink_to(binary)
        if case == 'wrong_main':
            path('/proc/100/exe').unlink()
            path('/proc/100/exe').symlink_to(path('/usr/local/xray/xray'))
        if case == 'special_file':
            os.mkfifo(path('/usr/local/x-ui/pipe'), 0o600)
        if case in ('default_parent_link', 'log_parent_link', 'sysconfig_parent_link'):
            original = path({'default_parent_link': '/etc/default', 'log_parent_link': '/var/log',
                             'sysconfig_parent_link': '/etc/sysconfig'}[case])
            moved = original.with_name(original.name + '-external')
            original.rename(moved)
            original.symlink_to(moved, target_is_directory=True)
        if case in ('unsafe_default', 'unsafe_sysconfig'):
            write('/etc/default/x-ui' if case == 'unsafe_default' else '/etc/sysconfig/x-ui',
                  b'XUI_DB_TYPE=postgres\nXUI_DB_FOLDER=/foreign\n')
        if case == 'environment_link':
            path('/etc/default/x-ui').unlink()
            path('/etc/default/x-ui').symlink_to(path('/root/cert/shared.pem'))
        if case in ('log_link', 'log_dangling'):
            shutil.rmtree(path('/var/log/x-ui'))
            path('/var/log/x-ui').symlink_to(path('/var/log/nginx' if case == 'log_link' else '/root/absent'))
        if case == 'log_mount':
            write('/proc/self/mountinfo', ('1 0 0:1 / ' + str(path('/var/log/x-ui/nested')) + ' rw - ext4 fixture rw\n').encode())
        if case == 'archive_unknown':
            write('/usr/local/x-ui-linux-amd64.tar.gz', b'not a pinned archive')
        if case == 'archive_link':
            path('/usr/local/x-ui-linux-amd64.tar.gz').unlink()
            path('/usr/local/x-ui-linux-amd64.tar.gz').symlink_to(path('/root/cert/shared.pem'))
        if case in ('certificate_inside', 'certificate_extensionless', 'certificate_setting'):
            cert = '/etc/x-ui/' + ('old.pem' if case == 'certificate_inside' else 'tls-material')
            write(cert, b'-----BEGIN CERTIFICATE-----\nuntouched certificate')
            foreign[cert] = b'-----BEGIN CERTIFICATE-----\nuntouched certificate'
            if case == 'certificate_setting':
                with sqlite3.connect(db) as con:
                    con.execute('INSERT INTO settings VALUES (?, ?)', ('webCertFile', str(path(cert))))
        if case == 'external_ssl_settings':
            with sqlite3.connect(db) as con:
                con.executemany('INSERT INTO settings VALUES (?, ?)',
                                [('webCertFile', str(path('/root/cert/shared.pem'))),
                                 ('webKeyFile', str(path('/root/.acme.sh/account.key')))])
        if case in ('active_owned', 'active_other', 'active_pid_dead', 'active_pid_live', 'active_pid_no_owner'):
            if case != 'active_pid_no_owner':
                write('/root/.xboard-install/active/job', (PANEL_ID if case != 'active_other' else 'other').encode())
            if case.startswith('active_pid_'):
                write('/root/.xboard-install/active/pid', b'999')
                if case == 'active_pid_live':
                    path('/proc/999').mkdir(mode=0o700)
        if case == 'active_pid_no_owner':
            path('/root/.xboard-install/active/job').unlink()
        if case == 'active_no_pid':
            path('/root/.xboard-install/active/pid').unlink()
        if case in ('active_absent', 'active_legacy_empty'):
            path('/root/.xboard-install/active/job').unlink()
            path('/root/.xboard-install/active/pid').unlink()
            if case == 'active_absent':
                path('/root/.xboard-install/active').rmdir()
        if case in ('active_pid_reused', 'active_pid_reference', 'active_pid_lookalike',
                    'active_pid_selected', 'active_pid_other_job', 'active_pid_installer',
                    'active_pid_verifier', 'active_pid_certificate', 'active_pid_zombie'):
            script = {
                'active_pid_selected': str(path(job + '/runner')),
                'active_pid_other_job': str(path('/root/.xboard-install/fedcba98-7654-3210-fedc-ba9876543210/runner')),
                'active_pid_installer': str(path(job + '/install.sh')),
                'active_pid_verifier': str(path(job + '/verified.sh')),
                'active_pid_certificate': str(path(job + '/certificate-check.py')),
            }.get(case)
            command = (('/bin/bash\0' + script + '\0').encode() if script else
                       b'' if case == 'active_pid_zombie' else
                       ('/usr/bin/ls\0' + str(path(job)) + '\0').encode() if case == 'active_pid_reference' else
                       ('/usr/bin/printf\0' + str(path(job + '/runner-unrelated')) + '\0').encode() if case == 'active_pid_lookalike' else
                       b'/usr/bin/sleep\0' + b'3600\0')
            foreign['/proc/999/cmdline'] = command
            write('/proc/999/cmdline', command)
        if case == 'active_pid_malformed':
            write('/root/.xboard-install/active/pid', b'999\n123\n')
        if case in ('active_unknown_file', 'active_pid_launch', 'active_pid_next'):
            name = {'active_unknown_file': 'unknown', 'active_pid_launch': 'pid.launch', 'active_pid_next': 'pid.next'}[case]
            foreign['/root/.xboard-install/active/' + name] = b'unknown pending claim data'
            write('/root/.xboard-install/active/' + name, b'unknown pending claim data')
        if case == 'active_not_terminal':
            write(job + '/status', b'RUNNING\n')
        if case in ('active_job_mode', 'active_pid_mode'):
            path('/root/.xboard-install/active/' + ('job' if case == 'active_job_mode' else 'pid')).chmod(0o644)
        if case in ('active_job_link', 'active_pid_link', 'active_job_hardlink', 'active_pid_hardlink'):
            name = 'job' if case.startswith('active_job_') else 'pid'
            marker = path('/root/.xboard-install/active/' + name)
            data = marker.read_bytes()
            external = write('/root/foreign-claim-' + name, data)
            marker.unlink()
            if case.endswith('hardlink'):
                os.link(external, marker)
            else:
                marker.symlink_to(external)
            foreign['/root/foreign-claim-' + name] = data
        if case == 'active_directory_link':
            path('/root/.xboard-install/active').rename(path('/root/foreign-claim'))
            path('/root/.xboard-install/active').symlink_to(path('/root/foreign-claim'), target_is_directory=True)
            for name in ('job', 'pid'):
                foreign['/root/foreign-claim/' + name] = path('/root/foreign-claim/' + name).read_bytes()
        if case in ('active_other_runner', 'active_other_running', 'other_history'):
            other = '/root/.xboard-install/fedcba98-7654-3210-fedc-ba9876543210'
            write(other + '/status', b'RUNNING\n' if case == 'active_other_running' else b'FAILED\n')
            write(other + '/phase', b'INSTALL\n')
            if case == 'active_other_runner':
                write(other + '/runner', b'unknown running installer')
            else:
                foreign[other + '/status'] = path(other + '/status').read_bytes()
                foreign[other + '/phase'] = path(other + '/phase').read_bytes()
        if case == 'active_process':
            write('/proc/999/cmdline', (str(path(job + '/runner')) + '\0').encode())
        if case == 'active_process_no_claim':
            shutil.rmtree(path('/root/.xboard-install/active'))
            command = ('/bin/bash\0' + str(path(job + '/runner')) + '\0').encode()
            write('/proc/999/cmdline', command)
            foreign['/proc/999/cmdline'] = command
        if case in ('unsafe_env_comment', 'unsafe_env_spaced_comment', 'unsafe_env_export', 'unsafe_env_command'):
            write('/etc/default/x-ui', {
                'unsafe_env_comment': b'XUI_DB_TYPE=sqlite#not-a-comment\n',
                'unsafe_env_spaced_comment': b'XUI_DB_TYPE=sqlite # still part of the systemd value\n',
                'unsafe_env_export': b'export XUI_DB_TYPE=sqlite\n',
                'unsafe_env_command': b'XUI_DB_TYPE=$(touch /root/never-run)\n',
            }[case])
        mutations = []
        lock_order = []
        original_flock = fcntl.flock
        contender_fd = os.open(path('/root/.xboard-install'), os.O_RDONLY | os.O_DIRECTORY)
        root_contended = False
        executing = False
        held_fd = None
        if case in ('maintenance_lock_busy', 'job_lock_busy'):
            target = write('/root/.xboard-maintenance/lock' if case == 'maintenance_lock_busy' else job + '/uninstall-lock')
            held_fd = os.open(target, os.O_RDWR)
            original_flock(held_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)

        def assert_install_locked():
            try:
                original_flock(contender_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                return
            original_flock(contender_fd, fcntl.LOCK_UN)
            raise AssertionError('install directory must remain locked through inspection and mutation')

        def tracked_flock(fd, operation):
            current = os.fstat(fd)
            lock_order.append('install' if stat.S_ISDIR(current.st_mode) else
                              'maintenance' if os.path.exists(path('/root/.xboard-maintenance/lock')) and
                              current.st_ino == path('/root/.xboard-maintenance/lock').stat().st_ino else 'job')
            assert operation == fcntl.LOCK_EX | fcntl.LOCK_NB
            return original_flock(fd, operation)

        fault_at = int(case.split('_')[1]) if case.startswith('fault_') else None
        fault_enabled = True
        read_only = False
        mock_running = False

        def checkpoint(label):
            assert_install_locked()
            mutations.append(label)
            if fault_enabled and (len(mutations) == fault_at or
                                  case.startswith('resume_') and label == 'systemctl.stop'):
                raise OSError('simulated connection loss after mutation')

        clock = {'now': 0.0}

        def sleep(seconds):
            clock['now'] += seconds
            if case == 'delayed_stop' and not state['active'] and clock['now'] >= 1 and path('/proc/100').exists():
                shutil.rmtree(path('/proc/100'))

        def systemctl_fixture(argv, **kwargs):
            assert_install_locked()
            assert argv[0] == str(path('/usr/bin/systemctl')), argv
            args = argv[1:]
            calls.append(args)
            action = args[0]
            output = ''
            returncode = 0
            if action == 'show':
                if '--value' not in args:
                    assert args[1] == '--all', args
                    keys = args[-1].removeprefix('--property=').split(',')
                    names = args[2:-1]
                    if names == ['x-ui.service'] and not unit.exists() and case == 'show_timeout_reload':
                        clock['now'] += kwargs['timeout']
                        raise subprocess.TimeoutExpired(argv, kwargs['timeout'])
                    if case == 'absent_rc1_reordered':
                        keys.reverse()
                    records = []
                    for name in names:
                        fields = {}
                        for key in keys:
                            if name == 'x-ui.service':
                                if key == 'ActiveState':
                                    fields[key] = props.get('retry_active', 'active' if state['active'] else 'inactive')
                                else:
                                    fields[key] = props.get(key, '')
                                if case == 'delayed_reload' and not unit.exists() and clock['now'] - state.get('reload_at', 0) < 1:
                                    if key == 'LoadState':
                                        fields[key] = 'loaded'
                                if not unit.exists() and case in ('absent_rc1', 'absent_rc1_reordered', 'absent_bad_rc1', 'absent_rc2'):
                                    returncode = 2 if case == 'absent_rc2' else 1
                                    if case == 'absent_bad_rc1' and key == 'LoadState':
                                        fields[key] = 'error'
                            else:
                                fields[key] = name if key in ('Id', 'Names') else ''
                                if case in ('foreign', 'foreign_log') and name == 'nginx.service' and key == 'WorkingDirectory':
                                    fields[key] = str(path('/var/log/x-ui' if case == 'foreign_log' else '/usr/local/x-ui/user-assets'))
                                if case == 'foreign_environment' and name == 'nginx.service' and key == 'EnvironmentFiles':
                                    fields[key] = str(path('/etc/default/x-ui')) + ' (ignore_errors=yes)'
                        records.append('\n'.join(key + '=' + fields[key] for key in keys))
                    if case == 'batch_missing' and names != ['x-ui.service']:
                        records.pop()
                    if case == 'batch_duplicate' and names != ['x-ui.service']:
                        records[-1] = records[0]
                    output = '\n\n'.join(records)
                else:
                    name, key = args[1], args[2].removeprefix('--property=')
                    assert name == 'x-ui.service', args
                    if case == 'bad_show_rc1':
                        returncode = 1
                    if key == 'ActiveState' and not state['active'] and case == 'show_timeout_stop':
                        clock['now'] += kwargs['timeout']
                        raise subprocess.TimeoutExpired(argv, kwargs['timeout'])
                    if key == 'ActiveState':
                        output = props.get('retry_active', 'active' if state['active'] else 'inactive')
                        if case == 'delayed_stop' and not state['active'] and clock['now'] < 0.5:
                            output = 'deactivating'
                    elif key == 'ControlGroup':
                        output = '/system.slice/x-ui.service' if state['active'] else ''
                    elif key == 'MainPID':
                        output = '100' if state['active'] else '0'
                    elif key == 'UnitFileState':
                        output = 'disabled' if state['disabled'] else 'enabled'
                    else:
                        output = props.get(key, '')
            elif action in ('list-units', 'list-unit-files'):
                output = 'x-ui.service enabled\nnginx.service enabled\nxray-standalone.service enabled\ngetty@.service disabled'
                if case == 'many_services':
                    output += ''.join('\nfixture-' + str(index) + '.service enabled' for index in range(1000))
            elif action == 'cat':
                output = '[Service]\nExecStart=' + (str(binary) if case == 'foreign_template' else '/usr/bin/agetty')
            elif action == 'show-environment':
                output = 'XUI_DB_FOLDER=/foreign' if case == 'manager_env' else ''
            elif action == 'stop':
                assert args == ['stop', 'x-ui.service']
                assert not read_only
                if case == 'timeout':
                    raise subprocess.TimeoutExpired(argv, 45)
                state['active'] = False
                if wal_connection is not None:
                    wal_connection.close()
                if case not in ('residual', 'delayed_stop') and path('/proc/100').exists():
                    shutil.rmtree(path('/proc/100'))
                if case == 'changed':
                    unit.write_bytes(b'changed during stop')
                if case == 'archive_changed':
                    write('/usr/local/x-ui-linux-amd64.tar.gz', b'archive changed after initial hash validation')
                if case == 'db_checkpoint':
                    replacement = write('/etc/x-ui/checkpoint', db.read_bytes(), 0o640)
                    replacement.replace(db)
            elif action == 'disable':
                assert args == ['disable', '--no-reload', 'x-ui.service']
                assert not read_only
                state['disabled'] = True
                if enable.is_symlink():
                    enable.unlink()
            elif action == 'daemon-reload':
                assert not read_only
                state['reloaded'] = True
                state['reload_at'] = clock['now']
                props['LoadState'] = 'loaded' if unit.exists() or case == 'cached_unit' else 'not-found'
                props['FragmentPath'] = str(unit) if unit.exists() else ''
                props['DropInPaths'] = str(path(drop)) if path(drop).exists() else ''
                if not unit.exists() and case == 'additional_reappeared':
                    write('/etc/default/x-ui', b'XUI_DB_TYPE=sqlite\n')
                if not unit.exists() and case == 'process_reappeared':
                    write('/proc/200/cgroup', b'0::/system.slice/x-ui.service\n')
                    path('/proc/200/exe').symlink_to(binary)
            else:
                raise AssertionError(args)
            return types.SimpleNamespace(returncode=returncode, stdout=(output + '\n').encode())

        def systemctl(argv, **kwargs):
            nonlocal mock_running
            mock_running = True
            try:
                result = systemctl_fixture(argv, **kwargs)
            finally:
                mock_running = False
            if argv[1] in ('stop', 'disable', 'daemon-reload'):
                checkpoint('systemctl.' + argv[1])
            return result

        original_lstat = os.lstat
        nonroot_seen = []

        def fixture_stat(value, *args, **kwargs):
            if executing and str(value).startswith(str(path('/root/.xboard-install')) + '/'):
                assert_install_locked()
            original = original_lstat(value, *args, **kwargs)
            nonroot = case == 'archive_uid' and str(value).startswith(str(path('/usr/local/x-ui')))
            if case in ('active_job_owner', 'active_pid_owner'):
                marker = 'job' if case == 'active_job_owner' else 'pid'
                nonroot = str(value) == str(path('/root/.xboard-install/active/' + marker))
            if nonroot:
                nonroot_seen.append(str(value))
            if case in PARENT_FIXTURES:
                parent, _, uid, gid = PARENT_FIXTURES[case]
                if str(value) == str(path(parent)):
                    return FixtureStat(original, uid, gid)
            return FixtureStat(original, 1001 if nonroot else 0, 1001 if nonroot else 0)

        def fixture_group(gid):
            if gid not in PARENT_GROUPS:
                raise KeyError(gid)
            return types.SimpleNamespace(gr_name=PARENT_GROUPS[gid])

        tree = Paths(root).visit(ast.parse(producer()))
        ast.fix_missing_locations(tree)

        archive_reads = []

        def audit(event, args):
            if event == 'open' and str(args[0]) in {str(path(name)) for name in ADDITIONAL[-2:]} and not args[2] & (os.O_WRONLY | os.O_RDWR):
                archive_reads.append(str(args[0]))
            if event in ('subprocess.Popen', 'os.system', 'os.exec', 'os.posix_spawn'):
                raise AssertionError('real process execution prohibited')
            mutating = event in ('os.remove', 'os.rmdir', 'os.mkdir', 'os.rename', 'os.chmod', 'os.chown',
                                 'os.link', 'os.symlink', 'os.truncate', 'os.utime', 'os.kill', 'os.killpg')
            if event == 'open':
                mutating = bool(args[2] & (os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_TRUNC | os.O_APPEND))
            if read_only and mutating:
                raise AssertionError('completion retry must be read-only')
            if mutating or event == 'open':
                value = args[0]
                if isinstance(value, (str, bytes, os.PathLike)):
                    value = os.fsdecode(value)
                    if os.path.isabs(value):
                        assert value == str(root) or value.startswith(str(root) + '/'), (event, value)

        sys.addaudithook(audit)

        def wrap_mutation(name, original):
            def wrapped(*args, **kwargs):
                value = original(*args, **kwargs)
                if not mock_running and (name != 'open' or args[1] & (os.O_WRONLY | os.O_RDWR | os.O_CREAT)):
                    try:
                        checkpoint('os.' + name)
                    except OSError:
                        if name == 'open':
                            os.close(value)
                        raise
                return value
            return wrapped

        def execute():
            nonlocal executing
            lock_order.clear()
            output = io.StringIO()
            with contextlib.ExitStack() as stack:
                stack.enter_context(patch('os.lstat', fixture_stat))
                stack.enter_context(patch.object(grp, 'getgrgid', side_effect=fixture_group))
                stack.enter_context(patch('os.geteuid', return_value=0))
                stack.enter_context(patch('os.getegid', return_value=0))
                stack.enter_context(patch('fcntl.flock', side_effect=tracked_flock))
                stack.enter_context(patch('subprocess.run', systemctl))
                stack.enter_context(patch('time.monotonic', side_effect=lambda: clock['now']))
                stack.enter_context(patch('time.sleep', side_effect=sleep))
                stack.enter_context(patch.dict(os.environ, {'XBOARD_EXPECTED': json.dumps(expected)}))
                for name in ('open', 'mkdir', 'unlink', 'rmdir', 'replace', 'fsync'):
                    stack.enter_context(patch('os.' + name, wrap_mutation(name, getattr(os, name))))
                stack.enter_context(contextlib.redirect_stdout(output))
                try:
                    executing = True
                    exec(compile(tree, '<actual-uninstaller-producer>', 'exec'), {})
                except SystemExit:
                    pass
                finally:
                    executing = False
            if not root_contended:
                original_flock(contender_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                original_flock(contender_fd, fcntl.LOCK_UN)
            return output.getvalue()

        def ssl_metadata():
            return {name: (path(name).lstat().st_ino, path(name).lstat().st_mode,
                           path(name).lstat().st_mtime_ns, path(name).lstat().st_ctime_ns)
                    for name in ('/root/cert', '/root/cert/shared.pem', '/root/.acme.sh', '/root/.acme.sh/account.key')}

        original_ssl = ssl_metadata()
        initial_installation = None
        if case == 'lifecycle':
            initial_installation = {
                name: path(name).read_bytes() for name in
                (job + '/status', job + '/phase', job + '/started', job + '/result', job + '/result.next', job + '/install.sh',
                 job + '/verified.sh', job + '/cookies', job + '/install-result.env', job + '/verify-abc123.cookies',
                 '/usr/bin/x-ui', '/usr/local/x-ui/x-ui', '/usr/local/x-ui/bin/xray', '/etc/x-ui/x-ui.db',
                 '/etc/systemd/system/x-ui.service', '/etc/default/x-ui', '/etc/sysconfig/x-ui',
                 '/var/log/x-ui/access.log', '/var/log/x-ui/nested/error.log',
                 '/usr/local/x-ui-linux-amd64.tar.gz', '/usr/local/x-ui-linux-arm64.tar.gz')}
        if case == 'root_lock_busy':
            root_contended = True
            original_flock(contender_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        initial = execute()
        if root_contended:
            original_flock(contender_fd, fcntl.LOCK_UN)
            root_contended = False
        if held_fd is not None:
            os.close(held_fd)
        initial_mutations = list(mutations)
        initial_calls = list(calls)
        initial_lock_order = list(lock_order)
        fault_enabled = False
        second = None
        unchanged = None
        if case in ('root_lock_busy', 'maintenance_lock_busy', 'job_lock_busy'):
            calls.clear()
            mutations.clear()
            second = execute()
        if case.startswith(('fault_', 'resume_', 'retry_')):
            if case.startswith('resume_'):
                assert initial == 'REFUSED\tUNCONFIRMED\tSTOP\n', initial
                if case == 'resume_mount':
                    write('/proc/self/mountinfo', ('1 0 0:1 / ' + str(path('/usr/local/x-ui/user-assets')) + ' rw - ext4 fixture rw\n').encode())
                if case in ('resume_parent', 'resume_tree_link', 'resume_recreated', 'resume_job_link'):
                    original = path({'resume_parent': '/usr/local', 'resume_tree_link': '/usr/local/x-ui',
                                     'resume_recreated': '/usr/local/x-ui', 'resume_job_link': job}[case])
                    moved = original.with_name(original.name + '-moved')
                    original.rename(moved)
                    if case == 'resume_recreated':
                        original.mkdir(mode=0o700)
                        (original / 'unrelated').write_bytes(b'unknown replacement must survive')
                        foreign['/usr/local/x-ui/unrelated'] = b'unknown replacement must survive'
                    else:
                        original.symlink_to(moved, target_is_directory=True)
                if case == 'resume_unit':
                    unit.write_bytes(b'[Service]\nExecStop=/bin/sh -c arbitrary\n')
                if case == 'resume_dropin':
                    write('/etc/systemd/system/x-ui.service.d/99-foreign.conf', b'[Service]\nExecStop=/bin/sh\n')
                if case == 'resume_db':
                    with sqlite3.connect(db) as con:
                        con.execute("UPDATE settings SET value='9999' WHERE key='webPort'")
                if case == 'resume_binding':
                    expected['port'] = '9999'
                if case == 'resume_receipt_mode':
                    path(job + '/uninstall-started').chmod(0o644)
                if case == 'resume_binary_link':
                    binary.unlink()
                    binary.symlink_to(path('/usr/local/xray/xray'))
                if case == 'resume_cli_link':
                    cli.unlink()
                    cli.symlink_to(path('/usr/local/xray/xray'))
            if case.startswith('retry_'):
                assert initial == 'REMOVED\n', initial
                marker = path(job + '/uninstall-complete')
                if case == 'retry_bad_marker':
                    marker.write_bytes(b'broken')
                if case == 'retry_bad_started':
                    path(job + '/uninstall-started').write_bytes(b'broken')
                if case == 'retry_marker_mode':
                    marker.chmod(0o644)
                if case == 'retry_job_mode':
                    path(job).chmod(0o755)
                if case == 'retry_marker_hardlink':
                    os.link(marker, path('/root/extra-link'))
                if case == 'retry_marker_link':
                    moved = marker.with_name('moved')
                    marker.rename(moved)
                    marker.symlink_to(moved)
                if case == 'retry_runner':
                    write(job + '/runner')
                if case == 'retry_mount':
                    write('/proc/self/mountinfo', ('1 0 0:1 / ' + str(path(job)) + ' rw - ext4 fixture rw\n').encode())
                if case == 'retry_missing_marker':
                    marker.unlink()
                if case == 'retry_no_receipt':
                    marker.unlink()
                    path(job + '/uninstall-started').unlink()
                if case in ('retry_port', 'retry_path', 'retry_url', 'retry_panel'):
                    key = {'retry_port': 'port', 'retry_path': 'path', 'retry_url': 'url', 'retry_panel': 'panelId'}[case]
                    expected[key] = 'fedcba98-7654-3210-fedc-ba9876543210' if key == 'panelId' else 'different'
                if case == 'retry_normalized':
                    expected['path'] = '/selected/'
                    expected['url'] = expected['url'].rstrip('/')
                recreated = {'retry_live': '/usr/local/x-ui/recreated', 'retry_config': '/etc/x-ui/recreated',
                             'retry_unit': '/etc/systemd/system/x-ui.service', 'retry_cli': '/usr/bin/x-ui',
                             'retry_dropin': '/etc/systemd/system/x-ui.service.d/recreated',
                             'retry_enable': '/etc/systemd/system/multi-user.target.wants/x-ui.service'}
                if case in recreated:
                    write(recreated[case], b'recreated must survive')
                if case == 'retry_dangling':
                    cli.symlink_to(path('/root/absent'))
                if case == 'retry_active':
                    props['retry_active'] = 'active'
                if case == 'retry_loaded':
                    props['LoadState'] = 'loaded'
                if case == 'retry_fragment':
                    props['FragmentPath'] = str(unit)
                if case == 'retry_dropin_state':
                    props['DropInPaths'] = str(path(drop))
                if case == 'retry_install_process':
                    write('/proc/999/cmdline', ('/bin/bash\0' + str(path(job + '/runner')) + '\0').encode())
                if case in ('retry_owned_process', 'retry_cgroup_process'):
                    write('/proc/200/cgroup', b'0::/system.slice/x-ui.service\n' if case == 'retry_cgroup_process'
                          else b'0::/system.slice/foreign.service\n')
                    path('/proc/200/exe').symlink_to(binary if case == 'retry_owned_process' else path('/usr/local/xray/xray'))

            def snapshot():
                return {str(p.relative_to(root)): (p.lstat().st_mode, p.lstat().st_ino, p.lstat().st_mtime_ns,
                        os.readlink(p) if p.is_symlink() else (p.read_bytes() if p.is_file() else None))
                        for p in root.rglob('*')}

            before = snapshot()
            calls.clear()
            mutations.clear()
            read_only = case.startswith('retry_') and case not in ('retry_missing_marker', 'retry_no_receipt', 'retry_panel')
            second = execute()
            read_only = False
            unchanged = before == snapshot()
        if case.startswith('legacy_'):
            assert initial == 'REMOVED\n', initial
            original_completion = path(job + '/uninstall-complete').read_bytes()
            write('/etc/default/x-ui', b'XUI_DB_TYPE=sqlite\n' if case != 'legacy_unsafe_env' else b'XUI_DB_TYPE=mysql\n')
            write('/etc/sysconfig/x-ui', b"XUI_DB_TYPE='sqlite'\n")
            write('/var/log/x-ui/nested/old.log', b'legacy logs')
            path('/var/log/x-ui/external').symlink_to(path('/root/cert'), target_is_directory=True)
            for arch in ('amd64', 'arm64'):
                write('/usr/local/x-ui-linux-' + arch + '.tar.gz', ARCHIVE_FIXTURE)
            path('/root/.xboard-install/active').mkdir(mode=0o700)
            if case != 'legacy_empty':
                write('/root/.xboard-install/active/job', (str(path(job)) + '\n').encode())
            if case == 'legacy_failed_reused_pid':
                write(job + '/status', b'FAILED\n')
                write(job + '/phase', b'API\n')
                write('/root/.xboard-install/active/pid', b'999\n')
                write('/proc/999/cmdline', b'/usr/bin/sleep\0' + b'3600\0')
                foreign['/proc/999/cmdline'] = b'/usr/bin/sleep\0' + b'3600\0'
            write(job + '/cookies', b'old login cookies')
            if case == 'legacy_runner':
                write(job + '/runner', b'finished runner credentials')
            if case == 'legacy_unsafe_active':
                write('/root/.xboard-install/active/job', b'another-owner')
            if case == 'legacy_certificate':
                write('/var/log/x-ui/old.pem', b'certificate must survive')
                foreign['/var/log/x-ui/old.pem'] = b'certificate must survive'
            calls.clear()
            mutations.clear()
            second = execute()
            assert path(job + '/uninstall-complete').read_bytes() == original_completion
        if case == 'lifecycle':
            assert initial == 'REMOVED\n', initial
            assert all(not os.path.lexists(path(name)) for name in ADDITIONAL)
            old_job = job
            old_completion = path(old_job + '/uninstall-complete').read_bytes()
            job = '/root/.xboard-install/fedcba98-7654-3210-fedc-ba9876543210'
            expected['panelId'] = job.rsplit('/', 1)[1]
            for name, data in initial_installation.items():
                write(name.replace(old_job, job), data)
            os.utime(path(job + '/started'), ns=(1000000000, 1000000000))
            path('/root/.xboard-install/active').mkdir(mode=0o700)
            write('/root/.xboard-install/active/job', (str(path(job)) + '\n').encode())
            write('/root/.xboard-install/active/pid', b'1000\n')
            enable.symlink_to(unit)
            write('/proc/100/cgroup', b'0::/system.slice/x-ui.service\n')
            write('/proc/100/environ', b'XUI_DB_TYPE=sqlite\0')
            path('/proc/100/exe').symlink_to(binary)
            state.update(active=True, disabled=False, reloaded=False)
            props.update(LoadState='loaded', FragmentPath=str(unit), DropInPaths='')
            calls.clear()
            mutations.clear()
            second = execute()
            assert path(old_job + '/uninstall-complete').read_bytes() == old_completion
        receipts = [p.read_bytes() for p in path(job).glob('uninstall-*') if p.is_file() and not p.is_symlink()]
        summary = {
            'initial': initial, 'output': second if second is not None else initial,
            'initial_mutations': initial_mutations, 'mutations': mutations, 'unchanged': unchanged,
            'initial_lock_order': initial_lock_order, 'lock_order': lock_order,
            'stopped': any(c[0] == 'stop' for c in initial_calls), 'calls': calls,
            'live': path('/usr/local/x-ui').exists(), 'db': db.exists(), 'unit': unit.exists(), 'cli': cli.exists(),
            'job_secrets': any(path(job + '/' + name).exists() for name in
                               ('result', 'result.next', 'install.sh', 'verified.sh', 'cookies', 'install-result.env', 'verify-abc123.cookies', 'runner')),
            'ssl_untouched': original_ssl == ssl_metadata(),
            'archive_reads': len(archive_reads),
            'additional_remaining': [name for name in ADDITIONAL if os.path.lexists(path(name))],
            'active_remaining': os.path.lexists(path('/root/.xboard-install/active')),
            'waited_seconds': clock['now'],
            'no_backup': not path('/root/.xboard-uninstall').exists() and not list(root.rglob('*.tar')) and not list(root.rglob('*.sqlite')),
            'unrelated_preserved': all(path(name).read_bytes() == data for name, data in foreign.items()),
            'receipts_private': all(stat.S_IMODE(p.stat().st_mode) == 0o600 for p in path(job).glob('uninstall-*') if p.is_file()),
            'receipts_nonsecret': all(not any(secret in value for secret in
                (b'old-password', b'old-token', b'old-user', b'selected', b'panel.invalid', b'clients recovery', b'installer credentials'))
                for value in receipts),
            'nonroot_seen': bool(nonroot_seen),
            'parent_path': str(path(PARENT_FIXTURES[case][0])) if case in PARENT_FIXTURES else None,
            'unused_parents_absent': all(not os.path.lexists(path(name)) for name in
                                         ('/etc/default', '/etc/sysconfig', '/var', '/var/log')),
        }
        completion = path(job + '/uninstall-complete')
        if completion.exists() and not case.startswith('retry_'):
            summary['completion_keys'] = sorted(json.loads(completion.read_bytes()))
        os.close(contender_fd)
        print(json.dumps(summary))


class NativeUninstallerTest(unittest.TestCase):
    def run_case(self, case):
        result = subprocess.run([sys.executable, str(Path(__file__).resolve()), '--worker', case],
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(result.stdout)
        self.assertTrue(data['unrelated_preserved'], data)
        self.assertTrue(data['no_backup'], data)
        self.assertTrue(data['ssl_untouched'], data)
        return data

    def assert_removed(self, data):
        self.assertEqual(data['output'], 'REMOVED\n', data)
        self.assertFalse(any(data[k] for k in ('live', 'db', 'unit', 'cli', 'job_secrets', 'active_remaining', 'additional_remaining')), data)
        self.assertTrue(data['receipts_private'] and data['receipts_nonsecret'], data)

    def test_standard_service_variants_do_not_require_a_frozen_unit_or_global_audit(self):
        for case in ('standard_service_variants', 'manager_env', 'batch_missing', 'batch_duplicate'):
            with self.subTest(case=case):
                data = self.run_case(case)
                self.assert_removed(data)
                self.assertFalse(any(call[0] in ('list-units', 'list-unit-files', 'show-environment', 'cat')
                                     for call in data['calls']), data)

    def test_native_removal_accepts_archive_metadata_and_unlinks_without_following(self):
        for case in ('success', 'inactive', 'dropin', 'wal', 'usrmerge', 'archive_uid', 'groupwrite', 'hardlink',
                     'hardlink_binary', 'hardlink_db', 'special_file', 'file_modes', 'db_checkpoint'):
            with self.subTest(case=case):
                data = self.run_case(case)
                self.assert_removed(data)
                self.assertEqual(data['completion_keys'], ['panelId', 'target', 'version'])
                if case == 'archive_uid':
                    self.assertTrue(data['nonroot_seen'])

    def test_safe_ubuntu_parent_groups_and_missing_unused_directories(self):
        for case in (*SAFE_PARENTS, 'missing_unused_parents'):
            with self.subTest(case=case):
                data = self.run_case(case)
                self.assert_removed(data)
                if case == 'missing_unused_parents':
                    self.assertTrue(data['unused_parents_absent'], data)

    def test_parent_group_exceptions_remain_path_specific_and_fail_closed(self):
        for case in UNSAFE_PARENTS:
            with self.subTest(case=case):
                data = self.run_case(case)
                self.assertRegex(data['output'], r'^REFUSED\tPARENT\tPARENT_[A-Z_]+\n$')
                stage = 'PARENT_' + re.sub(r'[^A-Z_]', '_', data['parent_path'].strip('/').upper())
                self.assertEqual(data['output'], 'REFUSED\tPARENT\t' + stage + '\n', data)
                self.assertFalse(data['stopped'], data)
                self.assertEqual(data['initial_mutations'], [], data)
                self.assertEqual(data['calls'], [], data)
                self.assertTrue(data['live'] and data['db'] and data['unit'] and data['job_secrets'], data)

    def test_kotlin_parent_diagnostics_map_known_stages_to_one_path(self):
        source = SOURCE.read_text()
        self.assertIn('${uninstallParentPath(stage)}', source)
        mapping = dict(re.findall(r'"(PARENT_[A-Z_]+)" -> "([^"]+)"', source))
        self.assertEqual(mapping['PARENT_FILESYSTEM_ROOT'], '/')
        for parent in ('/root', '/usr', '/usr/local', '/usr/bin', '/etc', '/etc/systemd',
                       '/etc/systemd/system', '/etc/default', '/etc/sysconfig', '/var', '/var/log'):
            with self.subTest(parent=parent):
                stage = 'PARENT_' + parent.strip('/').replace('/', '_').upper()
                self.assertEqual(mapping[stage], parent)

    def test_unsafe_or_unowned_targets_are_refused_before_stop(self):
        cases = {'port': 'TARGET', 'dbpath': 'TARGET', 'mount': 'MOUNT', 'root_mount': 'MOUNT',
                 'cli_mount': 'MOUNT', 'job_mount': 'MOUNT', 'parentlink': 'PARENT', 'parent_mode': 'PARENT',
                 'tree_link': 'PANEL_LAYOUT', 'dblink': 'DB_LAYOUT', 'wal_link': 'DB_LAYOUT',
                 'unsafe_unit': 'SERVICE', 'job_missing': 'OWNERSHIP',
                 'job_wrong': 'OWNERSHIP', 'running': 'BUSY', 'maintenance': 'BUSY', 'attempted': 'RECEIPT',
                 'foreign_process': 'PROCESS', 'wrong_main': 'PROCESS'}
        for case, code in cases.items():
            with self.subTest(case=case):
                data = self.run_case(case)
                self.assertRegex(data['output'], '^REFUSED\t' + code + '\t[A-Z_]+\n$')
                self.assertFalse(data['stopped'], data)
                self.assertTrue(data['live'] and data['db'] and data['unit'] and data['job_secrets'], data)
                self.assertNotIn('old-password', data['output'])

    def test_uncertain_outcomes_do_not_report_success(self):
        for case in ('residual', 'changed', 'timeout', 'cached_unit'):
            with self.subTest(case=case):
                self.assertRegex(self.run_case(case)['output'], '^REFUSED\t[A-Z_]+\t[A-Z_]+\n$')

    def test_resume_after_every_filesystem_or_service_mutation(self):
        baseline = self.run_case('fault_0')
        self.assert_removed(baseline)
        self.assertGreater(len(baseline['initial_mutations']), 25)
        for index, operation in enumerate(baseline['initial_mutations'], 1):
            with self.subTest(index=index, operation=operation):
                data = self.run_case('fault_' + str(index))
                self.assertTrue(data['initial'].startswith('REFUSED\t'), data)
                self.assert_removed(data)

    def test_resume_revalidates_boundaries_target_and_service_before_deletion(self):
        cases = {'resume_mount': 'MOUNT', 'resume_parent': 'PARENT', 'resume_tree_link': 'CHANGED',
                 'resume_recreated': 'CHANGED', 'resume_job_link': 'JOB_PRIVATE', 'resume_unit': 'CHANGED',
                 'resume_dropin': 'CHANGED', 'resume_db': 'TARGET', 'resume_binding': 'RECEIPT',
                 'resume_receipt_mode': 'JOB_PRIVATE', 'resume_binary_link': 'SERVICE',
                 'resume_cli_link': 'PANEL_LAYOUT'}
        for case, code in cases.items():
            with self.subTest(case=case):
                data = self.run_case(case)
                self.assertRegex(data['output'], '^REFUSED\\t' + code + '\\t[A-Z_]+\\n$')
                self.assertTrue(data['live'] and data['db'] and data['unit'] and data['job_secrets'], data)
                if case != 'resume_db':
                    self.assertFalse(any(c[0] in ('stop', 'disable') for c in data['calls']), data)

    def test_completion_retry_is_read_only_and_normalized_target_bound(self):
        for case in ('retry_success', 'retry_normalized'):
            with self.subTest(case=case):
                data = self.run_case(case)
                self.assert_removed(data)
                self.assertTrue(data['unchanged'], data)
                self.assertEqual(data['mutations'], [], data)
                self.assertEqual(len(data['calls']), 1, data)
                self.assertTrue(all(c[0] == 'show' for c in data['calls']), data)

    def test_completion_rejects_invalid_receipts_reappeared_paths_and_processes(self):
        cases = {'retry_bad_marker': 'RECEIPT', 'retry_bad_started': 'RECEIPT',
                 'retry_marker_mode': 'JOB_PRIVATE', 'retry_job_mode': 'JOB_PRIVATE',
                 'retry_marker_hardlink': 'JOB_PRIVATE', 'retry_marker_link': 'JOB_PRIVATE',
                 'retry_runner': 'BUSY', 'retry_mount': 'MOUNT', 'retry_port': 'RECEIPT',
                 'retry_path': 'RECEIPT', 'retry_url': 'RECEIPT', 'retry_live': 'REAPPEARED',
                 'retry_config': 'REAPPEARED', 'retry_unit': 'REAPPEARED', 'retry_cli': 'REAPPEARED',
                 'retry_dropin': 'REAPPEARED', 'retry_enable': 'REAPPEARED', 'retry_dangling': 'REAPPEARED',
                 'retry_active': 'UNCONFIRMED', 'retry_loaded': 'UNCONFIRMED', 'retry_fragment': 'UNCONFIRMED',
                  'retry_dropin_state': 'UNCONFIRMED', 'retry_owned_process': 'PROCESS', 'retry_cgroup_process': 'PROCESS',
                  'retry_install_process': 'BUSY'}
        for case, code in cases.items():
            with self.subTest(case=case):
                data = self.run_case(case)
                self.assertEqual(data['output'], 'REFUSED\t' + code + '\tCOMPLETION\n', data)
                self.assertTrue(data['unchanged'], data)
                self.assertEqual(data['mutations'], [], data)
                self.assertTrue(all(c[0] == 'show' for c in data['calls']), data)

    def test_missing_completion_resumes_but_absence_without_receipt_does_not_prove_ownership(self):
        self.assert_removed(self.run_case('retry_missing_marker'))
        for case in ('retry_no_receipt', 'retry_panel'):
            with self.subTest(case=case):
                self.assertTrue(self.run_case(case)['output'].startswith('REFUSED\t'))

    def test_additional_assets_are_owned_scoped_and_ssl_remains_untouched(self):
        for case in ('log_link', 'log_dangling', 'archive_link', 'external_ssl_settings',
                     'active_owned', 'active_pid_dead', 'other_history', 'default_env_service',
                     'sysconfig_env_service', 'empty_environments', 'no_additional_assets'):
            with self.subTest(case=case):
                self.assert_removed(self.run_case(case))

    def test_unsafe_additional_assets_and_active_owners_refuse_before_stop(self):
        cases = {'unsafe_default': 'ENVIRONMENT', 'unsafe_sysconfig': 'ENVIRONMENT',
                 'unsafe_env_comment': 'ENVIRONMENT', 'unsafe_env_spaced_comment': 'ENVIRONMENT',
                 'unsafe_env_export': 'ENVIRONMENT', 'unsafe_env_command': 'ENVIRONMENT', 'environment_link': 'ENVIRONMENT',
                 'default_parent_link': 'PARENT', 'sysconfig_parent_link': 'PARENT', 'log_parent_link': 'PARENT',
                 'log_mount': 'MOUNT', 'archive_unknown': 'ARCHIVE',
                 'certificate_inside': 'CERTIFICATE', 'certificate_extensionless': 'CERTIFICATE',
                 'certificate_setting': 'CERTIFICATE', 'active_other': 'BUSY', 'active_pid_live': 'BUSY',
                 'active_pid_no_owner': 'BUSY', 'active_other_runner': 'BUSY', 'active_other_running': 'BUSY',
                 'active_process': 'BUSY', 'bad_show_rc1': 'SERVICE'}
        for case, code in cases.items():
            with self.subTest(case=case):
                data = self.run_case(case)
                self.assertRegex(data['output'], '^REFUSED\\t' + code + '\\t[A-Z_]+\\n$')
                self.assertFalse(data['stopped'], data)
                self.assertTrue(data['live'] and data['db'] and data['unit'] and data['job_secrets'], data)

    def test_stop_and_reload_settle_in_first_attempt_with_bounded_waits(self):
        for case in ('delayed_stop', 'delayed_reload', 'absent_rc1', 'absent_rc1_reordered'):
            with self.subTest(case=case):
                data = self.run_case(case)
                self.assert_removed(data)
                self.assertLessEqual(data['waited_seconds'], 15 if case == 'delayed_stop' else 10, data)
                if case.startswith('delayed_'):
                    self.assertGreaterEqual(data['waited_seconds'], 1, data)
                self.assertEqual(sum(call[0] == 'stop' for call in data['calls']), 1, data)
                self.assertEqual(sum(call[0] == 'daemon-reload' for call in data['calls']), 1, data)
        self.assertEqual(self.run_case('residual')['waited_seconds'], 15)
        self.assertEqual(self.run_case('cached_unit')['waited_seconds'], 10)
        for case, seconds, stage in (('show_timeout_stop', 15, 'STOP'), ('show_timeout_reload', 10, 'VERIFY')):
            with self.subTest(case=case):
                data = self.run_case(case)
                self.assertEqual(data['waited_seconds'], seconds, data)
                self.assertRegex(data['output'], '^REFUSED\\t[A-Z_]+\\t' + stage + '\\n$')

    def test_false_absence_never_reports_success(self):
        for case in ('absent_bad_rc1', 'absent_rc2', 'additional_reappeared', 'process_reappeared'):
            with self.subTest(case=case):
                data = self.run_case(case)
                self.assertRegex(data['output'], '^REFUSED\\t[A-Z_]+\\tVERIFY\\n$')
                self.assertTrue(data['job_secrets'], data)

    def test_other_system_services_are_not_audited_or_modified(self):
        data = self.run_case('many_services')
        self.assert_removed(data)
        self.assertFalse(any(call[0] in ('list-units', 'list-unit-files', 'cat') for call in data['calls']), data)
        self.assertLess(len(data['calls']), 100, data)
        self.assertEqual(data['archive_reads'], 2, data)
        changed = self.run_case('archive_changed')
        self.assertEqual(changed['output'], 'REFUSED\tARCHIVE\tDISABLE\n', changed)
        self.assertTrue(changed['live'] and changed['db'], changed)

    def test_legacy_completion_cleans_leftovers_without_database_or_credentials(self):
        for case in ('legacy_success', 'legacy_runner'):
            with self.subTest(case=case):
                self.assert_removed(self.run_case(case))
        for case, code in (('legacy_unsafe_env', 'ENVIRONMENT'), ('legacy_unsafe_active', 'BUSY'),
                           ('legacy_certificate', 'CERTIFICATE')):
            with self.subTest(case=case):
                data = self.run_case(case)
                self.assertEqual(data['output'], 'REFUSED\t' + code + '\tCOMPLETION\n', data)
                self.assertFalse(data['live'] or data['db'], data)
                self.assertTrue(data['additional_remaining'], data)

    def test_install_uninstall_reinstall_uninstall_keeps_only_nonsecret_history(self):
        data = self.run_case('lifecycle')
        self.assertEqual(data['initial'], 'REMOVED\n', data)
        self.assert_removed(data)
        self.assertEqual(data['completion_keys'], ['panelId', 'target', 'version'])

    def test_directory_lock_matches_bash_flock_descriptor(self):
        bash = shutil.which('bash')
        flock = shutil.which('flock')
        if not bash or not flock:
            self.skipTest('bash and flock are required for descriptor interoperability')
        with tempfile.TemporaryDirectory(prefix='uninstaller-flock-', dir='/tmp/opencode') as directory:
            fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                command = [bash, '--noprofile', '--norc', '-c', 'exec 9<"$1"; "$2" -n 9', 'claim', directory, flock]
                blocked = subprocess.run(command, capture_output=True, timeout=5)
                self.assertEqual(blocked.returncode, 1, blocked.stderr)
                fcntl.flock(fd, fcntl.LOCK_UN)
                available = subprocess.run(command, capture_output=True, timeout=5)
                self.assertEqual(available.returncode, 0, available.stderr)
                self.assertEqual(list(Path(directory).iterdir()), [])
            finally:
                os.close(fd)

    def test_install_directory_lock_is_first_nonblocking_and_released_after_refusal(self):
        for case, first in (('root_lock_busy', ['install']),
                            ('maintenance_lock_busy', ['install', 'maintenance']),
                            ('job_lock_busy', ['install', 'maintenance', 'job'])):
            with self.subTest(case=case):
                data = self.run_case(case)
                self.assertEqual(data['initial'], 'REFUSED\tBUSY\t' +
                                 ('INSTALL_LOCK' if case == 'root_lock_busy' else 'LOCK') + '\n', data)
                self.assertEqual(data['initial_lock_order'], first, data)
                self.assertFalse(data['stopped'], data)
                self.assert_removed(data)
                self.assertEqual(data['lock_order'], ['install', 'maintenance', 'job'], data)
                if case == 'root_lock_busy':
                    self.assertEqual(data['initial_mutations'], [], data)
        data = self.run_case('retry_success')
        self.assertEqual(data['lock_order'], ['install'], data)
        self.assertTrue(data['unchanged'], data)

    def test_terminal_claims_accept_full_job_paths_optional_and_reused_pids(self):
        for case in ('success', 'active_no_pid', 'active_pid_dead', 'active_pid_reused', 'active_pid_reference',
                     'active_pid_lookalike', 'active_pid_zombie', 'active_absent', 'active_legacy_empty',
                     'legacy_empty', 'legacy_failed_reused_pid'):
            with self.subTest(case=case):
                data = self.run_case(case)
                self.assert_removed(data)
                self.assertEqual(data['lock_order'], ['install', 'maintenance', 'job'], data)

    def test_claim_files_are_private_consistent_and_do_not_authorize_running_jobs(self):
        absent = self.run_case('active_process_no_claim')
        self.assertEqual(absent['output'], 'REFUSED\tBUSY\tJOB\n', absent)
        self.assertEqual(absent['mutations'], [], absent)
        self.assertFalse(absent['active_remaining'] or absent['stopped'], absent)
        self.assertTrue(absent['live'] and absent['db'] and absent['job_secrets'], absent)
        cases = {
            'active_pid_selected': 'BUSY', 'active_pid_other_job': 'BUSY', 'active_pid_installer': 'BUSY',
            'active_pid_verifier': 'BUSY', 'active_pid_certificate': 'BUSY', 'active_pid_malformed': 'BUSY',
            'active_unknown_file': 'BUSY', 'active_pid_launch': 'BUSY', 'active_pid_next': 'BUSY',
            'active_not_terminal': 'BUSY', 'active_job_mode': 'JOB_PRIVATE', 'active_pid_mode': 'JOB_PRIVATE',
            'active_job_owner': 'JOB_PRIVATE', 'active_pid_owner': 'JOB_PRIVATE',
            'active_job_link': 'JOB_PRIVATE', 'active_pid_link': 'JOB_PRIVATE',
            'active_job_hardlink': 'JOB_PRIVATE', 'active_pid_hardlink': 'JOB_PRIVATE',
            'active_directory_link': 'JOB_PRIVATE',
        }
        for case, code in cases.items():
            with self.subTest(case=case):
                data = self.run_case(case)
                self.assertRegex(data['output'], '^REFUSED\\t' + code + '\\t[A-Z_]+\\n$')
                self.assertEqual(data['initial_mutations'], [], data)
                self.assertFalse(data['stopped'], data)
                self.assertTrue(data['active_remaining'] and data['live'] and data['db'] and data['job_secrets'], data)

    def test_kotlin_contract_has_no_backup_return_or_secret_diagnostics(self):
        source = SOURCE.read_text()
        self.assertIn('suspend fun uninstall(server: InstalledServer): Unit', source)
        self.assertIn('response.stdout == "REMOVED\\n"', source)
        for forbidden in ('tarfile', '.backup(', 'panel.sqlite', 'panel.tar', '.xboard-uninstall',
                          'server.panelPassword', 'server.token', 'server.secretPath}"'):
            self.assertNotIn(forbidden, source)
        self.assertIn('uninstall-started, uninstall-complete', source)


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--worker':
        worker(sys.argv[2])
    else:
        unittest.main()
