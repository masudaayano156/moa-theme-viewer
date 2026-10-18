#!/usr/bin/env python3
"""Limited public-source leak/permission checks, not a malware certification."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

WRAPPER_SHA256 = '498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17'
FORBIDDEN_PERMISSIONS = {
    'android.permission.CAMERA', 'android.permission.RECORD_AUDIO',
    'android.permission.READ_CONTACTS', 'android.permission.WRITE_CONTACTS',
    'android.permission.READ_SMS', 'android.permission.RECEIVE_SMS',
    'android.permission.READ_CALL_LOG', 'android.permission.READ_PHONE_STATE',
    'android.permission.ACCESS_FINE_LOCATION', 'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.MANAGE_EXTERNAL_STORAGE', 'android.permission.READ_MEDIA_IMAGES',
    'android.permission.READ_MEDIA_VIDEO', 'com.google.android.gms.permission.AD_ID'
}
PATTERNS = {
    'private_key': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{24,}'),
    'github_token': re.compile(r'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})'),
    'literal_client_api_key': re.compile(r'AIza[0-9A-Za-z_-]{30,}'),
    'private_windows_profile': re.compile(r'(?i)\b[A-Z]:[\\/]+Users[\\/]+[A-Za-z0-9_. -]+[\\/]'),
    'private_test_hook': re.compile(r'moa\.offlineTest|setOfflineTestMode|ExpiredTokenTransport|interface DownloadConnections|org\.robolectric|synthetic_')
}


def audit(root):
    result = subprocess.run(['git', '-C', str(root), 'ls-files', '-z'], capture_output=True, check=True)
    names = [name.decode('utf-8') for name in result.stdout.split(b'\0') if name]
    findings = []
    for name in names:
        path = root / name
        if re.search(r'(^|/)(?:\.tools|node_modules|fixtures|screenshots|research|src/test|src/androidTest)(?:/|$)', name):
            findings.append({'rule': 'private_or_test_directory', 'file': name})
        if re.search(r'(?i)(?:^|/)(?:google-services\.json|signing\.properties|local\.properties|\.env(?:\..*)?|.*\.(?:jks|p12|keystore|dpapi|har|sqlite|db|log|apk|aab))$', name):
            findings.append({'rule': 'private_configuration_or_artifact', 'file': name})
        if name == 'gradle/wrapper/gradle-wrapper.jar':
            if hashlib.sha256(path.read_bytes()).hexdigest() != WRAPPER_SHA256:
                findings.append({'rule': 'wrapper_digest_changed', 'file': name})
            continue
        try:
            text = path.read_text(encoding='utf-8')
        except UnicodeDecodeError:
            findings.append({'rule': 'unexpected_nontext_source', 'file': name})
            continue
        for rule, pattern in PATTERNS.items():
            if rule == 'private_test_hook' and name.startswith('verification/'):
                continue  # Verification tools contain the literal patterns they detect.
            if pattern.search(text):
                findings.append({'rule': rule, 'file': name})
    properties = (root / 'gradle/wrapper/gradle-wrapper.properties').read_text(encoding='utf-8')
    checksums = re.findall(r'(?m)^\s*distributionSha256Sum\s*[=:]\s*([^\r\n]+?)\s*$', properties)
    if checksums != ['d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab']:
        findings.append({'rule': 'gradle_distribution_digest_changed'})
    android = '{http://schemas.android.com/apk/res/android}'
    manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
    permissions = []
    for node in list(manifest.findall('uses-permission')) + list(manifest.findall('uses-permission-sdk-23')):
        name = node.get(android+'name'); maximum = node.get(android+'maxSdkVersion')
        permissions.append({'name': name, 'max_sdk': maximum})
        if name in FORBIDDEN_PERMISSIONS:
            findings.append({'rule': 'unexpected_sensitive_permission', 'permission': name})
        if name in {'android.permission.WRITE_EXTERNAL_STORAGE', 'android.permission.READ_EXTERNAL_STORAGE'} and maximum != '28':
            findings.append({'rule': 'legacy_storage_must_end_at_api28', 'permission': name})
    config = json.loads((root / 'push-worker/wrangler.jsonc').read_text(encoding='utf-8'))
    if config.get('vars') != {'FIREBASE_PROJECT_ID': 'replace-with-project-id', 'RELAY_ORIGIN': ''}:
        findings.append({'rule': 'private_worker_configuration'})
    if any(db.get('database_id') != '00000000-0000-0000-0000-000000000000' for db in config.get('d1_databases', [])):
        findings.append({'rule': 'private_database_identifier'})
    return {'schema': 1, 'result': 'pass' if not findings else 'fail', 'tracked_files': len(names),
            'source_manifest_permissions': permissions, 'findings': findings,
            'scope': 'Limited known leak markers, reviewed permissions and wrapper/config invariants. No absence-of-backdoor guarantee.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--report', type=Path)
    args = parser.parse_args();report = audit(args.root.resolve())
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report))
    return 0 if report['result'] == 'pass' else 1


if __name__ == '__main__':
    sys.exit(main())
