#!/usr/bin/env python3

import argparse
import copy
import re
import shutil
import sys
import xml.etree.ElementTree as ET

from collections import OrderedDict
from pathlib import Path

ET.register_namespace(
    "android", "http://schemas.android.com/apk/res/android"
)
ET.register_namespace(
    "tools", "http://schemas.android.com/tools"
)
ET.register_namespace(
    "xliff", "urn:oasis:names:tc:xliff:document:1.2"
)


def resource_key(element):
    """Identify a values XML resource by its Android resource type/name."""
    tag = element.tag
    name = element.get("name")

    if tag in ("public", "java-symbol"):
        return ("metadata", tag, element.get("type"), name)

    if tag == "public-group":
        return ("metadata", tag, element.get("type"))

    if tag == "item":
        return ("resource", element.get("type"), name)

    if name is not None:
        return ("resource", tag, name)

    # Preserve un-named metadata without treating unrelated elements
    # as equivalent resources.
    return ("unnamed", ET.tostring(element, encoding="unicode"))


def read_resource_dirs(bp_path):
    text = bp_path.read_text(encoding="utf-8")

    match = re.search(
        r"\bresource_dirs\s*:\s*\[(.*?)\]",
        text,
        flags=re.DOTALL,
    )

    if not match:
        raise RuntimeError("Could not find resource_dirs in Android.bp")

    return re.findall(r'"([^"]+)"', match.group(1))


def merge_resources(bp_path, output_dir):
    resource_dirs = read_resource_dirs(bp_path)
    repository = bp_path.parent

    if not resource_dirs:
        raise RuntimeError("No resource directories were found")

    # The resource_dirs list is taken directly from Android.bp.
    # Later directories override earlier ones.
    files = OrderedDict()
    value_groups = OrderedDict()
    file_overrides = 0
    value_overrides = 0

    for relative_dir in resource_dirs:
        source_dir = repository / relative_dir

        if not source_dir.is_dir():
            raise RuntimeError(
                f"Resource directory does not exist: {source_dir}"
            )

        print(f"Reading: {relative_dir}")

        for path in sorted(source_dir.rglob("*")):
            if not path.is_file():
                continue

            relative = path.relative_to(source_dir)
            parts = relative.parts

            is_values_xml = (
                len(parts) >= 2
                and (
                    parts[0] == "values"
                    or parts[0].startswith("values-")
                )
                and path.suffix.lower() == ".xml"
            )

            if is_values_xml:
                qualifier_dir = parts[0]

                try:
                    root = ET.parse(path).getroot()
                except ET.ParseError as exc:
                    raise RuntimeError(
                        f"Invalid XML in {path}: {exc}"
                    ) from exc

                if root.tag != "resources":
                    raise RuntimeError(
                        f"Expected a <resources> root in {path}"
                    )

                group = value_groups.setdefault(
                    qualifier_dir,
                    {
                        "attributes": OrderedDict(),
                        "items": OrderedDict(),
                    },
                )

                for key, value in root.attrib.items():
                    local_name = key.rsplit("}", 1)[-1]

                    # tools:keep and tools:discard contain comma-separated
                    # lists, so combine these lists rather than losing
                    # values declared in earlier resource directories.
                    if (
                        local_name in ("keep", "discard")
                        and key in group["attributes"]
                    ):
                        existing = group["attributes"][key]
                        group["attributes"][key] = ",".join(
                            part for part in (existing, value) if part
                        )
                    else:
                        group["attributes"][key] = value

                for element in root:
                    key = resource_key(element)

                    if key in group["items"]:
                        value_overrides += 1

                    # Preserve the latest definition for this resource
                    # key while retaining unique resources from all roots.
                    group["items"][key] = copy.deepcopy(element)

            else:
                # Android file resources are identified by their resource
                # directory (including qualifiers) and basename, not by
                # their file extension.
                filename = relative.name

                if filename.endswith(".9.png"):
                    resource_name = filename[:-len(".9.png")]
                else:
                    resource_name = path.stem

                key = (relative.parent.as_posix(), resource_name)

                if key in files:
                    previous_relative, previous_path, previous_source_dir = files[key]

                    if previous_source_dir == source_dir:
                        raise RuntimeError(
                            "Duplicate file-based resource in one source "
                            f"directory: {previous_path} and {path}"
                        )

                    file_overrides += 1

                files[key] = (relative, path, source_dir)

    # Never write generated files into the original source resource trees.
    output_dir = output_dir.resolve()

    if output_dir == repository.resolve():
        raise RuntimeError("Refusing to overwrite the repository root")

    if output_dir.exists():
        shutil.rmtree(output_dir)

    output_dir.mkdir(parents=True)

    # Copy regular file-based resources; later resource roots win.
    for relative_path, source_path, _source_dir in files.values():
        destination = output_dir / relative_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_path, destination)

    # Consolidate all values XML files per qualifier into one XML file.
    for qualifier_dir, group in value_groups.items():
        destination_dir = output_dir / qualifier_dir
        destination_dir.mkdir(parents=True, exist_ok=True)

        root = ET.Element(
            "resources",
            attrib=dict(group["attributes"]),
        )

        for element in group["items"].values():
            root.append(copy.deepcopy(element))

        # An attribute can be referenced by multiple styleables, but its
        # format/enum/flag definition must only be emitted once.
        attr_definitions = {}

        for styleable in root.findall("declare-styleable"):
            for attr in styleable.findall("attr"):
                name = attr.get("name")

                if not name or name.startswith("android:"):
                    continue

                definition_attributes = {
                    key: value
                    for key, value in attr.attrib.items()
                    if key != "name"
                }
                child_definitions = [
                    ET.tostring(child, encoding="unicode")
                    for child in attr
                ]

                # Plain <attr name="..."/> entries are references, not
                # definitions. Keep them so every styleable retains its
                # own attribute index.
                if not definition_attributes and not child_definitions:
                    continue

                signature = (
                    tuple(sorted(definition_attributes.items())),
                    tuple(child_definitions),
                )

                if name not in attr_definitions:
                    attr_definitions[name] = signature
                    continue

                if attr_definitions[name] != signature:
                    raise RuntimeError(
                        "Conflicting definitions of attribute "
                        f"{name!r} in values/{qualifier_dir}"
                    )

                # Retain the attribute reference in this styleable, but
                # remove the duplicate format/enum/flag definition.
                attr.attrib.clear()
                attr.set("name", name)

                # Remove duplicate format/enum/flag details while
                # retaining the required attribute name.
                for child in list(attr):
                    attr.remove(child)

        ET.indent(root, space="    ")

        ET.ElementTree(root).write(
            destination_dir / "merged.xml",
            encoding="utf-8",
            xml_declaration=True,
        )

    print()
    print(f"Resource directories: {len(resource_dirs)}")
    print(f"File overlays applied: {file_overrides}")
    print(f"Values resource overrides: {value_overrides}")
    print(f"Generated resource directory: {output_dir}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bp", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    try:
        merge_resources(args.bp.resolve(), args.output)
    except Exception as exc:
        print(f"Resource merge failed: {exc}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
