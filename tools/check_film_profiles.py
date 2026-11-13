#!/usr/bin/env python3
"""Check every measured film/paper mapping, native profile and scalar packet field."""
from pathlib import Path
import hashlib
import re
import struct

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/scamera/app/processing/color"
CPP = ROOT / "app/src/main/cpp"


def strings(text, name):
    match = re.search(r"\b" + name + r"\s*=\s*\{(.*?)\};", text, re.S)
    assert match, name
    return re.findall(r'"([^"\n]+)"', match.group(1))


def check():
    packet = (JAVA / "FilmReferenceParameters.java").read_text(encoding="utf-8")
    stocks = (JAVA / "FilmSim.java").read_text(encoding="utf-8")
    native = (CPP / "third_party/spektrafilm/SpektraGeneratedProfileCurves.cpp").read_text(encoding="utf-8")
    decode = (CPP / "film-reference-params.h").read_text(encoding="utf-8")
    films, papers = strings(packet, "FILMS"), strings(packet, "PAPERS")
    assert len(films) == len(set(films)) == 20
    assert len(papers) == len(set(papers)) == 8
    measured = re.findall(r'new Stock\("([^"]+)",\s*"[^"]+",\s*"[^"]+",\s*(NEGATIVE|CINE|SLIDE)', stocks)
    assert set(films) == {name for name, _ in measured}, "Missing or duplicate measured stock"
    film_table = re.search(r"ProfileCurveSet film_profiles\[\]\s*=\s*\{(.*?)\n\};", native, re.S).group(1)
    paper_table = re.search(r"ProfileCurveSet paper_profiles\[\]\s*=\s*\{(.*?)\n\};", native, re.S).group(1)
    assert re.findall(r'\{\s*"([^"]+)"', film_table) == films, "Native film pointer-table order"
    assert re.findall(r'\{\s*"([^"]+)"', paper_table) == papers, "Native paper pointer-table order"
    for index, row in enumerate(re.findall(r'\{([^{}]+)\}', film_table)):
        for field in ("log_sensitivity", "density_curves", "channel_density", "base_density"):
            assert f"film_{index}_{films[index]}_{field}" in row, f"Wrong profile data pointer: {films[index]} / {field}"
    fingerprints = {}
    for index, film in enumerate(films):
        arrays = {}
        for field in ("log_sensitivity", "density_curves", "channel_density", "base_density"):
            name = f"film_{index}_{film}_{field}"
            match = re.search(r"float " + name + r"\[\]\s*=\s*\{(.*?)\};", native, re.S)
            assert match, name
            values = [float(value.rstrip("f")) for value in match.group(1).replace(",", " ").split()]
            assert values, name
            arrays[field] = b"".join(struct.pack("<f", value) for value in values)
        fingerprint = hashlib.sha256(b"".join(arrays.values())).hexdigest()
        assert fingerprint not in fingerprints, f"Identical native profiles: {film} / {fingerprints.get(fingerprint)}"
        fingerprints[fingerprint] = film
    for index, paper in enumerate(papers):
        assert re.search(r"float paper_" + str(index) + "_" + paper + r"_density_curves\[\]", native), paper
    defaults = re.findall(r"data\.put(Int|Float)\((\d+),[^\n]+?\);\s*//\s*(\w+)", packet)
    assert len(defaults) == 162 and {int(offset) for _, offset, _ in defaults} == set(range(0, 648, 4))
    for kind, offset, field in defaults:
        if field == "enabled":
            continue  # The native render entry point itself is the enabled gate.
        native_kind = "Int" if kind == "Int" else "Float"
        expected = r"params\." + field + r"\s*=.*?packet" + native_kind + r"\(data," + offset + r"\)"
        assert re.search(expected, decode), f"Scalar ABI mismatch: {field} at {offset}"
    print(f"Film profiles: {len(films)} unique native stocks, {len(papers)} papers, 162 scalar fields mapped")


if __name__ == "__main__":
    check()
