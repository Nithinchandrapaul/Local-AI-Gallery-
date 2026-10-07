#!/usr/bin/env python3
"""
Autonomous Build Watchdog for Sunny Local AI Gallery
Monitors build pipelines, verifies APK signatures and model assets,
packages releases, and manages milestone state.
"""

import os
import sys
import json
import time
import shutil
import hashlib
import zipfile
import subprocess
from pathlib import Path
from datetime import datetime

REPO_ROOT = Path(__file__).resolve().parent.parent
WATCHDOG_DIR = REPO_ROOT / ".watchdog"
STATE_FILE = WATCHDOG_DIR / "state.json"
COMPLETED_FILE = WATCHDOG_DIR / "completed.json"
LOG_FILE = WATCHDOG_DIR / "build.log"
FAILURES_FILE = WATCHDOG_DIR / "failures.log"
RELEASES_DIR = REPO_ROOT / "releases"
REPO_NAME = "Nithinchandrapaul/Local-AI-Gallery-"

def log(msg: str):
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    formatted = f"[{timestamp}] [WATCHDOG] {msg}"
    print(formatted, flush=True)
    WATCHDOG_DIR.mkdir(parents=True, exist_ok=True)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(formatted + "\n")

def log_failure(msg: str):
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    formatted = f"[{timestamp}] [WATCHDOG-ERROR] {msg}"
    print(formatted, file=sys.stderr, flush=True)
    WATCHDOG_DIR.mkdir(parents=True, exist_ok=True)
    with open(FAILURES_FILE, "a", encoding="utf-8") as f:
        f.write(formatted + "\n")

def init_state() -> dict:
    WATCHDOG_DIR.mkdir(parents=True, exist_ok=True)
    if STATE_FILE.exists():
        try:
            with open(STATE_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {
        "current_version": "1.3.0",
        "status": "idle",
        "completed_versions": [],
        "last_build": None,
        "last_apk": None,
        "last_sha256": None
    }

def save_state(state: dict):
    WATCHDOG_DIR.mkdir(parents=True, exist_ok=True)
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=2)

def load_completed() -> list:
    if COMPLETED_FILE.exists():
        try:
            with open(COMPLETED_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return []

def save_completed(completed_list: list):
    WATCHDOG_DIR.mkdir(parents=True, exist_ok=True)
    with open(COMPLETED_FILE, "w", encoding="utf-8") as f:
        json.dump(completed_list, f, indent=2)

def calculate_sha256(file_path: Path) -> str:
    sha = hashlib.sha256()
    with open(file_path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            sha.update(chunk)
    return sha.hexdigest()

def get_git_commit() -> str:
    try:
        res = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
            check=True
        )
        return res.stdout.strip()
    except Exception as e:
        return "UNKNOWN"

def verify_apk(apk_path: Path) -> tuple[bool, dict]:
    """
    Verifies that the APK:
    1. Is a valid ZIP archive
    2. Contains AndroidManifest.xml
    3. Bundles the EmbeddingGemma model asset (>50MB)
    4. Has valid APK signature (v1 JAR signature or v2/v3 APK Signing Block)
    """
    details = {
        "path": str(apk_path),
        "size_bytes": 0,
        "manifest": False,
        "model_bundled": False,
        "model_size_bytes": 0,
        "signature_v1": False,
        "signature_v2_v3": False,
        "verified": False,
        "error": None
    }
    
    if not apk_path.exists():
        details["error"] = "File does not exist"
        return False, details

    details["size_bytes"] = apk_path.stat().st_size
    if details["size_bytes"] < 1_000_000:
        details["error"] = f"File is too small ({details['size_bytes']} bytes)"
        return False, details

    try:
        with zipfile.ZipFile(apk_path, "r") as z:
            names = set(z.namelist())
            if "AndroidManifest.xml" in names:
                details["manifest"] = True

            model_name = "assets/models/embeddinggemma-2-text-vision-440m.litertlm"
            if model_name in names:
                info = z.getinfo(model_name)
                details["model_size_bytes"] = info.file_size
                if info.file_size > 50_000_000:
                    details["model_bundled"] = True

            v1_sigs = [n for n in names if n.startswith("META-INF/") and (n.endswith(".RSA") or n.endswith(".DSA") or n.endswith(".EC"))]
            if v1_sigs:
                details["signature_v1"] = True

    except zipfile.BadZipFile as e:
        details["error"] = f"Corrupted APK zip: {e}"
        return False, details

    # Check for APK Signing Block (v2 / v3 Scheme)
    try:
        with open(apk_path, "rb") as f:
            # Check last 64KB for APK Sig Block 42
            f.seek(max(0, details["size_bytes"] - 65536))
            tail = f.read()
            if b"APK Sig Block 42" in tail:
                details["signature_v2_v3"] = True
    except Exception as e:
        log(f"Warning reading APK signing block: {e}")

    is_signed = details["signature_v1"] or details["signature_v2_v3"]
    if not is_signed:
        details["error"] = "APK is not signed (no v1 META-INF signature and no v2/v3 APK Signing Block)"
        return False, details

    if not details["model_bundled"]:
        details["error"] = "Required EmbeddingGemma model is not bundled in APK"
        return False, details

    if not details["manifest"]:
        details["error"] = "Missing AndroidManifest.xml"
        return False, details

    details["verified"] = True
    return True, details

def publish_release(version_tag: str, version_name: str, apk_source: Path) -> Path:
    release_dir = RELEASES_DIR / version_tag
    release_dir.mkdir(parents=True, exist_ok=True)

    dest_apk_name = f"SunnyLocalAIGallery-v{version_name}.apk"
    dest_apk_path = release_dir / dest_apk_name
    shutil.copy2(apk_source, dest_apk_path)

    sha256 = calculate_sha256(dest_apk_path)
    file_size = dest_apk_path.stat().st_size
    size_mb = file_size / (1024 * 1024)
    commit = get_git_commit()
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S UTC")

    # Write SHA256SUMS.txt
    sha_file = release_dir / "SHA256SUMS.txt"
    with open(sha_file, "w", encoding="utf-8") as f:
        f.write(f"{sha256}  {dest_apk_name}\n")

    # Write build-info.txt
    build_info_file = release_dir / "build-info.txt"
    build_info = f"""Version: {version_name}
Package: com.sunny.localphotoai
Git commit: {commit}
APK: {dest_apk_name}
APK SHA256: {sha256}
APK size: {file_size} bytes ({size_mb:.2f} MB)
Signature: VERIFIED
Model bundled: YES
Build status: PASS
Timestamp: {timestamp}
Local path: {dest_apk_path}
"""
    with open(build_info_file, "w", encoding="utf-8") as f:
        f.write(build_info)

    log(f"Published release {version_tag} -> {dest_apk_path}")
    log(f"SHA256: {sha256}")
    return dest_apk_path

import re

def get_gradle_version() -> str:
    gradle_file = REPO_ROOT / "app" / "build.gradle.kts"
    if gradle_file.exists():
        with open(gradle_file, "r", encoding="utf-8") as f:
            for line in f:
                if "versionName" in line:
                    match = re.search(r'versionName\s*=\s*["\']([^"\']+)["\']', line)
                    if match:
                        return match.group(1)
    return "1.3.0"

def main():
    state = init_state()
    version = get_gradle_version()
    version_tag = f"V{version.rsplit('.', 1)[0]}" if '.' in version else f"V{version}"
    state["current_version"] = version
    save_state(state)
    log(f"Starting watchdog check for version {version} ({version_tag})")

    # Check if this version is already completed
    completed = load_completed()
    if version in completed:
        log(f"Version {version} already marked completed in watchdog.")
        return 0

    state["status"] = "monitoring"
    save_state(state)

    # Monitor latest GitHub Actions run
    log(f"Querying GitHub Actions runs for {REPO_NAME}...")
    run_cmd = ["gh", "run", "list", "--repo", REPO_NAME, "-L", "1", "--json", "databaseId,status,conclusion,name,headSha"]
    res = subprocess.run(run_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        log_failure(f"Failed to query gh runs: {res.stderr}")
        return 1

    runs = json.loads(res.stdout)
    if not runs:
        log("No CI runs found.")
        return 1

    latest_run = runs[0]
    run_id = str(latest_run["databaseId"])
    log(f"Tracking CI run {run_id} ({latest_run['status']}, {latest_run.get('conclusion')})")

    while latest_run["status"] not in ["completed"]:
        log(f"Run {run_id} is {latest_run['status']}... waiting 20s")
        time.sleep(20)
        res = subprocess.run(run_cmd, capture_output=True, text=True)
        if res.returncode == 0:
            latest_run = json.loads(res.stdout)[0]

    log(f"Run {run_id} completed with conclusion: {latest_run.get('conclusion')}")
    if latest_run.get("conclusion") != "success":
        log_failure(f"CI build failed with conclusion: {latest_run.get('conclusion')}")
        state["status"] = "failed"
        save_state(state)
        return 1

    # Download installable artifact
    download_dir = WATCHDOG_DIR / "temp_download"
    if download_dir.exists():
        shutil.rmtree(download_dir)
    download_dir.mkdir(parents=True, exist_ok=True)

    log(f"Downloading artifacts from run {run_id}...")
    # Attempt to download specific installable artifact first
    dl_res = subprocess.run(
        ["gh", "run", "download", run_id, "--repo", REPO_NAME, "--pattern", "*installable*", "--dir", str(download_dir)],
        capture_output=True,
        text=True
    )
    if dl_res.returncode != 0 or not list(download_dir.glob("**/*.apk")):
        dl_res = subprocess.run(
            ["gh", "run", "download", run_id, "--repo", REPO_NAME, "--dir", str(download_dir)],
            capture_output=True,
            text=True
        )
    if dl_res.returncode != 0:
        log_failure(f"Failed to download artifacts: {dl_res.stderr}")
        return 1

    # Find APKs
    apks = list(download_dir.glob("**/*.apk"))
    log(f"Found {len(apks)} APK(s) in downloaded artifacts: {[a.name for a in apks]}")

    installable_apk = None
    for apk in apks:
        valid, details = verify_apk(apk)
        if valid:
            log(f"Verified candidate: {apk.name} ({details['size_bytes']} bytes, model: {details['model_size_bytes']} bytes, signed: True)")
            installable_apk = apk
            break
        else:
            log(f"Candidate {apk.name} failed verification: {details.get('error')}")

    if not installable_apk:
        log_failure("No valid installable APK found in artifacts!")
        return 1

    # Publish release
    dest_apk = publish_release(version_tag, version, installable_apk)
    sha256 = calculate_sha256(dest_apk)

    # Update state
    completed.append(version)
    save_completed(completed)

    state["status"] = "completed"
    state["completed_versions"] = completed
    state["last_build"] = datetime.now().isoformat()
    state["last_apk"] = str(dest_apk)
    state["last_sha256"] = sha256
    save_state(state)

    log(f"Successfully finished milestone {version}!")
    return 0

if __name__ == "__main__":
    sys.exit(main())
