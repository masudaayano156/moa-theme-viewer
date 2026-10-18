#!/usr/bin/env python3
"""Compare a release APK with a build of its public Android source.

Current source builds without Firebase resources, client configuration, or relay
project variables. For historical source containing firebase-config.gradle, only
five client-side string resources are copied from the reference APK into temporary
build input. The APK is never installed or executed. No signing key, service
account, server credential, or original google-services.json is needed.
Python 3.10+, Git, JDK 17, Android SDK command-line tools and the checked-in Gradle
Wrapper are required for `build`. The `compare` command only requires Python.
"""
from __future__ import annotations

import argparse
from contextlib import nullcontext
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit
import zipfile


CLIENT_NAMES = ("google_app_id", "google_api_key", "gcm_defaultSenderId",
                "project_id", "moa_push_endpoint")
VCS_ENTRY = "META-INF/version-control-info.textproto"
MAX_APK_BYTES = 128 * 1024 * 1024
MAX_UNCOMPRESSED_BYTES = 512 * 1024 * 1024


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def run(command: list[str], *, cwd: Path | None = None,
        env: dict[str, str] | None = None, timeout: int = 60) -> subprocess.CompletedProcess:
    """No shell is used, including when launching Gradle on Windows."""
    return subprocess.run(command, cwd=cwd, env=env, capture_output=True,
                          text=True, encoding="utf-8", errors="replace",
                          timeout=timeout, check=False)


def checked_output(command: list[str], description: str, **kwargs) -> str:
    result = run(command, **kwargs)
    if result.returncode:
        # Tool output can contain build inputs. Never include it in an exception.
        raise ValueError(description + " failed")
    return result.stdout.strip()


def load_apk(path: Path, expected_sha256: str | None = None) -> bytes:
    if not path.is_file() or not 0 < path.stat().st_size <= MAX_APK_BYTES:
        raise ValueError("APK is missing, empty, or larger than 128 MiB")
    data = path.read_bytes()
    if expected_sha256 and sha256(data) != expected_sha256.lower():
        raise ValueError("Reference APK does not match the expected SHA-256")
    return data


def without_apk_signing_block(data: bytes) -> tuple[bytes, int]:
    """Remove only the v2/v3 APK signing block and repair the EOCD offset.

    No ZIP entry is removed, replaced, normalized, or ignored. In particular,
    META-INF VCS information and any v1 signature entries remain in comparison.
    """
    eocd = data.rfind(b"PK\x05\x06", max(0, len(data) - 65557))
    if (eocd < 0 or eocd + 22 > len(data) or
            eocd + 22 + struct.unpack_from("<H", data, eocd + 20)[0] != len(data)):
        raise ValueError("Unsupported ZIP end record")
    disk, directory_disk, disk_count, count = struct.unpack_from("<HHHH", data, eocd + 4)
    directory_size, directory = struct.unpack_from("<II", data, eocd + 12)
    if (disk or directory_disk or disk_count != count or count == 65535 or
            directory == 0xffffffff or directory_size == 0xffffffff or
            directory + directory_size != eocd):
        raise ValueError("Multi-disk, ZIP64, or nonstandard APK ZIP is unsupported")
    if directory < 24 or data[directory - 16:directory] != b"APK Sig Block 42":
        return data, 0
    block_size = struct.unpack_from("<Q", data, directory - 24)[0] + 8
    start = directory - block_size
    if (block_size < 32 or start < 0 or
            struct.unpack_from("<Q", data, start)[0] + 8 != block_size):
        raise ValueError("Malformed APK signing block")
    stripped = bytearray(data[:start] + data[directory:])
    struct.pack_into("<I", stripped, eocd - block_size + 16, start)
    return bytes(stripped), block_size


def zip_entries(path: Path) -> tuple[dict[str, dict], dict[str, str | None]]:
    with zipfile.ZipFile(path) as archive:
        files = archive.infolist()
        names = [entry.filename for entry in files]
        if (len(files) > 4096 or len(set(names)) != len(names) or
                sum(entry.file_size for entry in files) > MAX_UNCOMPRESSED_BYTES):
            raise ValueError("APK has duplicate entries or excessive unpacked size")
        if any(entry.flag_bits & 1 for entry in files):
            raise ValueError("Encrypted APK ZIP entry is unsupported")
        entries = {entry.filename: {"sha256": sha256(archive.read(entry)),
                                   "bytes": entry.file_size}
                   for entry in files}
        vcs = {"revision": None, "generation_error": None}
        if VCS_ENTRY in entries:
            if entries[VCS_ENTRY]["bytes"] > 65536:
                raise ValueError("Unexpectedly large VCS metadata")
            value = archive.read(VCS_ENTRY).decode("utf-8", errors="strict")
            revisions = re.findall(r'\brevision:\s*"([0-9a-f]{40,64})"', value)
            if len(revisions) == 1:
                vcs["revision"] = revisions[0]
            error = re.search(r"\bgenerate_error_reason:\s*([A-Z_]+)", value)
            if error:
                vcs["generation_error"] = error.group(1)
        return entries, vcs


def compare(apk: Path, rebuilt: Path, expected_hash: str | None = None) -> dict:
    reference_bytes = load_apk(apk, expected_hash)
    rebuilt_bytes = load_apk(rebuilt)
    reference_entries, reference_vcs = zip_entries(apk)
    rebuilt_entries, rebuilt_vcs = zip_entries(rebuilt)
    missing = sorted(set(reference_entries) - set(rebuilt_entries))
    added = sorted(set(rebuilt_entries) - set(reference_entries))
    changed = sorted(name for name in reference_entries.keys() & rebuilt_entries.keys()
                     if reference_entries[name] != rebuilt_entries[name])
    stripped, signature_bytes = without_apk_signing_block(reference_bytes)
    _, rebuilt_signature_bytes = without_apk_signing_block(rebuilt_bytes)
    return {
        "reference_apk_sha256": sha256(reference_bytes),
        "rebuilt_apk_sha256": sha256(rebuilt_bytes),
        "reference_without_signing_block_sha256": sha256(stripped),
        "reference_signing_block_bytes": signature_bytes,
        "rebuilt_signing_block_bytes": rebuilt_signature_bytes,
        "reference_entry_count": len(reference_entries),
        "rebuilt_entry_count": len(rebuilt_entries),
        "identical_entry_count": len(reference_entries.keys() & rebuilt_entries.keys()) - len(changed),
        "missing_entries": missing, "added_entries": added, "changed_entries": changed,
        "all_zip_entry_bytes_equal": not (missing or added or changed),
        "exact_bytes_equal_after_removing_only_signing_block": stripped == rebuilt_bytes,
        "ignored_entries": [],
        "reference_vcs": reference_vcs, "rebuilt_vcs": rebuilt_vcs,
        "reference_entries": reference_entries, "rebuilt_entries": rebuilt_entries,
        "signature_verification": "not_performed_by_compare",
        "meaning": "Equality verifies these build inputs and packaged bytes; it does not audit remote services."
    }


def output_directory(path: Path) -> Path:
    path = path.resolve()
    if path.exists():
        raise ValueError("Use a new output directory; existing data is never overwritten")
    path.mkdir(parents=True)
    return path


def java_executable(java_home: str | None) -> Path:
    if not java_home:
        raise ValueError("Set JAVA_HOME to JDK 17 or pass --java-home")
    executable = Path(java_home).resolve() / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not executable.is_file():
        raise ValueError("Java executable was not found under JAVA_HOME")
    return executable


def normalized_jdk(version_output: str) -> dict[str, str]:
    # JAVA_TOOL_OPTIONS and launcher warnings can contain local values. Keep
    # only a validated version token and a fixed, non-user-controlled vendor.
    match = re.search(r'(?m)^(?:openjdk|java) version "(17(?:\.[0-9]+){0,3}(?:\+[0-9]+)?)"',
                      version_output)
    if not match:
        raise ValueError("This reproducibility build requires JDK 17")
    vendor = "unknown"
    for token, name in (("Temurin", "Eclipse Adoptium"), ("Corretto", "Amazon"),
                        ("Microsoft", "Microsoft"), ("Zulu", "Azul"),
                        ("Java(TM)", "Oracle"), ("OpenJDK", "OpenJDK")):
        if token in version_output:
            vendor = name
            break
    return {"version": match.group(1), "vendor": vendor}


def reject_ignored_sources(source: Path) -> int:
    # The repository can deliberately ignore everything except an allowlist.
    # A clean `git status` therefore does not prove absence of extra source.
    ignored = checked_output(["git", "-C", str(source), "ls-files", "--others", "--ignored",
                              "--exclude-standard", "-z"], "Ignored source check")
    paths = [value for value in ignored.split("\0") if value]
    if paths:
        raise ValueError("Preexisting ignored files can alter a build; use a fresh checkout")
    return len(paths)


def public_client_inputs(apk: Path, sdk: Path, java: Path) -> tuple[dict, str]:
    tools = sdk / "cmdline-tools/latest"
    analyzer = tools / "lib/apkanalyzer-classpath.jar"
    if not analyzer.is_file():
        raise ValueError("Install Android SDK cmdline-tools;latest")

    def analyze(*arguments: str) -> str:
        return checked_output([str(java), "-Dcom.android.sdklib.toolsdir=" + str(tools),
                               "-classpath", str(analyzer),
                               "com.android.tools.apk.analyzer.ApkAnalyzerCli",
                               *arguments, str(apk)], "APK resource extraction")

    package = analyze("manifest", "application-id")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", package):
        raise ValueError("Reference APK application ID is invalid")
    values = {name: analyze("resources", "value", "--config", "default", "--type", "string",
                            "--name", name) for name in CLIENT_NAMES}
    patterns = {
        "google_app_id": r"1:[0-9]+:android:[A-Za-z0-9]+",
        "google_api_key": r"[A-Za-z0-9_-]{30,80}",
        "gcm_defaultSenderId": r"[0-9]+",
        "project_id": r"[a-z][a-z0-9-]{4,62}",
        "moa_push_endpoint": r"https://[a-z0-9-]+\.[a-z0-9-]+\.workers\.dev"
    }
    if any(not re.fullmatch(patterns[name], value) for name, value in values.items()):
        raise ValueError("Selected APK client resources are missing or malformed")
    parsed = urlsplit(values["moa_push_endpoint"])
    if parsed.username or parsed.password or parsed.port or parsed.path or parsed.query or parsed.fragment:
        raise ValueError("APK relay endpoint must be a bare HTTPS workers.dev origin")
    return values, package


def verify_signature(apk: Path, sdk: Path, java: Path, build_tools: str) -> dict:
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", build_tools):
        raise ValueError("Invalid --build-tools version")
    verifier = sdk / "build-tools" / build_tools / "lib/apksigner.jar"
    if not verifier.is_file():
        raise ValueError("Install the selected Android SDK Build Tools")
    result = run([str(java), "-jar", str(verifier), "verify", "--verbose", "--print-certs", str(apk)])
    if result.returncode:
        raise ValueError("Reference APK signature verification failed")
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})", result.stdout)
    if not certificates:
        raise ValueError("Reference APK signer certificate could not be read")
    return {"verified": True, "certificate_sha256": certificates, "tool": "Android SDK apksigner",
            "build_tools": build_tools}


def rebuild(args: argparse.Namespace, out: Path) -> dict:
    apk, source, sdk = args.apk.resolve(), args.source_root.resolve(), args.sdk.resolve()
    load_apk(apk, args.expected_apk_sha256)
    if not source.is_dir() or not sdk.is_dir():
        raise ValueError("Source root or Android SDK directory does not exist")
    if out.is_relative_to(source):
        raise ValueError("Keep --output outside --source-root")
    commit = checked_output(["git", "-C", str(source), "rev-parse", "HEAD"], "Git commit check")
    if not re.fullmatch(r"[0-9a-f]{40,64}", commit):
        raise ValueError("Source HEAD must be an exact Git commit")
    if args.expected_source_commit and commit != args.expected_source_commit:
        raise ValueError("Source HEAD differs from --expected-source-commit")
    if checked_output(["git", "-C", str(source), "status", "--porcelain", "--untracked-files=normal"],
                      "Source check"):
        raise ValueError("Use a clean source checkout without modified or untracked source files")
    if (source / "local.properties").exists():
        raise ValueError("Use a fresh checkout without local.properties; --sdk selects the SDK")
    allowed_generated_files = reject_ignored_sources(source)
    wrapper = source / "gradle/wrapper/gradle-wrapper.jar"
    properties = source / "gradle/wrapper/gradle-wrapper.properties"
    if not wrapper.is_file() or not properties.is_file():
        raise ValueError("The checked-in Gradle Wrapper is required")
    wrapper_properties = properties.read_text(encoding="utf-8")
    if not re.search(r"(?m)^distributionSha256Sum=[0-9a-f]{64}\s*$", wrapper_properties):
        raise ValueError("Pin distributionSha256Sum in the Gradle Wrapper properties")
    java = java_executable(args.java_home)
    version = run([str(java), "-version"])
    java_version = version.stderr + version.stdout
    if version.returncode:
        raise ValueError("Java version check failed")
    jdk = normalized_jdk(java_version)
    signature = verify_signature(apk, sdk, java, args.build_tools)
    legacy_client_inputs = (source / "firebase-config.gradle").is_file()
    client = {}
    config = None
    if legacy_client_inputs:
        client, package = public_client_inputs(apk, sdk, java)
        config = {
            "project_info": {"project_number": client["gcm_defaultSenderId"], "project_id": client["project_id"]},
            "client": [{"client_info": {"mobilesdk_app_id": client["google_app_id"],
                                         "android_client_info": {"package_name": package}},
                        "api_key": [{"current_key": client["google_api_key"]}]}]
        }
    environment = os.environ.copy()
    for name in list(environment):
        if name in {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "GRADLE_OPTS"} or name.startswith("ORG_GRADLE_PROJECT_"):
            environment.pop(name, None)
    environment["JAVA_HOME"] = str(java.parent.parent)
    environment["ANDROID_HOME"] = str(sdk)
    environment["ANDROID_SDK_ROOT"] = str(sdk)
    gradle_home = args.gradle_user_home.resolve() if args.gradle_user_home else out / "gradle-user-home"
    if any((gradle_home / name).exists() for name in ("init.gradle", "init.gradle.kts", "gradle.properties")) or (
            (gradle_home / "init.d").is_dir() and any((gradle_home / "init.d").rglob("*"))):
        raise ValueError("Gradle user home contains extra configuration; use a cache without init scripts or properties")
    environment["GRADLE_USER_HOME"] = str(gradle_home)
    temporary_inputs = (tempfile.TemporaryDirectory(prefix="apk-client-inputs-", dir=out)
                        if legacy_client_inputs else nullcontext(None))
    with temporary_inputs as temporary:
        configuration = None
        if legacy_client_inputs:
            configuration = Path(temporary) / "google-services.json"
            configuration.write_text(json.dumps(config), encoding="utf-8")
            environment["ORG_GRADLE_PROJECT_moaFirebaseConfig"] = str(configuration)
            environment["ORG_GRADLE_PROJECT_moaPushEndpoint"] = client["moa_push_endpoint"]
        command = [str(java), "-Dorg.gradle.appname=gradlew", "-classpath", str(wrapper),
                   "org.gradle.wrapper.GradleWrapperMain", "--no-daemon", "--no-build-cache",
                   "--no-configuration-cache", "--project-cache-dir", str(out / "project-cache"),
                   "--rerun-tasks", ":app:assembleRelease"]
        if args.offline:
            command.insert(-1, "--offline")
        process = run(command, cwd=source, env=environment, timeout=args.build_timeout)
        log = process.stdout + process.stderr
        redacted_values = [*client.values()]
        if configuration is not None:
            redacted_values.append(str(configuration))
        for value in sorted(redacted_values, key=len, reverse=True):
            log = log.replace(value, "[APK-derived client value]")
        (out / "build.log").write_text(log, encoding="utf-8")
        if process.returncode:
            raise ValueError("Unsigned release build failed; inspect the redacted build.log")
    rebuilt = source / "app/build/outputs/apk/release/app-release-unsigned.apk"
    report = compare(apk, rebuilt, args.expected_apk_sha256)
    if report["rebuilt_signing_block_bytes"]:
        raise ValueError("Expected an unsigned release build; check source signing configuration")
    report.update({
        "source_commit": commit,
        "reference_vcs_matches_source_commit": report["reference_vcs"]["revision"] == commit,
        "rebuilt_vcs_matches_source_commit": report["rebuilt_vcs"]["revision"] == commit,
        "signature_verification": signature,
        "client_input_mode": "legacy_apk_resources" if legacy_client_inputs else "none_required",
        "selected_apk_client_resource_names": list(CLIENT_NAMES) if legacy_client_inputs else [],
        "selected_client_inputs_sha256": (sha256(json.dumps(client, sort_keys=True).encode())
                                          if legacy_client_inputs else None),
        "wrapper_jar_sha256": sha256(wrapper.read_bytes()),
        "wrapper_properties_sha256": sha256(properties.read_bytes()),
        "jdk": jdk, "gradle_build_cache_used": False,
        "allowed_preexisting_generated_file_count": allowed_generated_files,
        "extra_gradle_user_configuration_allowed": False,
        "inherited_jvm_and_gradle_project_options_removed": True,
        "configuration_cache_used": False, "all_tasks_rerun": True,
        "offline_dependencies": args.offline,
        "temporary_client_inputs_created": legacy_client_inputs,
        "temporary_client_inputs_deleted": legacy_client_inputs,
        "signing_key_required": False, "server_credentials_required": False,
        "limitations": ["Dependency downloads/cache must be independently trusted or verified.",
                        "JDK/SDK/dependency versions can affect reproducibility.",
                        "Packaged-byte equality does not prove remote server behavior or absence of all bugs."]
                       + (["Historical APK client settings are intentionally taken from the reference APK."]
                          if legacy_client_inputs else [])
    })
    return report


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build", help="Build public source and compare; extract client inputs only for historical Firebase source")
    comparison = commands.add_parser("compare", help="Compare two already-built APK files")
    for command in (build, comparison):
        command.add_argument("--apk", type=Path, required=True, help="Reference release APK")
        command.add_argument("--output", type=Path, required=True, help="New directory for JSON report")
        command.add_argument("--expected-apk-sha256", help="Expected digest from the release")
    comparison.add_argument("--rebuilt-apk", type=Path, required=True)
    build.add_argument("--source-root", type=Path, required=True, help="Clean Git checkout of the release commit")
    build.add_argument("--sdk", type=Path, required=True)
    build.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    build.add_argument("--build-tools", default="35.0.0")
    build.add_argument("--expected-source-commit")
    build.add_argument("--gradle-user-home", type=Path)
    build.add_argument("--offline", action="store_true")
    build.add_argument("--build-timeout", type=int, default=1800)
    args = parser.parse_args()
    if args.expected_apk_sha256 and not re.fullmatch(r"[0-9a-fA-F]{64}", args.expected_apk_sha256):
        parser.error("--expected-apk-sha256 must be a SHA-256 hex digest")
    return args


def main() -> int:
    args = arguments()
    out = output_directory(args.output)
    report = (rebuild(args, out) if args.command == "build" else
              compare(args.apk, args.rebuilt_apk, args.expected_apk_sha256))
    (out / "comparison.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    # The full file contains per-entry hashes. Terminal output stays compact.
    summary = {key: value for key, value in report.items()
               if key not in ("reference_entries", "rebuilt_entries")}
    print(json.dumps(summary, indent=2))
    return 0 if report["exact_bytes_equal_after_removing_only_signing_block"] else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.TimeoutExpired, zipfile.BadZipFile) as error:
        print("Reproducibility check failed: " + (str(error) if isinstance(error, ValueError) else type(error).__name__), file=sys.stderr)
        sys.exit(2)
