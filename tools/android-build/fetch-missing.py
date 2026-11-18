#!/usr/bin/env python3
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET


MIRRORS = (
    "https://mirrors.huaweicloud.com/repository/maven/",
    "https://maven.aliyun.com/repository/public/",
    "https://maven.aliyun.com/repository/google/",
)


def download(url):
    try:
        with urllib.request.urlopen(url, timeout=20) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def fetch(relative, optional=False):
    urls = [mirror + relative for mirror in MIRRORS]
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(download, url) for url in urls]
        results = []
        for url, future in zip(urls, futures):
            try:
                data = future.result()
                if data is not None:
                    results.append((url, data))
            except Exception as error:
                print(f"Unavailable: {url}: {type(error).__name__}")
    if not results and optional:
        return None, []
    huawei = [entry for entry in results if "mirrors.huaweicloud.com/" in entry[0]]
    aliyun = [entry for entry in results if "maven.aliyun.com/" in entry[0]]
    if not huawei or not aliyun:
        raise SystemExit(f"Cannot compare Huawei/Aliyun: {relative}")
    if any(data != results[0][1] for _, data in results):
        raise SystemExit(f"Mirror content mismatch: {relative}")
    return results[0][1], [url for url, _ in results]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("coordinates", nargs="+")
    args = parser.parse_args()
    tooling = Path(__file__).resolve().parent
    audit_path = tooling / "mirror-artifacts.json"
    audit = json.loads(audit_path.read_text()) if audit_path.exists() else {}
    for coordinate in args.coordinates:
        parts = coordinate.split(":")
        if len(parts) != 3 or any(not part or "/" in part or ".." in part for part in parts):
            raise SystemExit(f"Invalid coordinate: {coordinate}")
        group, module, version = parts
        stem = f"{group.replace('.', '/')}/{module}/{version}/{module}-{version}"
        pom, sources = fetch(stem + ".pom")
        xml = ET.fromstring(pom)
        packaging = xml.findtext("{http://maven.apache.org/POM/4.0.0}packaging", "jar")
        extensions = ["pom", "module"]
        if packaging != "pom":
            if packaging not in ("jar", "aar"):
                raise SystemExit(f"Unsupported packaging: {packaging}")
            extensions.append(packaging)
        for extension in extensions:
            relative = stem + "." + extension
            data, urls = (pom, sources) if extension == "pom" else fetch(relative, optional=extension == "module")
            if data is None:
                continue
            target = tooling / "maven" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists() and target.read_bytes() != data:
                raise SystemExit(f"Existing artifact differs: {relative}")
            target.write_bytes(data)
            audit[relative] = {"sha256": hashlib.sha256(data).hexdigest(), "compared_sources": urls}
            audit_path.write_text(json.dumps(audit, indent=2) + "\n")
            print(f"Verified and stored: {relative}")


if __name__ == "__main__":
    main()
