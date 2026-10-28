"""Validate the OpenAPI snapshot against this repository's Java HTTP contracts.

No application/database startup is needed. For the complete OpenAPI 3.0 schema
validation, also install openapi-spec-validator in an isolated Python environment.
"""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "src/main/java/xyz/v185v/collegeassistant"


def source_text(path):
    text = path.read_text(encoding="utf-8")
    tokens = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*.*?\*/|//[^\n]*', re.S)
    return tokens.sub(lambda m: m[0] if m[0].startswith(('"', "'")) else " ", text)


def balanced_end(text, start):
    depth = 0
    quote = None
    escaped = False
    for i in range(start, len(text)):
        ch = text[i]
        if quote:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == quote:
                quote = None
            continue
        if ch in ('"', "'"):
            quote = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
    raise ValueError("Unbalanced Java declaration")


def split_fields(text):
    parts = []
    start = 0
    depth = 0
    quote = None
    escaped = False
    for i, ch in enumerate(text):
        if quote:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == quote:
                quote = None
            continue
        if ch in ('"', "'"):
            quote = ch
        elif ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append(text[start:i].strip())
            start = i + 1
    if text[start:].strip():
        parts.append(text[start:].strip())
    return parts


def strip_annotations(text):
    while True:
        match = re.search(r"@[\w.]+", text)
        if not match:
            return text.strip()
        end = match.end()
        tail = end
        while tail < len(text) and text[tail].isspace():
            tail += 1
        if tail < len(text) and text[tail] == "(":
            end = balanced_end(text, tail) + 1
        text = text[:match.start()] + text[end:]


def typed_field(raw):
    clean = strip_annotations(raw)
    match = re.fullmatch(r"(.+)\s+(\w+)", clean, re.S)
    if not match:
        raise ValueError(f"Unhandled Java field: {raw}")
    return re.sub(r"\s+", "", match[1]), match[2]


def contracts():
    records = {}
    enums = {}
    routes = {}
    for path in sorted(JAVA_ROOT.rglob("*.java")):
        text = source_text(path)
        for match in re.finditer(r"public record\s+(\w+)(?:<[^>]+>)?\s*\(", text):
            end = balanced_end(text, match.end() - 1)
            fields = {}
            for raw in split_fields(text[match.end():end]):
                if "@JsonIgnore" in raw:
                    continue
                typ, name = typed_field(raw)
                fields[name] = {"type": typ, "raw": raw}
            records[match[1]] = {"fields": fields, "path": str(path.relative_to(ROOT)).replace("\\", "/")}
        for match in re.finditer(r"public enum\s+(\w+)\s*\{", text):
            end = text.index("}", match.end())
            head = text[match.end():end].split(";", 1)[0].strip()
            values = [value.strip() for value in head.split(",") if value.strip()]
            if all(re.fullmatch(r"[A-Z][A-Z_0-9]*", value) for value in values):
                enums[match[1]] = values
        if not path.name.endswith("Controller.java"):
            continue
        base = re.search(r'@RequestMapping\("([^"]+)"\)', text)
        if not base:
            raise ValueError(f"Unhandled controller mapping: {path}")
        for match in re.finditer(r"@(Get|Post|Put|Patch|Delete)Mapping\b", text):
            offset = match.end()
            while text[offset].isspace():
                offset += 1
            mapping = ""
            if text[offset] == "(":
                end = balanced_end(text, offset)
                mapping = text[offset + 1:end]
                offset = end + 1
            subpath = re.search(r'"([^"]+)"', mapping)
            subpath = subpath[1] if subpath else ""
            signature = re.search(r"\bpublic\s+(\S+)\s+(\w+)\s*\(", text[offset:])
            if not signature:
                raise ValueError(f"Missing handler signature: {path}")
            parameter_start = offset + signature.end()
            parameter_end = balanced_end(text, parameter_start - 1)
            parameters = {}
            request = None
            for raw in split_fields(text[parameter_start:parameter_end]):
                typ, field = typed_field(raw)
                where = "path" if "@PathVariable" in raw else "query" if "@RequestParam" in raw else None
                if where:
                    explicit = re.search(r'@(?:PathVariable|RequestParam)\(\s*(?:value\s*=\s*)?"([^"]+)"', raw)
                    name = explicit[1] if explicit else field
                    parameters[(where, name)] = {"type": typ, "raw": raw}
                if "@RequestBody" in raw:
                    request = {"type": typ, "required": "required = false" not in raw}
            routes[(match[1].lower(), base[1] + subpath)] = {
                "return": signature[1], "handler": signature[2], "parameters": parameters,
                "request": request, "mapping": mapping,
                "path": str(path.relative_to(ROOT)).replace("\\", "/"),
            }
    return records, enums, routes


def resolve(schema, spec):
    if "$ref" not in schema:
        return schema
    return spec["components"]["schemas"][schema["$ref"].rsplit("/", 1)[1]]


def validate_source(spec):
    records, enums, routes = contracts()
    schemas = spec["components"]["schemas"]
    actual = {(method, path) for path, operations in spec["paths"].items() for method in operations}
    assert actual == set(routes), f"Route mismatch: missing={set(routes)-actual}, extra={actual-set(routes)}"
    checked_records = 0
    for name, record in records.items():
        if name not in schemas or name == "ApiResponse":
            continue
        properties = schemas[name].get("properties")
        if properties is None:
            continue
        fields = record["fields"]
        assert set(properties) == set(fields), f"{name} fields differ: {set(properties)^set(fields)}"
        checked_records += 1
        for field, declaration in fields.items():
            schema = resolve(properties[field], spec)
            typ = declaration["type"]
            if typ in ("int", "Integer", "long", "Long"):
                fmt = "int32" if typ in ("int", "Integer") else "int64"
                assert schema.get("type") == "integer" and schema.get("format") == fmt, f"{name}.{field} integer format differs"
            elif typ == "String":
                assert schema.get("type") == "string", f"{name}.{field} type differs"
            elif typ in ("boolean", "Boolean"):
                assert schema.get("type") == "boolean", f"{name}.{field} type differs"
            elif typ == "OffsetDateTime":
                assert schema.get("format") == "date-time", f"{name}.{field} time format differs"
            elif typ.startswith("List<"):
                assert schema.get("type") == "array", f"{name}.{field} list type differs"
            elif typ == "char[]":
                assert schema.get("type") == "string", f"{name}.{field} secret must be a JSON string"
    for name, values in enums.items():
        if name in schemas:
            assert schemas[name].get("enum") == values, f"{name} enum differs"
    ids = []
    for (method, path), route in routes.items():
        operation = spec["paths"][path][method]
        ids.append(operation["operationId"])
        parameters = {(p["in"], p["name"]): p for p in operation.get("parameters", [])}
        expected = dict(route["parameters"])
        multipart = "MULTIPART_FORM_DATA_VALUE" in route["mapping"]
        if multipart:
            form = operation["requestBody"]["content"]["multipart/form-data"]["schema"]
            assert set(form["properties"]) == {name for where, name in expected}
            expected = {}
        assert set(parameters) == set(expected), f"{method} {path} parameters differ"
        for key, declaration in expected.items():
            schema = resolve(parameters[key]["schema"], spec)
            typ = declaration["type"]
            if typ in ("int", "Integer", "long", "Long"):
                fmt = "int32" if typ in ("int", "Integer") else "int64"
                assert schema.get("format") == fmt, f"{method} {path} {key} format differs"
            default = re.search(r'defaultValue\s*=\s*"([^"]+)"', declaration["raw"])
            if default:
                value = default[1]
                value = int(value) if typ == "int" else value == "true" if typ == "boolean" else value
                assert schema.get("default") == value, f"{method} {path} {key} default differs"
        request = route["request"]
        if request:
            actual_body = operation["requestBody"]
            assert actual_body["required"] == request["required"], f"{path} request body requirement differs"
            assert actual_body["content"]["application/json"]["schema"]["$ref"].endswith("/" + request["type"]), f"{path} request type differs"
        elif not multipart:
            assert "requestBody" not in operation, f"{path} unexpected request body"
        returned = route["return"]
        content = operation["responses"]["200"]["content"]
        if returned.startswith("ApiResponse<"):
            assert "application/json" in content
            envelope = resolve(content["application/json"]["schema"], spec)
            data = envelope["properties"]["data"]
            typ = returned[len("ApiResponse<"):-1]
            if typ == "Void":
                assert data.get("enum") == [None], f"{path} must return null data"
            elif typ.startswith("List<"):
                assert data["items"]["$ref"].endswith("/" + typ[5:-1]), f"{path} list response differs"
            elif typ.startswith("PageResult<"):
                pg = resolve(data, spec)
                assert pg["properties"]["items"]["items"]["$ref"].endswith("/" + typ[11:-1]), f"{path} page response differs"
            else:
                assert data["$ref"].endswith("/" + typ), f"{path} response type differs"
        elif returned == "SseEmitter":
            assert "text/event-stream" in content
        elif returned == "ResponseEntity<StreamingResponseBody>":
            assert any(value["schema"].get("format") == "binary" for value in content.values())
        else:
            raise AssertionError(f"Unhandled return type: {returned}")
    assert len(ids) == len(set(ids)), "Duplicate operationId"
    ref_count = 0

    def walk(value):
        nonlocal ref_count
        if isinstance(value, dict):
            if "$ref" in value:
                ref_count += 1
                assert len(value) == 1, f"OpenAPI 3.0 reference has ignored siblings: {value}"
                target = spec
                for key in value["$ref"][2:].split("/"):
                    target = target[key]
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)
    walk(spec)
    return {"controllers": 18, "paths": len(spec["paths"]), "operations": len(routes), "java_records": checked_records, "references": ref_count}


if __name__ == "__main__":
    spec = json.loads((ROOT / "docs/openapi.json").read_text(encoding="utf-8"))
    print(json.dumps(validate_source(spec), ensure_ascii=False))
    try:
        from openapi_spec_validator import validate
    except ImportError:
        print("Source contracts passed; openapi-spec-validator is not installed.")
    else:
        validate(spec)
        print("OpenAPI 3.0 schema validation passed.")
