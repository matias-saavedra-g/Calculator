"""Desktop test-suite for app/src/main/python/calc_engine.py.

Run (any CPython with sympy==1.14.0 / mpmath==1.3.0 installed):
    python engine-tests/test_engine.py            # assertions + summary
    python engine-tests/test_engine.py -v         # also print every result row
"""
import io
import json
import os
import sys
import time

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "python"))
sys.dont_write_bytecode = True   # keep app/src/main/python free of __pycache__
import calc_engine as E  # noqa: E402

VERBOSE = "-v" in sys.argv

# (query, substring expected in the FIRST row title)   -- None = just must not crash
CASES = [
    # ---- plain arithmetic / numbers
    ("2+2", "4"), ("1/3 + 1/6", "1/2"), ("2^10", "1024"), ("5!", "120"), ("sqrt(2)", "sqrt(2)"),
    ("15% of 200", "30"), ("0.1 + 0.2", "0.3"), ("10 mod 3", "1"), ("e^(i pi)", "-1"),
    ("(3+4i)(1-2i)", "11 - 2i"), ("log(8, 2)", "3"), ("ln e^3", "3"), ("|−5| + 2", "7"),
    ("what is 3 times 4 plus 2", "14"), ("255 in hex", "0xff"), ("pi to 30 digits", "3.14159265358979323846264338328"),
    # ---- solve
    ("2x + 2 = 0", "x = -1"), ("solve x^2 - 4 = 0", "x = -2,  x = 2"), ("solve x^2 = 4 for x", "x = -2"),
    ("x^2 + y^2 = 25, x - y = 1", "="), ("x^2 < 4", "-2 < x < 2"), ("solve 2^x = 1024", "x = 10"),
    ("3x + 2y = 12, x - y = 1", "x = 14/5"), ("sin(x) = 1/2", "x = π/6"),
    # ---- integrals: indefinite
    ("int x^2", "x^3/3 + C"), ("integrate x^2", "x^3/3 + C"), ("int sin x", "-cos(x) + C"),
    ("int x e^x dx", "+ C"), ("int 1/x", "log(x) + C"), ("int e^(-x^2)", "erf"),
    ("int x*y dy", "x*y^2/2 + C"), ("integral of 1/(1+x^2)", "atan(x) + C"),
    ("int x^2, x", "x^3/3 + C"),
    # ---- integrals: definite / improper / numeric
    ("int x^2 from 0 to 1", "1/3"), ("integrate x^2 dx from 0 to 1", "1/3"),
    ("int_0^1 x^2 dx", "1/3"), ("int_0^pi sin x", "2"), ("int_(0)^(pi/2) cos(x) dx", "1"),
    ("int x^2, x, 0, 3", "9"), ("int x^2 x=0..3", "9"), ("int x^2 from x=0 to 3", "9"),
    ("integral of e^(-x^2) from -infinity to infinity", "sqrt(π)"),
    ("int 1/x from 1 to oo", "∞"), ("int 1/x^2 from 1 to oo", "1"),
    ("int sin(x)/x from 0 to oo", "π/2"), ("int x^x from 0 to 1", "0.78343"),
    # ---- sums
    ("sum 1 to 100", "5050"), ("sum 1..100", "5050"), ("sum from 1 to 100", "5050"),
    ("sum of all numbers between 1 and 100", "5050"), ("sum 1 to 99 step 2", "2500"),
    ("sum 1 to 10^9", "500000000500000000"), ("sum -5 to 5", "0"), ("sum 10 to 1", "0"),
    ("sum i for i = 1 to 100", "5050"), ("sum of 1/n^2 from n=1 to infinity", "π^2/6"),
    ("sum k^2 from 1 to 10", "385"), ("sum k^2, k, 1, 10", "385"), ("sum n from n=1..10", "55"),
    ("sum 1, 2, 3, 4", "10"), ("sum [1, 2, 3]", "6"), ("prod 1 to 5", "120"),
    ("product of k from k=1 to 5", "120"), ("sum 1/2^n from n=0 to oo", "2"),
    # ---- derivatives / limits / series / algebra
    ("derivative of sin(x)^2", "sin(2x)"), ("d/dx x^3 e^x", "exp(x)"), ("second derivative of x^4 wrt x", "12x^2"),
    ("derivative of x^2 at x=3", "6"), ("limit of sin(x)/x as x->0", "1"), ("lim x->oo (1+1/x)^x", "e"),
    ("limit of 1/x as x -> 0+", "∞"), ("taylor series of cos(x)", "x^4/24"), ("factor x^2 - 1", "(x - 1)*(x + 1)"),
    ("factor 360", "2^3 × 3^2 × 5"), ("expand (x+1)^3", "x^3 + 3x^2 + 3x + 1"),
    ("simplify sin(x)^2 + cos(x)^2", "1"), ("partial fractions 1/(x^2-1)", "1/(2(x - 1))"),
    ("is 97 prime", "Yes"), ("det [[1,2],[3,4]]", "-2"), ("inverse of [[1,2],[3,4]]", "[[-2, 1]"),
    ("eigenvalues of [[2,0],[0,3]]", "2"),
    # ---- Wolfram Language syntax
    ("Integrate[x^2, x]", "x^3/3 + C"), ("Integrate[Sin[x]^2, {x, 0, Pi}]", "π/2"),
    ("D[x^3, x]", "3x^2"), ("D[Sin[x], {x, 2}]", "-sin(x)"), ("Solve[x^2 == 4, x]", "x = -2"),
    ("Solve[{x + y == 3, x - y == 1}, {x, y}]", "x = 2,  y = 1"), ("Sum[k, {k, 1, 100}]", "5050"),
    ("Sum[1/n^2, {n, 1, Infinity}]", "π^2/6"), ("Limit[Sin[x]/x, x -> 0]", "1"),
    ("Factor[x^2 - 1]", "(x - 1)*(x + 1)"), ("Expand[(x + 1)^2]", "x^2 + 2x + 1"),
    ("N[Pi, 20]", "3.1415926535897932385"), ("N[Sqrt[2]]", "1.41421356237"), ("PrimeQ[97]", "Yes"),
    ("FactorInteger[360]", "2^3 × 3^2 × 5"), ("Det[{{1, 2}, {3, 4}}]", "-2"),
    ("Series[Exp[x], {x, 0, 3}]", "x^3/6"), ("1 + Integrate[x, {x, 0, 2}]", "3"), ("Sqrt[16] + Sin[0]", "4"),
    ("Product[k, {k, 1, 5}]", "120"),
    # ---- phone-friendly function application
    ("sinx", "sin(x)"), ("int sinx", "-cos(x) + C"), ("d/dx lnx", "1/x"), ("cos2x", "cos(2x)"),
    ("sinhx", "sinh(x)"), ("sinh(1)", "sinh(1)"), ("int x sinx dx", "+ C"), ("sinx + cosx", "sin(x) + cos(x)"),
    ("sqrtx", "sqrt(x)"), ("x + sin(x)", "x + sin(x)"),
    # ---- unicode input
    ("∫ x^2", "x^3/3 + C"), ("√16", "4"), ("2×3÷4", "3/2"),
]

# Things that must FAIL gracefully (ok == False, no exception)
BAD = ["garbage (((", "int", "sum", "Integrate[x^2", "ans + 1"]


def first(q, ans=""):
    data = json.loads(E.evaluate_json(q, ans))
    return data


def main():
    fails = 0
    t_all = time.time()
    for q, want in CASES:
        t = time.time()
        d = first(q)
        ms = (time.time() - t) * 1000
        rows = d["rows"]
        title = rows[0]["t"] if rows else "<no rows>"
        ok = d["ok"] and rows and (want is None or want in title)
        if not ok:
            fails += 1
        if VERBOSE or not ok:
            print("%s %-48s (%5.0f ms)" % ("  " if ok else "✗ ", q, ms))
            for r in rows[:6]:
                print("       %-56s | %s" % (r["t"][:56], r["s"][:60]))
            if not ok:
                print("       expected title to contain: %r" % want)
    for q in BAD:
        d = first(q)
        if d["ok"]:
            fails += 1
            print("✗ should have failed: %r -> %s" % (q, d["rows"]))
    # ans substitution
    d = first("ans * 2", ans="21")
    if not (d["ok"] and d["rows"][0]["t"] == "42"):
        fails += 1
        print("✗ ans substitution:", d)
    # quick mode must not compute extras
    d = json.loads(E.evaluate_json("x^2 - 5x + 6", "", True))
    if any("Derivative" in r["s"] for r in d["rows"]):
        fails += 1
        print("✗ quick mode computed extras")
    d = json.loads(E.evaluate_json("x^2 - 5x + 6", "", False))
    if not any("Derivative" in r["s"] for r in d["rows"]):
        fails += 1
        print("✗ full mode missing extras")
    print("\n%d cases, %d failures, %.1f s" % (len(CASES) + len(BAD) + 3, fails, time.time() - t_all))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
