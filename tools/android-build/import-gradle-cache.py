#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1")
    args = parser.parse_args()
    destination = Path(__file__).resolve().parent / "maven"
    destination.mkdir(parents=True, exist_ok=True)
    copied = 0
    if args.source.is_dir():
        for artifact in sorted(args.source.glob("*/*/*/*/*")):
            if not artifact.is_file():
                continue
            group, module, version, _, name = artifact.relative_to(args.source).parts
            target = destination.joinpath(*group.split("."), module, version, name)
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                if digest(target) != digest(artifact):
                    raise SystemExit(f"Conflicting cached artifact: {group}:{module}:{version}/{name}")
                continue
            shutil.copy2(artifact, target)
            copied += 1
    for metadata in sorted(destination.rglob("*.module")):
        for variant in json.loads(metadata.read_text()).get("variants", []):
            for entry in variant.get("files", []):
                source = metadata.parent / entry["name"]
                target = (metadata.parent / entry["url"]).resolve()
                if not target.is_relative_to(destination):
                    raise SystemExit(f"Artifact URL escapes local Maven: {metadata}")
                if not source.is_file() or source == target:
                    continue
                expected = entry.get("sha256")
                if not expected or digest(source) != expected:
                    raise SystemExit(f"Module checksum mismatch: {source}")
                if target.exists():
                    if digest(target) != expected:
                        raise SystemExit(f"Conflicting Maven layout: {target}")
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
                copied += 1
    print(f"Local Maven: {destination}; copied {copied} artifacts")


if __name__ == "__main__":
    main()
