# History storage and JSON format

## Where it lives

`/data/data/com.mattia.calculator/files/history.json`, which is app-private storage, so no
permission is needed and other apps can't read it.

* Written after every change with `android.util.AtomicFile` (write a temp file, then rename), so a
  crash or power loss mid-write cannot corrupt it. An unreadable file is copied to
  `history.json.corrupt` instead of being overwritten.
* Capped at 10 000 entries (oldest dropped).
* **Android backup**: `data_extraction_rules.xml` / `backup_rules.xml` include only `history.json`
  in Google cloud backup and device-to-device transfer, not the ~9 MB Python runtime cache Chaquopy extracts.

## Export / import

Menu ⋮ → **Export history…** opens the system file picker (Storage Access Framework) with a name
like `calculator-history-20261122-1705.json`. It can save to Downloads, Drive, a USB stick, etc.
**Import history…** reads such a file and **merges** it by `id`, so importing twice doesn't
duplicate. Importing also accepts a bare JSON array of entries.

## Format (version 1)

```json
{
  "app": "Calculator",
  "format": 1,
  "exportedAt": "2026-11-22T17:05:12-03:00",
  "entries": [
    {
      "id": "0350c5c4-299d-4c56-895c-ea23a18c28c1",
      "input": "solve x^2=2",
      "rows": [
        { "t": "x = -sqrt(2),  x = sqrt(2)", "s": "Solve  x^2 = 2  for x", "c": "x = -sqrt(2),  x = sqrt(2)" },
        { "t": "x ≈ -1.414213562,  x ≈ 1.414213562", "s": "Decimal approximation", "c": "x ≈ -1.414213562,  x ≈ 1.414213562" }
      ],
      "ok": true,
      "timestamp": 1795377488793,
      "ms": 11
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `app`, `format` | file identification / schema version (readers ignore unknown fields) |
| `exportedAt` | ISO-8601 time of export; `null` in the internal file |
| `entries[]` | oldest first |
| `id` | random UUID, used to merge imports |
| `input` | exactly what was typed |
| `rows[]` | engine answer lines. `t` = title shown, `s` = subtitle (what was computed), `c` = copy value (also what `ans` uses) |
| `ok` | `false` for errors and timeouts (kept so you can fix and re-run them) |
| `timestamp` | Unix epoch milliseconds |
| `ms` | engine compute time |

The row format is the same `{t, s, c}` the Flow Launcher plugin uses, so the two can share tooling.
For example, all inputs (Nushell): `open calculator-history-*.json | get entries.input`.
