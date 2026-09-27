"""CI check after `gradle test`: the healer report exists and shows the heal and the plain-language step."""
import json, sys
r = json.load(open("build/healer-report/healing-report.json", encoding="utf-8"))
tests = {t["id"]: t["status"] for t in r["tests"]}
heals = [e for e in r["events"] if e.get("kind") is None and e.get("status") == "HEALED"]
found = [e for e in r["events"] if e.get("kind") == "intent" and e.get("status") == "HEALED"]
print("tests:", tests, "heals:", len(heals), "found by description:", len(found))
ok = tests == {"GradleHealingTest.healsRenamedButton": "PASSED", "GradleHealingTest.plainLanguageStep": "PASSED"} and heals and found
sys.exit(0 if ok else 1)
