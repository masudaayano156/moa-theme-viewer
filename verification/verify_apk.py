#!/usr/bin/env python3
"""Read-only release identity/permission checks. No private configuration or keys."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

REPOSITORY = 'masudaayano156/moa-theme-viewer'
CERTIFICATE = 'd425e1a47b90276aefb8d8ae1617fd44b0528113be25712faea20079b1211993'


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1048576), b''):
            value.update(chunk)
    return value.hexdigest()


def load_manifest(path):
    value = json.loads(Path(path).read_text(encoding='utf-8'))
    if value.get('schema') != 1 or value.get('repository') != REPOSITORY:
        raise ValueError('Unsupported release manifest')
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', value.get('version', '')):
        raise ValueError('Invalid release version')
    if not re.fullmatch(r'[0-9a-f]{40}', value.get('source_commit', '')):
        raise ValueError('Invalid source commit')
    if not re.fullmatch(r'[0-9a-f]{64}', value.get('apk', {}).get('sha256', '')):
        raise ValueError('Invalid APK digest')
    if value.get('certificate_sha256') != CERTIFICATE:
        raise ValueError('Unexpected publisher certificate')
    expected_name = 'moa-' + value['version'] + '.apk'
    if value['apk'].get('filename') != expected_name:
        raise ValueError('Unexpected APK filename')
    expected_url = 'https://github.com/' + REPOSITORY + '/releases/download/v' + value['version'] + '/' + expected_name
    if value['apk'].get('url') != expected_url:
        raise ValueError('Unexpected release URL')
    return value


def sdk_tools(root):
    folder = Path(root) / 'build-tools' / '35.0.0'
    aapt = folder / ('aapt2.exe' if os.name == 'nt' else 'aapt2')
    jar = folder / 'lib' / 'apksigner.jar'
    java = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if os.environ.get('JAVA_HOME') else shutil.which('java')
    if not aapt.is_file() or not jar.is_file() or not java:
        raise ValueError('Android build-tools 35.0.0 and a Java runtime are required')
    return aapt, jar, str(java)


def run(arguments):
    result = subprocess.run([str(item) for item in arguments], capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.returncode:
        # Do not copy arbitrary parser output or local paths into public reports.
        raise ValueError('Android verification tool failed')
    return result.stdout


def inspect(apk, manifest, sdk):
    expected = load_manifest(manifest)
    actual_hash = digest(apk)
    if actual_hash != expected['apk']['sha256'] or apk.stat().st_size != expected['apk']['bytes']:
        raise ValueError('File size or SHA-256 differs from the selected release')
    aapt, jar, java = sdk_tools(sdk)
    signing = run([java, '-jar', jar, 'verify', '--verbose', '--print-certs', apk.resolve()])
    certs = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$', signing, re.M)
    if certs != [CERTIFICATE] or 'Verified using v2 scheme (APK Signature Scheme v2): true' not in signing:
        raise ValueError('Invalid signature or unexpected signer')
    badging = run([aapt, 'dump', 'badging', apk.resolve()])
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if not package or package.group(1) != 'app.moa.client' or package.group(2) != str(expected['version_code']) or package.group(3) != expected['version']:
        raise ValueError('Unexpected package or version')
    if 'application-debuggable' in badging:
        raise ValueError('Release must not be debuggable')
    permissions = {}
    for line in badging.splitlines():
        match = re.match(r"uses-(implied-)?permission: name='([^']+)'(.*)$", line)
        if not match:
            continue
        tail = match.group(3)
        maximum = re.search(r"maxSdkVersion='([0-9]+)'", tail)
        reason = re.search(r"reason='([^']+)'", tail)
        item = permissions.setdefault(match.group(2), {'name': match.group(2), 'max_sdk': int(maximum.group(1)) if maximum else None, 'implied': False})
        if maximum:
            item['max_sdk'] = int(maximum.group(1))
        if match.group(1):
            item['implied'] = True
            item['reason'] = reason.group(1) if reason else 'Android compatibility rule'
    actual_permissions = {(p['name'], p['max_sdk']) for p in permissions.values()}
    allowed_permissions = {(p['name'], p.get('max_sdk')) for p in expected['permissions']}
    if actual_permissions != allowed_permissions:
        raise ValueError('APK permissions differ from the reviewed release manifest')
    forbidden = []
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or archive.testzip() is not None:
            raise ValueError('Invalid or duplicate ZIP entries')
        for entry in archive.infolist():
            if entry.file_size > 128 * 1024 * 1024:
                raise ValueError('Unexpected large ZIP entry')
            name = entry.filename.lower()
            if re.search(r'(?:^|/)(?:google-services\.json|.*\.(?:p12|jks|keystore|dpapi|sqlite|db|har|log))$', name):
                forbidden.append('private_configuration_or_user_data_filename')
            content = archive.read(entry)
            if re.search(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\r\n]+[A-Za-z0-9+/=\r\n]{24,}', content):
                forbidden.append('private_key_material')
        info = archive.read('META-INF/version-control-info.textproto').decode('utf-8') if 'META-INF/version-control-info.textproto' in names else ''
        revision = re.search(r'revision:\s*"([0-9a-f]{40})"', info)
        embedded = revision.group(1) if revision else 'NO_SUPPORTED_VCS_FOUND'
    if forbidden:
        raise ValueError('Private configuration, user data or key marker found')
    if embedded != expected.get('embedded_vcs_revision'):
        raise ValueError('Embedded source metadata differs from the reviewed release')
    return {'schema': 1, 'version': expected['version'], 'sha256': actual_hash, 'bytes': apk.stat().st_size,
            'package': package.group(1), 'version_code': int(package.group(2)), 'signature_verified': True,
            'certificate_sha256': CERTIFICATE, 'debuggable': False,
            'permissions': sorted(permissions.values(), key=lambda item: item['name']),
            'source_commit': expected['source_commit'], 'embedded_vcs_revision': embedded,
            'embedded_revision_matches_source': embedded == expected['source_commit'],
            'private_material_checks': 'no_detected_match', 'identity_result': 'pass',
            'scope': 'Release identity, declared/effective permissions and limited forbidden-file/key patterns; not a malware-free certification.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    try:
        if not args.sdk:
            raise ValueError('Set ANDROID_HOME or supply --sdk')
        report = inspect(args.apk, args.manifest, args.sdk)
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'version': report['version'], 'identity_result': 'pass', 'signature_verified': True,
                          'permission_count': len(report['permissions']), 'embedded_revision_matches_source': report['embedded_revision_matches_source']}))
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        print('Release verification failed: ' + (str(error) if isinstance(error, ValueError) else type(error).__name__), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
