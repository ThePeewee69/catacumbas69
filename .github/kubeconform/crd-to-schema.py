#!/usr/bin/env python3
"""Turns one version of a CustomResourceDefinition into the JSON schema kubeconform reads.

    python3 .github/kubeconform/crd-to-schema.py <crd.yaml> <version> > <group>/<kind>_<version>.json

Like `kubectl --validate=strict`, an object with `properties` rejects any other field, except inside allOf, anyOf,
oneOf and not, where the CRD only states conditions on some fields (adding the rule there would reject valid
objects). An int-or-string field accepts both.

kubeconform applies no defaults, so a `not` on a field that is left out would always fail (the API server would
have filled in the default first). Each `not` therefore applies only when its fields are present. Defaults and
the CEL rules (x-kubernetes-validations) are not checked: this catches typos, missing and badly typed fields.
"""
import json
import sys

import yaml

LOGIC = ("allOf", "anyOf", "oneOf", "not")


def convert(node, strict=True):
    if isinstance(node, list):
        return [convert(item, strict) for item in node]
    if not isinstance(node, dict):
        return node
    out = {}
    for key, value in node.items():
        if value is None:
            continue
        if key == "not" and isinstance(value, dict) and "properties" in value and "required" not in value:
            out[key] = convert({**value, "required": sorted(value["properties"])}, strict=False)
        elif key in LOGIC:
            out[key] = convert(value, strict=False)
        elif key == "properties":
            out[key] = {name: convert(prop, strict) for name, prop in value.items()}
        else:
            out[key] = convert(value, strict)
    if out.pop("x-kubernetes-int-or-string", False):
        out.pop("type", None)
        out.pop("anyOf", None)
        out["oneOf"] = [{"type": "string"}, {"type": "integer"}]
    if out.pop("nullable", False) and isinstance(out.get("type"), str):
        out["type"] = [out["type"], "null"]
    preserve = out.pop("x-kubernetes-preserve-unknown-fields", False)
    if strict and "properties" in out and "additionalProperties" not in out and not preserve:
        out["additionalProperties"] = False
    return out


def main():
    crd_path, version = sys.argv[1], sys.argv[2]
    with open(crd_path, encoding="utf-8") as f:
        crd = next(doc for doc in yaml.safe_load_all(f) if doc and doc.get("kind") == "CustomResourceDefinition")
    spec = next(v for v in crd["spec"]["versions"] if v["name"] == version)
    schema = convert(spec["schema"]["openAPIV3Schema"])
    schema["$schema"] = "http://json-schema.org/draft-07/schema#"
    json.dump(schema, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
