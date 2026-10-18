#!/usr/bin/env python3
"""Check public Maven dependency names/versions against OSV; never sends APKs or keys."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sys
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        raise ValueError('OSV redirect refused')


def scan(path):
    raw = path.read_bytes()
    inventory = json.loads(raw)
    if inventory.get('bomFormat') != 'CycloneDX' or not 0 < len(inventory.get('components', [])) <= 512:
        raise ValueError('Invalid or excessive runtime inventory')
    packages = set()
    for component in inventory['components']:
        fields = [component.get(key, '') for key in ('group', 'name', 'version')]
        if not all(isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_.+\-]{1,160}', value) for value in fields):
            raise ValueError('Unexpected Maven coordinate')
        packages.add((fields[0] + ':' + fields[1], fields[2]))
    ordered = sorted(packages)
    found = {package: set() for package in ordered}
    pending = [(package, None) for package in ordered]
    opener = urllib.request.build_opener(NoRedirect())
    pages = 0
    while pending:
        pages += 1
        if pages > 20:
            raise ValueError('OSV pagination exceeded the review limit')
        queries = []
        for (name, version), token in pending:
            item = {'package': {'ecosystem': 'Maven', 'name': name}, 'version': version}
            if token:
                item['page_token'] = token
            queries.append(item)
        request = urllib.request.Request('https://api.osv.dev/v1/querybatch',
            data=json.dumps({'queries': queries}).encode(), method='POST',
            headers={'Content-Type': 'application/json', 'User-Agent': 'moa-public-dependency-verification'})
        with opener.open(request, timeout=60) as response:
            data = response.read(4 * 1024 * 1024 + 1)
        if len(data) > 4 * 1024 * 1024:
            raise ValueError('OSV response exceeded the review limit')
        results = json.loads(data).get('results')
        if not isinstance(results, list) or len(results) != len(pending):
            raise ValueError('Incomplete OSV batch response')
        next_pending = []
        for (package, previous_token), result in zip(pending, results):
            if not isinstance(result, dict) or result.get('error'):
                raise ValueError('OSV returned an individual query error')
            for vulnerability in result.get('vulns', []):
                identifier = vulnerability.get('id', '')
                if not isinstance(identifier, str) or not re.fullmatch(r'[A-Za-z0-9_.\-]{1,160}', identifier):
                    raise ValueError('Unexpected vulnerability identifier')
                found[package].add(identifier)
            token = result.get('next_page_token')
            if token:
                if not isinstance(token, str) or len(token) > 8192 or token == previous_token:
                    raise ValueError('Invalid OSV pagination')
                next_pending.append((package, token))
        pending = next_pending
    findings = [{'package': package[0], 'version': package[1], 'vulnerabilities': sorted(ids)}
                for package, ids in found.items() if ids]
    return {'schema': 1, 'checked_at_utc': datetime.now(timezone.utc).isoformat(),
        'database': 'OSV', 'endpoint': 'https://api.osv.dev/v1/querybatch',
        'inventory_sha256': hashlib.sha256(raw).hexdigest(), 'packages_queried': len(ordered),
        'query_complete': True, 'result': 'known_vulnerabilities_found' if findings else 'no_known_match',
        'findings': findings,
        'transmitted_fields': ['public Maven package name', 'public package version', 'OSV page token when needed'],
        'scope': 'Known OSV Maven advisories for resolved release-runtime inputs at this time. Database coverage varies; excludes JDK/SDK, OS, WebView, remote services and unknown vulnerabilities.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    try:
        report = scan(args.inventory)
    except Exception as error:
        # Do not publish arbitrary network responses, local paths or environment data.
        print('Dependency check incomplete: ' + type(error).__name__, file=sys.stderr)
        return 2
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: report[key] for key in ('result', 'query_complete', 'packages_queried')}))
    return 1 if report['findings'] else 0


if __name__ == '__main__':
    sys.exit(main())
