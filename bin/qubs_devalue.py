"""
Parse the "devalue" serialised data emitted by React Router 7 (the
Qubs menu app at qubs.app uses it for the initial page payload).

Devalue is a flat array where:
  * primitives are literals (strings, numbers, true/false/null);
  * `{"_N": M}` is an object reference (key `_N`, value at array index M);
  * `[a, b, c]` is a 3-element array of those values;
  * "Date", "Map", "Set", "RegExp" markers signal a tagged type;
  * numbers that are not inside an object reference are also just values.

We resolve the index references against the same array (forward
references allowed) and return the resulting plain JSON tree.

Spec: https://github.com/Rich-Harris/devalue
"""
from __future__ import annotations

from typing import Any, Iterable


def parse(payload: list) -> Any:
    """Resolve a devalue-serialised payload to a plain Python object."""
    return _resolve(payload, 0, payload)


def _resolve(arr: list, idx: int, root: list) -> Any:
    value = arr[idx]
    if isinstance(value, dict):
        # Tagged types: {"Date": "2026-..."} etc. We don't need any of
        # them for menu scraping, so return the inner string.
        if len(value) == 1:
            (k, v), = value.items()
            if k == "Date":
                return root[v] if isinstance(v, int) else v
            if k.startswith("_"):
                return _resolve(arr, v, root)
            if k == "Map":
                # {"Map": [keys, values]} — at the canonical devalue shape.
                keys_idx, vals_idx = value["Map"]
                keys = _resolve(arr, keys_idx, root)
                vals = _resolve(arr, vals_idx, root)
                return dict(zip(keys, vals))
        return {k: (_resolve(arr, v, root) if isinstance(v, int) else v)
                for k, v in value.items()}
    if isinstance(value, list):
        return [_resolve(arr, v, root) if isinstance(v, int) else v for v in value]
    return value


def find_all(payload: list, type_name: str) -> Iterable[dict]:
    """Yield every object whose `__typename` is `type_name`."""
    arr = payload
    for i, v in enumerate(arr):
        if isinstance(v, dict) and v.get("__typename") == type_name:
            yield _resolve(arr, i, arr)
