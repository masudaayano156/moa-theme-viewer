#!/usr/bin/env python3
"""Verify an existing public release; never claim this workflow produced its APK."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from types import SimpleNamespace
from urllib.error import URLError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

import reproduce_apk
import verify_apk

HERE = Path(__file__).resolve().parent
REPOSITORY = verify_apk.REPOSITORY
PREDICATE_TYPE = "https://github.com/masudaayano156/moa-theme-viewer/verification/apk/v1"
REPORT_FILES = ("apk-identity.json", "comparison.json", "dependencies.cdx.json", "verification.json")
HEX = re.compile(r"[0-9a-f]{64}")
VERSION = re.compile(r"(?:0|[1-9][0-9]{0,5})\.(?:0|[1-9][0-9]{0,5})\.(?:0|[1-9][0-9]{0,5})")


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def file_hash(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git_commit(folder):
    return reproduce_apk.checked_output(["git", "-C", str(folder), "rev-parse", "HEAD"], "Commit check")


def require_main_context():
    if os.environ.get("GITHUB_ACTIONS") == "true":
        if os.environ.get("GITHUB_REPOSITORY") != REPOSITORY or os.environ.get("GITHUB_REF") != "refs/heads/main":
            raise ValueError("Release verification runs only from this repository's main branch")
        if git_commit(HERE.parent) != os.environ.get("GITHUB_SHA"):
            raise ValueError("Verification tools must match the dispatched workflow commit")


def manifest(version):
    if not VERSION.fullmatch(version):
        raise ValueError("Version must be a numeric major.minor.patch, without a v prefix")
    value = verify_apk.load_manifest(HERE / "releases" / ("v" + version + ".json"))
    if (value["version"] != version or value.get("package") != "app.moa.client" or
            type(value["apk"].get("bytes")) is not int or
            not 0 < value["apk"]["bytes"] <= reproduce_apk.MAX_APK_BYTES or
            type(value.get("version_code")) is not int or not 0 < value["version_code"] <= 2100000000):
        raise ValueError("Invalid reviewed release metadata")
    return value


def workflow_outputs(values):
    destination = os.environ.get("GITHUB_OUTPUT")
    if not destination:
        return
    with open(destination, "a", encoding="utf-8") as stream:
        for key, value in values.items():
            if not re.fullmatch(r"[a-z_][a-z0-9_]*", key) or "\n" in str(value) or "\r" in str(value):
                raise ValueError("Invalid workflow output")
            stream.write(key + "=" + str(value) + "\n")


def download_url(url):
    parsed = urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname not in {
            "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"} or
            parsed.username or parsed.password or parsed.port not in (None, 443) or parsed.fragment or
            any(ord(character) < 33 for character in url)):
        raise ValueError("Unexpected release download or redirect origin")


class PublicReleaseRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        download_url(new_url)
        return super().redirect_request(request, response, code, message, headers, new_url)


def download_release(expected, target):
    url = expected["apk"]["url"]
    download_url(url)
    request = Request(url, headers={"User-Agent": "Moa-Public-Release-Verification/1.0"})
    checksum, count = hashlib.sha256(), 0
    # No Authorization, cookies, browser session or private download URL is used.
    with build_opener(PublicReleaseRedirect()).open(request, timeout=60) as response, target.open("xb") as output:
        download_url(response.url)
        while True:
            data = response.read(1024 * 1024)
            if not data:
                break
            count += len(data)
            if count > expected["apk"]["bytes"]:
                raise ValueError("Release download exceeds the reviewed APK size")
            checksum.update(data)
            output.write(data)
    if count != expected["apk"]["bytes"] or checksum.hexdigest() != expected["apk"]["sha256"]:
        raise ValueError("Downloaded APK digest or size differs from the main-branch manifest")


def prepare(args):
    require_main_context()
    expected = manifest(args.version)
    state = reproduce_apk.output_directory(args.state)
    download_release(expected, state / expected["apk"]["filename"])
    workflow_outputs({"version": expected["version"], "source_commit": expected["source_commit"],
                      "apk_name": expected["apk"]["filename"], "apk_sha256": expected["apk"]["sha256"]})
    print(json.dumps({"version": expected["version"], "download": "digest_and_size_verified"}))
    return 0


def entry_map(values):
    result = {}
    if not isinstance(values, dict) or len(values) > 4096:
        raise ValueError("Invalid APK entry report")
    for name, item in values.items():
        if (not re.fullmatch(r"[A-Za-z0-9_.$/+@-]{1,512}", name) or name.startswith("/") or
                ".." in name.split("/") or not HEX.fullmatch(item.get("sha256", "")) or
                type(item.get("bytes")) is not int or not 0 <= item["bytes"] <= reproduce_apk.MAX_APK_BYTES):
            raise ValueError("Unexpected APK entry metadata; no raw values were exported")
        result[name] = {"sha256": item["sha256"], "bytes": item["bytes"]}
    return result


def sanitized_comparison(value):
    # Explicit fields only: no paths, environment variables, logs or client values.
    fields = ("reference_apk_sha256", "rebuilt_apk_sha256", "reference_without_signing_block_sha256",
              "reference_signing_block_bytes", "rebuilt_signing_block_bytes", "reference_entry_count",
              "rebuilt_entry_count", "identical_entry_count", "all_zip_entry_bytes_equal",
              "exact_bytes_equal_after_removing_only_signing_block", "ignored_entries", "source_commit",
              "reference_vcs_matches_source_commit", "rebuilt_vcs_matches_source_commit", "reference_vcs",
              "rebuilt_vcs", "selected_apk_client_resource_names", "selected_client_inputs_sha256",
              "wrapper_jar_sha256", "wrapper_properties_sha256", "jdk", "gradle_build_cache_used",
              "configuration_cache_used", "all_tasks_rerun", "temporary_client_inputs_deleted")
    result = {key: value[key] for key in fields if key in value}
    result["reference_entries"] = entry_map(value["reference_entries"])
    result["rebuilt_entries"] = entry_map(value["rebuilt_entries"])
    names = set(result["reference_entries"]) | set(result["rebuilt_entries"])
    for key in ("missing_entries", "added_entries", "changed_entries"):
        if any(name not in names for name in value[key]):
            raise ValueError("Invalid APK difference report")
        result[key] = value[key]
    result["scope"] = "All packaged entries and container bytes; only the APK signing block is removed for raw comparison."
    return result


def sanitized_inventory(value, version):
    if value.get("bomFormat") != "CycloneDX" or value.get("specVersion") != "1.6":
        raise ValueError("Unexpected dependency inventory format")
    components = value.get("components")
    if not isinstance(components, list) or not 0 < len(components) <= 4096:
        raise ValueError("Missing or excessive dependency inventory")
    output = []
    for component in components:
        if component.get("type") != "library":
            raise ValueError("Unexpected inventory component type")
        for key in ("group", "name", "version"):
            if not re.fullmatch(r"[A-Za-z0-9_.+-]{1,160}", component.get(key, "")):
                raise ValueError("Unexpected dependency coordinate")
        hashes = component.get("hashes", [])
        if len(hashes) != 1 or hashes[0].get("alg") != "SHA-256" or not HEX.fullmatch(hashes[0].get("content", "")):
            raise ValueError("Missing dependency SHA-256")
        prefix = "pkg:maven/" + quote(component["group"], safe="") + "/" + quote(component["name"], safe="") + "@" + quote(component["version"], safe="")
        if not re.fullmatch(re.escape(prefix) + r"\?type=(?:aar|jar)", component.get("purl", "")):
            raise ValueError("Unexpected dependency package URL")
        output.append({key: component[key] for key in ("type", "group", "name", "version", "purl", "hashes")})
    return {"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
            "metadata": {"component": {"type": "application", "name": "app.moa.client", "version": version},
                         "properties": [{"name": "scope", "value": "Resolved Android releaseRuntimeClasspath inputs; excludes build tools, OS, WebView and remote services."}]},
            "components": output}


def inventory(source, state, sdk):
    java = reproduce_apk.java_executable(os.environ.get("JAVA_HOME"))
    destination = state / "runtime-dependencies.json"
    env = os.environ.copy()
    for name in list(env):
        if name in {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "GRADLE_OPTS"} or name.startswith("ORG_GRADLE_PROJECT_"):
            env.pop(name, None)
    env["ANDROID_HOME"] = str(sdk)
    env["ANDROID_SDK_ROOT"] = str(sdk)
    # Reuse exactly the resolved inputs of the completed unsigned rebuild.
    env["GRADLE_USER_HOME"] = str(state / "rebuild" / "gradle-user-home")
    command = [str(java), "-classpath", str(source / "gradle/wrapper/gradle-wrapper.jar"),
               "org.gradle.wrapper.GradleWrapperMain", "--offline", "--no-daemon", "--no-build-cache",
               "--no-configuration-cache", "--init-script", str(HERE / "dependency_inventory.gradle"),
               "-PverificationInventoryPath=" + str(destination), ":app:writeSecurityInventory"]
    process = reproduce_apk.run(command, cwd=source, env=env, timeout=1200)
    if process.returncode:
        raise ValueError("Runtime dependency inventory could not be generated")
    return json.loads(destination.read_text(encoding="utf-8"))


def verify(args):
    require_main_context()
    expected = manifest(args.version)
    state, source, sdk = args.state.resolve(), args.source_root.resolve(), args.sdk.resolve()
    public = state / "public"
    public.mkdir(exist_ok=False)
    status = {"schema": 1, "repository": REPOSITORY, "version": expected["version"],
              "source_commit": expected["source_commit"], "verification_tool_commit": git_commit(HERE.parent),
              "apk_sha256": expected["apk"]["sha256"], "result": "incomplete",
              "claim": "Verification of an existing locally signed release; not build provenance for that APK.",
              "limitations": ["Only five public client resources are reused from the release APK.",
                              "These checks do not certify absence of malware or bugs, or remote server behavior.",
                              "The inventory covers resolved Android runtime dependencies only."]}
    try:
        apk = state / expected["apk"]["filename"]
        identity = verify_apk.inspect(apk, HERE / "releases" / ("v" + args.version + ".json"), sdk)
        write_json(public / "apk-identity.json", identity)
        work = reproduce_apk.output_directory(state / "rebuild")
        rebuild_args = SimpleNamespace(apk=apk, source_root=source, sdk=sdk,
            expected_apk_sha256=expected["apk"]["sha256"], expected_source_commit=expected["source_commit"],
            java_home=os.environ.get("JAVA_HOME"), build_tools="35.0.0", gradle_user_home=None,
            offline=False, build_timeout=2400)
        comparison = reproduce_apk.rebuild(rebuild_args, work)
        write_json(public / "comparison.json", sanitized_comparison(comparison))
        dependencies = sanitized_inventory(inventory(source, state, sdk), args.version)
        write_json(public / "dependencies.cdx.json", dependencies)
        complete = (comparison["all_zip_entry_bytes_equal"] is True and
                    comparison["reference_vcs_matches_source_commit"] is True and
                    comparison["rebuilt_vcs_matches_source_commit"] is True and
                    comparison["ignored_entries"] == [] and identity["embedded_revision_matches_source"] is True)
        exact = comparison["exact_bytes_equal_after_removing_only_signing_block"] is True
        status["result"] = ("pass" if exact else "content_verified_container_differs") if complete else "mismatch"
        status["bit_exact_reproduction"] = complete and exact
        status["all_packaged_entries_verified"] = complete
        if complete and not exact:
            status["container_note"] = "ZIP entry bytes match, but raw container bytes still differ after removing the signature block. This is not bit-exact APK reproduction."
        status["runtime_dependency_components"] = len(dependencies["components"])
    except Exception:
        # No arbitrary tool exception, URL, local path, environment or build log
        # is placed in an uploaded report. Detailed logs stay on this runner.
        status["result"] = "incomplete"
        status["failure"] = "A required identity, rebuild or inventory stage failed. No verification attestation is permitted."
        raise
    finally:
        write_json(public / "verification.json", status)
        print(json.dumps({"version": args.version, "result": status["result"]}))
    return 0 if complete else 1


def attestation(args):
    require_main_context()
    expected = manifest(args.version)
    folder = args.reports.resolve()
    if sorted(item.name for item in folder.iterdir()) != sorted(REPORT_FILES):
        raise ValueError("Verification artifact must contain exactly the four approved JSON reports")
    reports = {}
    for name in REPORT_FILES:
        path = folder / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 8 * 1024 * 1024:
            raise ValueError("Invalid verification artifact file")
        reports[name] = json.loads(path.read_text(encoding="utf-8"))
    status, identity, comparison = (reports[name] for name in ("verification.json", "apk-identity.json", "comparison.json"))
    exact = comparison.get("exact_bytes_equal_after_removing_only_signing_block")
    expected_result = "pass" if exact is True else "content_verified_container_differs"
    if (type(exact) is not bool or status.get("result") != expected_result or status.get("repository") != REPOSITORY or
            status.get("version") != args.version or status.get("source_commit") != expected["source_commit"] or
            status.get("verification_tool_commit") != git_commit(HERE.parent) or
            status.get("apk_sha256") != expected["apk"]["sha256"] or
            identity.get("identity_result") != "pass" or identity.get("signature_verified") is not True or
            identity.get("sha256") != expected["apk"]["sha256"] or
            identity.get("certificate_sha256") != verify_apk.CERTIFICATE or
            identity.get("embedded_revision_matches_source") is not True or
            comparison.get("all_zip_entry_bytes_equal") is not True or comparison.get("ignored_entries") != [] or
            comparison.get("reference_apk_sha256") != expected["apk"]["sha256"] or
            comparison.get("source_commit") != expected["source_commit"] or
            comparison.get("reference_vcs_matches_source_commit") is not True or
            comparison.get("rebuilt_vcs_matches_source_commit") is not True):
        raise ValueError("Reports do not establish the exact reviewed release verification")
    if (entry_map(comparison.get("reference_entries")) != entry_map(comparison.get("rebuilt_entries")) or
            any(comparison.get(key) != [] for key in ("missing_entries", "added_entries", "changed_entries")) or
            not HEX.fullmatch(comparison.get("reference_without_signing_block_sha256", "")) or
            (comparison.get("reference_without_signing_block_sha256") == comparison.get("rebuilt_apk_sha256")) != exact or
            not HEX.fullmatch(comparison.get("rebuilt_apk_sha256", "")) or
            not HEX.fullmatch(comparison.get("selected_client_inputs_sha256", "")) or
            comparison.get("selected_apk_client_resource_names") != list(reproduce_apk.CLIENT_NAMES) or
            identity.get("source_commit") != expected["source_commit"] or
            identity.get("embedded_vcs_revision") != expected["source_commit"] or
            identity.get("version") != args.version or identity.get("bytes") != expected["apk"]["bytes"]):
        raise ValueError("Verification report hashes or metadata are inconsistent")
    sanitized_inventory(reports["dependencies.cdx.json"], args.version)
    predicate = {"schema": 1, "claim": "verification-of-existing-release-content",
                 "isBitExactReproduction": exact,
                 "isBuildProvenance": False, "repository": "https://github.com/" + REPOSITORY,
                 "releaseVersion": args.version, "releaseSourceCommit": expected["source_commit"],
                 "verificationToolCommit": git_commit(HERE.parent),
                 "publisherCertificateSha256": verify_apk.CERTIFICATE,
                 "checks": {"releaseDigest": "matched-reviewed-main-manifest", "apkSignature": "verified",
                            "permissions": "matched-reviewed-main-manifest", "allPackagedEntryBytes": "identical",
                            "rawApkBytesExcludingSigningBlock": "identical" if exact else "different", "sourceRevision": "matched",
                            "ignoredZipEntries": []},
                 "clientInputs": {"origin": "five-allowlisted-public-resource-values-from-reference-apk",
                                  "names": list(reproduce_apk.CLIENT_NAMES),
                                  "sha256": comparison["selected_client_inputs_sha256"]},
                 "reports": [{"name": name, "sha256": file_hash(folder / name)} for name in REPORT_FILES],
                 "limitations": ["This workflow did not produce or sign the downloaded release APK.",
                                  "When isBitExactReproduction is false, only packaged entry content and source metadata are matched; container differences remain visible in the reports.",
                                  "No malware-free certification or assertion about remote service behavior.",
                                  "Dependency inventory covers Android runtime inputs, not the entire build environment."]}
    write_json(args.predicate, predicate)
    workflow_outputs({"subject_name": expected["apk"]["filename"],
                      "subject_digest": "sha256:" + expected["apk"]["sha256"]})
    print(json.dumps({"version": args.version, "attestation_mode": "custom-verification", "eligible": True}))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("prepare", "verify", "attestation"):
        command = commands.add_parser(name)
        command.add_argument("--version", required=True)
        if name != "attestation":
            command.add_argument("--state", type=Path, required=True)
        if name == "verify":
            command.add_argument("--source-root", type=Path, required=True)
            command.add_argument("--sdk", type=Path, required=True)
        if name == "attestation":
            command.add_argument("--reports", type=Path, required=True)
            command.add_argument("--predicate", type=Path, required=True)
    args = parser.parse_args()
    try:
        return {"prepare": prepare, "verify": verify, "attestation": attestation}[args.command](args)
    except Exception as error:
        reason = str(error) if isinstance(error, ValueError) else type(error).__name__
        print("Release verification stopped: " + reason, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
