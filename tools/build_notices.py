#!/usr/bin/env python3
"""Build app/src/main/assets/licenses.txt (shown in Settings -> Open-source licences) from
THIRD_PARTY_NOTICES.md plus the full licence texts in licenses/. Re-run after editing either."""
import pathlib
import re

root = pathlib.Path(__file__).resolve().parent.parent
md = (root / "THIRD_PARTY_NOTICES.md").read_text()
# Light markdown -> plain text: links become "text (url)", drop emphasis and heading marks.
txt = re.sub(r"\[([^\]]+)\]\((https?://[^)]+)\)", r"\1 (\2)", md)
txt = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r"\1", txt)
txt = re.sub(r"[*`]", "", txt)
txt = re.sub(r"^#+ ", "", txt, flags=re.M)
txt = re.sub(r"^> ?", "    ", txt, flags=re.M)
txt = txt.replace("<", "").replace(">", "")

head = ("Reev is free software: you can redistribute it and/or modify it under the terms of the GNU Affero "
        "General Public License, version 3 only (AGPL-3.0-only).\n"
        "Source code: https://github.com/asispan/reev\n"
        "Full licence: https://www.gnu.org/licenses/agpl-3.0.txt\n\n")
parts = [head, txt.strip(), ""]
for name in ["Apache-2.0.txt", "onnxruntime-MIT.txt", "Unicode-3.0.txt"]:
    parts += ["\n" + "=" * 60, name.removesuffix(".txt"), "=" * 60, (root / "licenses" / name).read_text().strip()]
parts += ["\n" + "=" * 60, "CC-BY-SA-4.0", "=" * 60,
          "Full text: https://creativecommons.org/licenses/by-sa/4.0/legalcode (also in licenses/CC-BY-SA-4.0.txt in the source)."]
out = root / "app/src/main/assets/licenses.txt"
out.write_text("\n".join(parts) + "\n")
print(out, out.stat().st_size, "bytes")
