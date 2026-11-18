#!/usr/bin/env python3
import argparse
import hashlib
import json
import pathlib
import subprocess
import sys
import sysconfig

ROOT = pathlib.Path(__file__).resolve().parent
PACKAGES = {
    "python3-cairosvg": "2.8.2-1",
    "python3-cairocffi": "1.7.1-5",
    "python3-cffi": "2.0.0-3build1",
    "python3-cssselect2": "0.9.0-1",
    "python3-tinycss2": "1.5.1-1",
    "python3-defusedxml": "0.7.1-3build1",
    "python3-webencodings": "0.5.1-5build1",
    "python3-pycparser": "3.0-1",
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    if pathlib.Path(sys.prefix).resolve() != ROOT / "venv":
        raise SystemExit("Run with tools/icons/venv/bin/python")
    packages = ROOT / "packages"
    vendor = ROOT / "vendor"
    packages.mkdir(exist_ok=True)
    vendor.mkdir(exist_ok=True)
    pins = [f"{name}={version}" for name, version in PACKAGES.items()]
    if args.download:
        subprocess.run([
            "apt-get", "-o", "Acquire::http::Timeout=5", "-o",
            "Acquire::https::Timeout=5", "-o", "Acquire::Retries=0",
            "download", *pins,
        ], cwd=packages, check=True, timeout=60)
    saved_metadata = packages / "apt-records.txt"
    metadata = (saved_metadata.read_text() if saved_metadata.exists() and not args.download
                else subprocess.check_output(["apt-cache", "show", *pins], text=True))
    records = []
    for paragraph in metadata.strip().split("\n\n"):
        fields = dict(line.split(": ", 1) for line in paragraph.splitlines()
                      if ": " in line and not line.startswith(" "))
        if fields.get("Package") not in PACKAGES:
            continue
        if fields.get("Version") != PACKAGES[fields["Package"]]:
            raise SystemExit("Unexpected APT version")
        filename = pathlib.PurePosixPath(fields["Filename"]).name
        archive = packages / filename
        with archive.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if digest != fields["SHA256"] or archive.stat().st_size != int(fields["Size"]):
            raise SystemExit(f"APT hash/size mismatch: {filename}")
        records.append({
            "package": fields["Package"], "version": fields["Version"],
            "file": filename, "size": int(fields["Size"]), "sha256": digest,
            "apt_filename": fields["Filename"],
        })
    if len(records) != len(PACKAGES) or len({r["package"] for r in records}) != len(PACKAGES):
        raise SystemExit("Missing or duplicate APT records")
    for record in records:
        subprocess.run(["dpkg-deb", "-x", str(packages / record["file"]), str(vendor)], check=True)
    site = pathlib.Path(sysconfig.get_path("purelib"))
    (site / "icons_vendor.pth").write_text("../../../../vendor/usr/lib/python3/dist-packages\n")
    (packages / "apt-records.txt").write_text(metadata)
    (packages / "requirements-apt.lock").write_text("\n".join(pins) + "\n")
    (packages / "SHA256SUMS").write_text("".join(f"{r['sha256']}  {r['file']}\n" for r in records))
    manifest = {
        "method": "APT download and dpkg-deb extraction, no system installation",
        "packages": records,
        "python_path": "vendor/usr/lib/python3/dist-packages",
        "venv_path": "venv/bin/python",
        "verification": {
            "sha256": "Every archive matched the SHA256 and size from local APT indices before extraction",
            "limits": [
                "Existing local APT indices were used; no apt update or fresh InRelease signature verification was performed",
                "No independent PyPI JSON or second-source hash verification; PyPI timed out after 5 seconds",
                "APT downloads used the configured Ubuntu HTTP repository; archive integrity was checked against local APT hashes",
                "This is a minimal runtime extraction, not a complete Debian package installation",
                "Unused Debian dependencies lxml, xcffib and tinycss2 test data were not downloaded",
                "System Python, Pillow, NumPy, CFFI backend and Cairo remain external dependencies and may change on OS updates",
            ],
        },
        "system_packages": subprocess.check_output([
            "dpkg-query", "-W", "-f=${Package}\t${Version}\t${Architecture}\n",
            "libcairo2", "python3-cffi-backend", "python3-pil", "python3-numpy",
            "python3-setuptools", "libgdk-pixbuf-2.0-0",
        ], text=True).splitlines(),
    }
    (packages / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Verified and extracted {len(records)} packages; connected {site / 'icons_vendor.pth'}")


if __name__ == "__main__":
    main()
