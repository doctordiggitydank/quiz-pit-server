#!/usr/bin/env python3
"""Builds element/src/main/resources/questions.json from the Quiz Pit trivia
Google Sheet (CSV export). Sheet layout (line 1 is labels, skipped):
  A=category  B=question  C-F=choices  G=correct answer (as text)

Usage:
  curl -sL "<SHEET_EXPORT_URL>/export?format=csv" -o /tmp/trivia.csv
  python3 tools/build-questions.py /tmp/trivia.csv element/src/main/resources/questions.json
"""
import csv
import json
import sys

def norm(s: str) -> str:
    return " ".join(s.strip().split()).casefold()

def main(csv_path: str, out_path: str) -> None:
    questions = []
    skipped = []
    with open(csv_path, newline="", encoding="utf-8-sig") as f:
        reader = csv.reader(f)
        rows = list(reader)
    for row_no, row in enumerate(rows[1:], start=2):  # line 1 = labels
        cells = [c.strip() for c in row]
        if len(cells) < 7 or not cells[0]:
            continue
        category, text, choices, correct = cells[0], cells[1], cells[2:6], cells[6]
        if not text or any(not c for c in choices):
            skipped.append((row_no, "missing category/question/choices"))
            continue
        if len(set(choices)) != len(choices):
            skipped.append((row_no, "duplicate choices"))
            continue
        matches = [i for i, c in enumerate(choices) if norm(c) == norm(correct)]
        if not matches:
            skipped.append((row_no, f"correct answer {correct!r} matches no choice"))
            continue
        if len(matches) > 1:
            skipped.append((row_no, "correct answer matches multiple choices"))
            continue
        questions.append({
            "id": f"q{row_no:04d}",
            "category": category,
            "text": text,
            "choices": choices,
            "correctIndex": matches[0],
        })
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({"questions": questions}, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"ok: {len(questions)} questions -> {out_path}")
    if skipped:
        print(f"skipped {len(skipped)} row(s):")
        for row_no, why in skipped[:20]:
            print(f"  line {row_no}: {why}")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
