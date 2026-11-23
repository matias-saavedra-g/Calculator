"""Natural-language + Wolfram-style math engine on top of SymPy.

Ported from the Flow Launcher "Solve" plugin (engine.py) and extended for the
Android Calculator app. See docs/ENGINE.md for the full description.

Public API (called from Kotlin through Chaquopy, see EngineService.kt):
    evaluate_json(text, ans="", quick=False) -> JSON string
        {"ok": bool, "rows": [{"t": title, "s": subtitle, "c": copy}], "ms": int}
    warmup() -> "ready"

`quick=True` is used for the live preview while typing: it skips the expensive
"extras" (roots / derivative / integral of a bare expression).
`strict` (kept from the plugin) only answers input that clearly looks like math.
"""
import json
import re
import sys
import time

import sympy as sp
from sympy.parsing.sympy_parser import (
    parse_expr, standard_transformations, implicit_multiplication,
    implicit_application, function_exponentiation, convert_xor, rationalize,
    split_symbols_custom, _token_splittable,
)

# ---------------------------------------------------------------- namespace
_FUNCS = """sin cos tan cot sec csc asin acos atan acot asec acsc atan2 sinh cosh
tanh coth sech csch asinh acosh atanh acoth exp log sqrt cbrt root Abs sign floor
ceiling factorial factorial2 binomial gamma loggamma beta zeta erf erfc erfinv Min
Max gcd lcm Mod re im arg conjugate fibonacci lucas bernoulli prime primepi
isprime totient divisors nextprime prevprime factorint LambertW besselj bessely
Heaviside DiracDelta Piecewise Matrix Rational Integer Float Symbol Function
Sum Product Integral Derivative Limit Eq""".split()
NS = {n: getattr(sp, n) for n in _FUNCS}
NS.update({
    "pi": sp.pi, "E": sp.E, "I": sp.I, "oo": sp.oo, "zoo": sp.zoo, "nan": sp.nan,
    "e": sp.E, "i": sp.I, "inf": sp.oo, "tau": 2 * sp.pi, "phi": sp.GoldenRatio,
    "deg": sp.pi / 180, "EulerGamma": sp.EulerGamma, "Catalan": sp.Catalan,
    "ln": sp.log, "lg": lambda x: sp.log(x, 10), "log10": lambda x: sp.log(x, 10),
    "log2": lambda x: sp.log(x, 2), "abs": sp.Abs, "ceil": sp.ceiling,
    "sgn": sp.sign, "max": sp.Max, "min": sp.Min, "mod": sp.Mod,
    "arcsin": sp.asin, "arccos": sp.acos, "arctan": sp.atan, "arccot": sp.acot,
    "arcsinh": sp.asinh, "arccosh": sp.acosh, "arctanh": sp.atanh,
    "choose": sp.binomial, "nCr": sp.binomial, "ncr": sp.binomial,
    "npr": lambda n, k: sp.factorial(n) / sp.factorial(n - k), "nPr": lambda n, k: sp.factorial(n) / sp.factorial(n - k),
    "fib": sp.fibonacci, "__builtins__": {},
})
MATH_WORDS = {k for k in NS if k.islower() and not k.startswith("_")}
NL_WORDS = set("""of for from to as with respect wrt the and at where when if is a an
solve derivative differentiate diff derive integrate integral antiderivative int
definite indefinite limit lim approaches sum summation product prod factor
factorize expand simplify roots zeros taylor maclaurin series expansion order
around about partial fractions apart det determinant inverse inv transpose
eigenvalues rank hex hexadecimal bin binary oct octal decimal digits decimals
places prime number factors factorization first second third fourth what
calculate compute evaluate find approx approximate numeric in or all numbers
integers between step ans""".split())
COMMANDS = re.compile(
    r"(solve|derivative|differentiate|diff|derive|d/d|d\^|integra|antiderivative|int\s|"
    r"limit|lim\s|sum\s|summation|product\s|prod\s|factor|expand|simplify|roots|zeros|"
    r"taylor|maclaurin|series|partial|apart|det|inverse|inv\s|transpose|eigenvalues|"
    r"rank\s|hex\s|bin\s|binary\s|oct\s|octal\s|prime|is\s|first|second|third|fourth|"
    r"\d+(st|nd|rd|th)\s|what|calculate|compute|evaluate|find|approx|numeric)", re.I)

_split = split_symbols_custom(lambda s: s not in NS and _token_splittable(s))
T = (convert_xor,) + standard_transformations + (
    _split, implicit_multiplication, implicit_application, function_exponentiation, rationalize)

_ALLOWED = re.compile(r"[0-9A-Za-z_\s+\-*/^%().,=<>!|\[\]]*")
PREF = "xyztuvwabcnmkrs"


class Nope(Exception):
    pass


# ------------------------------------------------- Wolfram Language subset
# `Head[arg, ...]` calls (capitalised head, '[' right after it) are rewritten
# before anything else. A command head that is the WHOLE input is turned into
# the natural-language form (so it gets the nice labels of the NL handlers);
# nested ones become SymPy constructors (Integral(...), Sum(...), ...), which
# `show()` evaluates with doit().
_WL_HEAD = re.compile(r"(?<![\w.])([A-Z][A-Za-z0-9]*)\[")
_WL_RENAME = {"ArcSin": "asin", "ArcCos": "acos", "ArcTan": "atan", "ArcCot": "acot",
              "ArcSinh": "asinh", "ArcCosh": "acosh", "ArcTanh": "atanh", "Log10": "log10",
              "Log2": "log2", "GCD": "gcd", "LCM": "lcm", "Abs": "Abs", "Mod": "Mod",
              "Max": "Max", "Min": "Min", "Sign": "sign", "Surd": "root", "Power": "Pow",
              "PrimePi": "primepi", "EulerPhi": "totient", "Divisors": "divisors",
              "Erf": "erf", "Erfc": "erfc", "Zeta": "zeta", "Gamma": "gamma",
              "Beta": "beta", "ProductLog": "LambertW", "Re": "re", "Im": "im",
              "Conjugate": "conjugate", "Arg": "arg", "Floor": "floor", "Ceiling": "ceiling"}


def _wl_split(s):
    """Split on top-level commas, respecting (), [] and {}."""
    parts, depth, cur = [], 0, ""
    for ch in s:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur.strip())
            cur = ""
        else:
            cur += ch
    parts.append(cur.strip())
    return [p for p in parts if p != ""]


def _wl_list(a):
    """'{x, 0, 1}' -> ['x', '0', '1'];  'x' -> ['x']."""
    a = a.strip()
    if a.startswith("{") and a.endswith("}"):
        return _wl_split(a[1:-1])
    return [a]


def _wl_braces(s):
    return s.replace("{", "[").replace("}", "]")


def _wl_call(head, args, top):
    """Translate one Head[args] (args already translated recursively)."""
    a = args
    h = head
    paren = lambda x: "(%s)" % _wl_braces(x)
    if h in ("Solve", "NSolve", "Reduce", "Roots", "FindRoot"):
        eqs = ", ".join(_wl_list(a[0])) if a else ""
        if len(a) > 1:
            return "solve %s for %s" % (eqs, ", ".join(v for v in _wl_list(a[1]) if re.fullmatch(r"[A-Za-z]\w*", v)))
        return "solve %s" % eqs
    if h in ("Integrate", "NIntegrate") and a:
        spec = _wl_list(a[1]) if len(a) > 1 else []
        if top:
            if len(spec) == 3:
                return "integrate %s wrt %s from %s to %s" % (paren(a[0]), spec[0], paren(spec[1]), paren(spec[2]))
            return "integrate %s" % paren(a[0]) + (" wrt %s" % spec[0] if spec else "")
        if len(spec) == 3:
            return "Integral(%s, (%s, %s, %s))" % (_wl_braces(a[0]), spec[0], spec[1], spec[2])
        return "Integral(%s, %s)" % (_wl_braces(a[0]), spec[0] if spec else "x")
    if h == "D" and a:
        spec = _wl_list(a[1]) if len(a) > 1 else ["x"]
        n = spec[1] if len(spec) > 1 else "1"
        if top:
            return "%s derivative of %s wrt %s" % (n, paren(a[0]), spec[0])
        return "Derivative(%s, (%s, %s))" % (_wl_braces(a[0]), spec[0], n)
    if h in ("Sum", "NSum", "Product", "NProduct") and len(a) > 1:
        spec = _wl_list(a[1])
        kind = "product" if "Product" in h else "sum"
        if len(spec) == 2:
            spec = [spec[0], "1", spec[1]]
        if top:
            return "%s %s from %s = %s to %s" % (kind, paren(a[0]), spec[0], spec[1], spec[2])
        return "%s(%s, (%s, %s, %s))" % ("Product" if kind == "product" else "Sum",
                                         _wl_braces(a[0]), spec[0], spec[1], spec[2])
    if h == "Limit" and len(a) > 1:
        m = re.fullmatch(r"\s*([A-Za-z]\w*)\s*(?:->|→)\s*(.+)", a[1])
        if m:
            if top:
                return "limit of %s as %s -> %s" % (paren(a[0]), m.group(1), m.group(2))
            return "Limit(%s, %s, %s)" % (_wl_braces(a[0]), m.group(1), m.group(2))
    if h == "Series" and len(a) > 1 and top:
        spec = _wl_list(a[1])
        if len(spec) == 3:
            return "series %s around %s = %s order %s" % (paren(a[0]), spec[0], spec[1], spec[2])
    if top and len(a) == 1:
        simple = {"Factor": "factor", "Expand": "expand", "Simplify": "simplify",
                  "FullSimplify": "simplify", "Apart": "partial fractions", "Det": "det",
                  "Inverse": "inverse", "Transpose": "transpose", "Eigenvalues": "eigenvalues",
                  "MatrixRank": "rank", "FactorInteger": "prime factorization of",
                  "N": "numeric"}
        if h in simple:
            return "%s %s" % (simple[h], _wl_braces(a[0]))
        if h == "PrimeQ":
            return "is %s prime" % a[0]
    if h == "N" and len(a) == 2 and top:
        return "%s to %s digits" % (paren(a[0]), a[1])
    if h == "BaseForm" and len(a) == 2 and top:
        return "%s in %s" % (a[0], {"16": "hex", "2": "binary", "8": "octal"}.get(a[1].strip(), "decimal"))
    if h == "PrimeQ" and len(a) == 1:
        return "isprime(%s)" % a[0]
    # plain functions: Sin[x] -> sin(x), FactorInteger nested -> factorint(...)
    name = _WL_RENAME.get(h) or {"FactorInteger": "factorint", "Factor": "factor", "Expand": "expand",
                                 "Simplify": "simplify", "N": "N"}.get(h) or h.lower()
    return "%s(%s)" % (name, ", ".join(_wl_braces(x) for x in a))


def wolfram(s):
    """Rewrite Wolfram Language calls; returns s unchanged when there are none."""
    if not _WL_HEAD.search(s):
        return s
    whole = s.strip()

    def rewrite(text, top_level_ok):
        out, i = "", 0
        while True:
            m = _WL_HEAD.search(text, i)
            if not m:
                return out + text[i:]
            start, depth, j = m.end(), 1, m.end()
            while j < len(text) and depth:
                if text[j] == "[":
                    depth += 1
                elif text[j] == "]":
                    depth -= 1
                j += 1
            if depth:
                raise Nope("unbalanced [ ]")
            inner = text[start:j - 1]
            args = [rewrite(x, False) for x in _wl_split(inner)]
            is_top = top_level_ok and m.start() == 0 and j == len(text)
            out += text[i:m.start()] + _wl_call(m.group(1), args, is_top)
            i = j

    s = rewrite(whole, True)
    # WL constants and operators
    s = re.sub(r"\bInfinity\b", "oo", s)
    s = re.sub(r"\bDegree\b", "deg", s)
    s = re.sub(r"\bGoldenRatio\b", "phi", s)
    return s.replace("==", "=")


# ------------------------------------------------------------------ parsing
def clean(q):
    s = q.strip().rstrip("?").strip()
    s = re.sub(r"√\s*(\d+(?:\.\d+)?|[A-Za-z]\w*)", r"sqrt(\1)", s)
    for a, b in (("×", "*"), ("·", "*"), ("÷", "/"), ("−", "-"), ("–", "-"), ("π", "pi"),
                 ("√", "sqrt"), ("∞", "oo"), ("²", "^2"), ("³", "^3"), ("≤", "<="),
                 ("≥", ">="), ("≠", "!="), ("°", " deg"), ("→", "->"), ("**", "^"), ("==", "="),
                 ("∫", "int "), ("∑", "sum "), ("∏", "prod "), ("{", "["), ("}", "]"),
                 (" ", " "), ("’", "'"), ("“", '"'), ("”", '"')):
        s = s.replace(a, b)
    s = re.sub(r"^(what\s+is|what's|whats|calculate|compute|evaluate|find)\s+(the\s+)?", "", s, flags=re.I)
    for pat, rep in ((r"\bdivided\s+by\b", "/"), (r"\b(times|multiplied\s+by)\b", "*"),
                     (r"\bplus\b", "+"), (r"\bminus\b", "-"), (r"\bsquared\b", "^2"),
                     (r"\bcubed\b", "^3"), (r"\bto\s+the\s+power\s+of\b", "^"),
                     (r"\bsquare\s+root\s+of\b", "sqrt"), (r"\bcube\s+root\s+of\b", "cbrt"),
                     (r"\binfinity\b", "oo"), (r"\bmod\b", "%"),
                     (r"(\d+(?:\.\d+)?)\s*%\s*of\b", r"(\1/100)*"),
                     (r"(\d+(?:\.\d+)?)\s*%(?!\s*[\w(])", r"(\1/100)")):
        s = re.sub(pat, rep, s, flags=re.I)
    s = re.sub(r"\b0x[0-9a-f]+\b|\b0b[01]+\b|\b0o[0-7]+\b", lambda m: str(int(m.group(), 0)), s, flags=re.I)
    s = re.sub(r"\|([^|]+)\|", r"Abs(\1)", s)

    def fix_case(m):
        w = m.group()
        return w.lower() if len(w) > 1 and w not in NS and w.lower() in (MATH_WORDS | NL_WORDS) else w
    s = re.sub(r"[A-Za-z_]+", fix_case, s).strip()
    # Phone-friendly function application (as Wolfram Alpha does):
    #   sinx -> sin(x),  lnx -> ln(x),  cos2x -> cos(2x),  sinhx -> sinh(x);  sinh / sin(x) untouched
    s = re.sub(r"\b([a-z]+)(\d*(?:\.\d+)?)([a-z])?\b", _fn_apply, s)
    return s


_FN1 = sorted("""sin cos tan cot sec csc asin acos atan acot asec acsc sinh cosh tanh coth sech
csch asinh acosh atanh arcsin arccos arctan ln log exp sqrt cbrt abs""".split(), key=len, reverse=True)


def _fn_apply(m):
    w = m.group(0)
    if w in NS or w in NL_WORDS:
        return w
    letters, digits, var = m.group(1), m.group(2), m.group(3)
    if digits and var:                       # cos2x
        return "%s(%s%s)" % (letters, digits, var) if letters in _FN1 else w
    if digits or var:
        return w
    for fn in _FN1:                          # sinx, lnx, sinhx
        if letters.startswith(fn) and len(letters) == len(fn) + 1:
            return "%s(%s)" % (fn, letters[-1])
    return w


def P(s, loc=None):
    s = s.strip().strip(",;")
    if not s:
        raise Nope("empty")
    if not _ALLOWED.fullmatch(s) or "__" in s or re.search(r"\.\s*[A-Za-z_]|[A-Za-z_)\]]\s*\.", s):
        raise Nope("unsupported characters")
    # sympy's implicit application chokes on "ln e^3" / "sin pi^2" (constant ^ power)
    s = re.sub(r"(?<=[A-Za-z] )(pi|e|i|E|I|oo|tau|phi)\^(-?[\w.]+|\([^()]*\))(?!\s*\^)", r"(\1^\2)", s)
    r = parse_expr(s, local_dict=dict(loc or {}), global_dict=dict(NS), transformations=T)
    if isinstance(r, (list, tuple)):
        r = sp.Matrix(r)
    if isinstance(r, (bool, int, float)):
        r = sp.sympify(r)
    if not isinstance(r, (sp.Basic, sp.MatrixBase)) or isinstance(r, sp.FunctionClass):
        raise Nope("not an expression")
    return r


def split_top(s):
    parts, depth, cur = [], 0, ""
    for ch in s:
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth -= 1
        if ch in ",;" and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    parts.append(cur)
    return [p.strip() for p in parts if p.strip()]


def symkey(sym):
    n = sym.name
    return (PREF.index(n) if n in PREF else len(PREF), n)


def pick(expr, hint=None, loc=None):
    if hint:
        return sp.Symbol(hint)
    syms = sorted(expr.free_symbols, key=symkey)
    return syms[0] if syms else sp.Symbol("x")


# --------------------------------------------------------------- formatting
def pretty(s):
    s = s.replace("**", "^")
    s = re.sub(r"\boo\b", "∞", s)
    s = re.sub(r"\bzoo\b", "complex ∞", s)
    s = re.sub(r"\bpi\b", "π", s)
    s = re.sub(r"\bI\b", "i", s)
    s = re.sub(r"\bE\b", "e", s)
    s = re.sub(r"(?<![\^\w.])(\d+)\*(?=[A-Za-zπ(])", r"\1", s)
    return s


def fmt(e):
    if isinstance(e, sp.MatrixBase):
        return "[" + ", ".join("[" + ", ".join(fmt(c) for c in row) + "]" for row in e.tolist()) + "]"
    if isinstance(e, (list, tuple)):
        return ", ".join(fmt(x) for x in e)
    if isinstance(e, dict):
        return ", ".join("%s: %s" % (fmt(k), fmt(v)) for k, v in e.items())
    if isinstance(e, sp.Float) or (isinstance(e, sp.Basic) and e.is_number and e.has(sp.Float)):
        return dec(e, 15)
    return pretty(sp.sstr(e))


def dec(e, digits=10):
    s = sp.sstr(sp.N(e, digits))
    s = re.sub(r"(\.\d*?[1-9])0+(?!\d)", r"\1", s)
    s = re.sub(r"\.0+(?!\d)", "", s)
    return pretty(s)


def is_exact_simple(e):
    return isinstance(e, sp.Basic) and (e.is_Integer or e.is_Float)


def row(title, sub, copy=None):
    return {"t": title, "s": sub, "c": copy if copy is not None else title}


def fmt_set(S, v):
    if isinstance(S, sp.FiniteSet):
        return ",  ".join("%s = %s" % (v, fmt(x)) for x in S)
    if isinstance(S, sp.ImageSet):
        lam = S.lamda
        return "%s = %s" % (v, fmt(lam.expr.subs(lam.variables[0], sp.Symbol("n"))))
    if isinstance(S, sp.Union):
        return ",  ".join(fmt_set(a, v) for a in S.args)
    if S is sp.S.Reals:
        return "all real %s" % v
    if S is sp.S.Complexes:
        return "all %s" % v
    return "%s ∈ %s" % (v, pretty(sp.sstr(S)))


def interval_text(S):
    if isinstance(S, sp.Interval):
        return "%s%s, %s%s" % ("(" if S.left_open else "[", fmt(S.start), fmt(S.end), ")" if S.right_open else "]")
    if isinstance(S, sp.Union):
        return " ∪ ".join(interval_text(a) for a in S.args)
    if isinstance(S, sp.FiniteSet):
        return "{%s}" % ", ".join(fmt(x) for x in S)
    if S is sp.S.EmptySet:
        return "∅"
    return pretty(sp.sstr(S))


def ineq_text(S, v):
    if S is sp.S.EmptySet:
        return "No solution"
    if S == sp.S.Reals:
        return "True for every real %s" % v
    if isinstance(S, sp.Interval):
        lo = "<" if S.left_open else "≤"
        hi = "<" if S.right_open else "≤"
        if S.start == -sp.oo:
            return "%s %s %s" % (v, hi, fmt(S.end))
        if S.end == sp.oo:
            return "%s %s %s" % (v, ">" if S.left_open else "≥", fmt(S.start))
        return "%s %s %s %s %s" % (fmt(S.start), lo, v, hi, fmt(S.end))
    if isinstance(S, sp.Union):
        return "  or  ".join(ineq_text(a, v) for a in S.args)
    if isinstance(S, sp.FiniteSet):
        return ",  ".join("%s = %s" % (v, fmt(x)) for x in S)
    raise ValueError("unsupported set")


def fmt_bool(b):
    s = pretty(sp.sstr(b))
    s = re.sub(r"\(-∞ < \w+\) & |\s*& \(\w+ < ∞\)", "", s)
    s = s.replace("&", "and").replace("|", "or")
    if " and " not in s and " or " not in s:
        s = s.strip("()")
    return s


# ------------------------------------------------------------------ solving
REL = re.compile(r"(<=|>=|!=|=|<|>)")
RELF = {"=": sp.Eq, "<": sp.Lt, ">": sp.Gt, "<=": sp.Le, ">=": sp.Ge, "!=": sp.Ne}


def parse_rels(part, loc):
    toks = REL.split(part)
    if len(toks) == 1:
        return None
    exprs = [P(t, loc) for t in toks[0::2]]
    return [(exprs[k], op, exprs[k + 1]) for k, op in enumerate(toks[1::2])]


def do_solve(text, vars_text=None):
    loc, want = {}, None
    if vars_text:
        names = [n for n in re.findall(r"[A-Za-z]\w*", vars_text) if n != "and"]
        loc = {n: sp.Symbol(n) for n in names}
        want = [loc[n] for n in names]
    rels = []
    for part in split_top(re.sub(r"\s+and\s+", ",", text)):
        r = parse_rels(part, loc)
        rels += r if r else [(P(part, loc), "=", sp.Integer(0))]
    syms = sorted(set().union(*[(a - b).free_symbols for a, _, b in rels]), key=symkey)
    shown = ",  ".join("%s %s %s" % (fmt(a), o, fmt(b)) for a, o, b in rels)
    if not syms:
        ok = all(bool(sp.simplify(RELF[o](a, b))) for a, o, b in rels)
        return [row("True" if ok else "False", "Is %s ?" % shown)]
    if want is None:
        want = syms[:1] if len(rels) == 1 else syms[:min(len(rels), len(syms))]
    interp = "Solve  %s  for %s" % (shown, ", ".join(map(str, want)))

    if any(o != "=" for _, o, _ in rels):
        v = want[0]
        res = sp.reduce_inequalities([RELF[o](a, b) for a, o, b in rels], v)
        try:
            S = res.as_set()
            return [row(ineq_text(S, v), interp), row("%s ∈ %s" % (v, interval_text(S)), "Interval notation")]
        except Exception:
            return [row(fmt_bool(res), interp)]

    eqs = [a - b for a, _, b in rels]
    if len(eqs) == 1 and len(want) == 1:
        return solve_one(eqs[0], want[0], interp)
    sols = sp.solve(eqs, want, dict=True)
    if not sols:
        return [row("No solution found", interp)]
    rows = []
    for k, d in enumerate(sols[:6]):
        title = ",  ".join("%s = %s" % (v, fmt(sp.simplify(d[v]))) for v in want if v in d)
        rows.append(row(title, interp if k == 0 else "Solution %d of %d" % (k + 1, len(sols))))
        if any(d[v].is_number and not d[v].is_Rational for v in d):
            rows.append(row(",  ".join("%s ≈ %s" % (v, dec(d[v])) for v in want if v in d), "Decimal approximation"))
    return rows


def solve_one(eq, v, interp):
    rows = []
    try:
        sols = sp.solve(eq, v)
    except NotImplementedError:
        sols = []
    if isinstance(sols, dict):
        sols = list(sols.values())
    sols = list(dict.fromkeys(sols))
    periodic = eq.has(sp.sin, sp.cos, sp.tan, sp.cot, sp.sec, sp.csc)
    if not sols:
        if sp.simplify(eq) == 0:
            return [row("True for every %s" % v, interp)]
        try:
            ss = sp.solveset(eq, v, sp.S.Reals if periodic else sp.S.Complexes)
        except Exception:
            ss = None
        if ss is sp.S.EmptySet and not periodic:
            return [row("No solution", interp)]
        if ss is not None and not isinstance(ss, sp.ConditionSet) and ss is not sp.S.EmptySet:
            return [row(fmt_set(ss, v), interp)]
        found = []
        for guess in (0, 1, -1, 3, -3, 10, -10, 0.5):
            try:
                val = sp.nsolve(eq, v, guess)
                if not any(abs(val - f) < 1e-9 for f in found):
                    found.append(val)
            except Exception:
                pass
        if found:
            return [row(",  ".join("%s ≈ %s" % (v, dec(f)) for f in sorted(found, key=lambda z: sp.re(z))),
                        interp + "  (numeric)")]
        return [row("No solution found" if not periodic else "No real solution", interp)]

    ugly = any(s.has(sp.CRootOf) for s in sols)
    exact = ["%s = %s" % (v, fmt(s)) for s in sols]
    numeric = any(s.is_number and not all(p.is_Rational for p in s.as_real_imag()) for s in sols)
    approx = ",  ".join("%s ≈ %s" % (v, dec(s)) for s in sols) if numeric else None
    joined = ",  ".join(exact)
    if ugly:
        for k, s in enumerate(sols[:8]):
            rows.append(row("%s ≈ %s" % (v, dec(s)),
                            interp if k == 0 else "Root %d of %d  (numeric)" % (k + 1, len(sols)), dec(s)))
    elif len(joined) <= 64 or len(sols) == 1:
        rows.append(row(joined, interp, joined if len(sols) > 1 else fmt(sols[0])))
        if approx:
            rows.append(row(approx, "Decimal approximation"))
        if approx and len(joined) > 80:
            rows.reverse()
    else:
        if approx:
            rows.append(row(approx, interp + "  (decimal)"))
        for k, s in enumerate(sols[:8]):
            rows.append(row(exact[k], "Exact solution %d of %d" % (k + 1, len(sols)), fmt(s)))
    if periodic:
        try:
            ss = sp.solveset(eq, v, sp.S.Reals)
            if isinstance(ss, (sp.ImageSet, sp.Union)):
                rows.insert(1, row(fmt_set(ss, v), "General real solution  (n ∈ ℤ)"))
        except Exception:
            pass
    return rows


# -------------------------------------------------------------- expressions
def number_rows(val, label, had_decimal=False):
    val = sp.simplify(val) if not is_exact_simple(val) else val
    if val.is_Integer or val.is_Float or not val.is_number or val in (sp.oo, -sp.oo, sp.zoo, sp.nan):
        return [row(fmt(val), label)]
    rows = [row(fmt(val), label), row(dec(val), "Decimal approximation")]
    if rows[0]["t"] == rows[1]["t"]:
        return rows[:1]
    if had_decimal or len(rows[0]["t"]) > 40:
        rows[0]["s"], rows[1]["s"] = "Exact form", label
        rows.reverse()
    return rows


def show(expr, label="Result", had_decimal=False, extras=True):
    if isinstance(expr, sp.MatrixBase):
        rows = [row(fmt(expr), label)]
        if extras and expr.is_square and expr.shape[0] <= 4:
            d = expr.det()
            rows.append(row(fmt(d), "Determinant"))
            if d != 0:
                rows.append(row(fmt(expr.inv()), "Inverse"))
        return rows
    if isinstance(expr, (sp.Sum, sp.Product, sp.Integral, sp.Derivative, sp.Limit)) or expr.has(sp.Sum, sp.Product, sp.Integral, sp.Derivative):
        expr = expr.doit()
    if isinstance(expr, (sp.logic.boolalg.Boolean, sp.Rel)):
        return [row(fmt_bool(expr), label)]
    if expr.is_number or not expr.free_symbols:
        return number_rows(expr, label, had_decimal)

    simp = sp.simplify(expr)
    rows = [row(fmt(simp), label)]
    if not extras:
        return rows
    seen = {rows[0]["t"]}

    def add(e, sub, title=None):
        t = title or fmt(e)
        if t not in seen and len(t) < 200:
            seen.add(t)
            rows.append(row(t, sub))

    syms = sorted(expr.free_symbols, key=symkey)
    for fn, sub in ((sp.expand, "Expanded form"), (sp.factor, "Factored form")):
        try:
            add(fn(expr), sub)
        except Exception:
            pass
    if len(syms) == 1 and not QUICK:
        v = syms[0]
        try:
            r = sp.solve(expr, v)
            if r and not any(s.has(sp.CRootOf) for s in r):
                add(None, "Roots  (expression = 0)", ",  ".join("%s = %s" % (v, fmt(s)) for s in r))
        except Exception:
            pass
        try:
            add(sp.simplify(sp.diff(expr, v)), "Derivative  d/d%s" % v)
        except Exception:
            pass
        try:
            integ = sp.integrate(expr, v)
            if not integ.has(sp.Integral):
                add(None, "Indefinite integral  ∫ d%s" % v, fmt(integ) + " + C")
        except Exception:
            pass
    return rows


ORD = {"first": 1, "second": 2, "third": 3, "fourth": 4, None: 1, "": 1}


def h_deriv(body, var=None, n=1, at=None):
    loc = {var: sp.Symbol(var)} if var else {}
    e = P(body, loc)
    v = pick(e, var)
    res = sp.simplify(sp.diff(e, v, n))
    label = "d%s/d%s%s  of  %s" % ("^%d" % n if n > 1 else "", v, "^%d" % n if n > 1 else "", fmt(e))
    if at:
        subs = parse_assign(at, v, loc)
        return show(res.subs(subs), label + "  at  " + ", ".join("%s = %s" % (k, fmt(x)) for k, x in subs.items()), extras=False)
    return show(res, label, extras=False)


def parse_assign(text, default_var, loc=None):
    out = {}
    for part in split_top(re.sub(r"\s+and\s+", ",", text)):
        if "=" in part:
            k, val = part.split("=", 1)
            out[sp.Symbol(k.strip())] = P(val, loc)
        else:
            out[default_var] = P(part, loc)
    return out


def h_integral(body, var=None, a=None, b=None):
    """Indefinite:  ∫ f dv  -> F + C   (SymPy: heuristics, Risch, Meijer-G, manual rules).
    Definite:    ∫_a^b f dv -> exact value + decimal; numeric quadrature (mpmath,
    tanh-sinh) when SymPy finds no closed form; improper bounds (±∞) allowed."""
    loc = {var: sp.Symbol(var)} if var else {}
    e = P(body, loc)
    v = pick(e, var)
    if a is not None:
        lo, hi = P(a, loc), P(b, loc)
        label = "∫ %s d%s  from %s to %s" % (fmt(e), v, fmt(lo), fmt(hi))
        res = sp.integrate(e, (v, lo, hi))
        if res.has(sp.Integral):
            try:
                num = sp.Integral(e, (v, lo, hi)).evalf()
            except Exception:
                num = None
            if num is None or num.has(sp.Integral) or not num.is_number:
                return [row("No closed form or numeric value found", label)]
            return [row(dec(num), label + "  (numeric)")]
        if res in (sp.oo, -sp.oo, sp.zoo):
            return [row(fmt(res), label), row("The integral diverges", "Improper integral", fmt(res))]
        if isinstance(res, sp.Piecewise):
            return [row(fmt(res), label + "  (depends on parameters)")]
        return show(res, label, extras=False)
    res = sp.integrate(e, v)
    if res.has(sp.Integral):
        return [row("No closed form found", "∫ %s d%s  (not elementary / unknown to SymPy)" % (fmt(e), v), "")]
    rows = [row(fmt(res) + " + C", "∫ %s d%s" % (fmt(e), v), fmt(res))]
    if not QUICK:
        try:
            alt = sp.simplify(res)
            if fmt(alt) != fmt(res):
                rows.append(row(fmt(alt) + " + C", "Simplified antiderivative", fmt(alt)))
        except Exception:
            pass
    return rows


def h_limit(body, var, target):
    target = target.strip()
    direction = "+-"
    m = re.fullmatch(r"(.+?)\s*(?:\^?\s*([+-])|from\s+(?:the\s+)?(left|right|above|below))", target)
    if m and m.group(1).strip() not in ("", "+", "-"):
        target = m.group(1)
        direction = m.group(2) or ("-" if m.group(3) in ("left", "below") else "+")
    loc = {var: sp.Symbol(var)}
    e, t = P(body, loc), P(target, loc)
    if t in (sp.oo, -sp.oo):
        direction = "-" if t == sp.oo else "+"
    res = sp.limit(e, loc[var], t, direction)
    side = {"+-": "", "+": "⁺", "-": "⁻"}[direction] if t not in (sp.oo, -sp.oo) else ""
    return show(res, "lim %s→%s%s  of  %s" % (var, fmt(t), side, fmt(e)), extras=False)


def h_sum(kind, body, var, a, b):
    loc = {var: sp.Symbol(var)}
    e, lo, hi = P(body, loc), P(a, loc), P(b, loc)
    prod = kind.startswith("prod")
    res = (sp.product if prod else sp.summation)(e, (loc[var], lo, hi))
    label = "%s of %s  for %s = %s … %s" % ("Product" if prod else "Sum", fmt(e), var, fmt(lo), fmt(hi))
    if res.has(sp.Sum, sp.Product):
        try:
            return [row(dec(res.evalf()), label + "  (numeric)")]
        except Exception:
            return [row("Does not converge / no closed form", label)]
    return show(res, label, extras=False)


def h_range(kind, a, b, step=None):
    """`sum 1 to 100` -> every integer from a to b (inclusive), optionally with a step.
    Uses the closed form of the arithmetic series, so `sum 1 to 10^12` is instant."""
    lo, hi = P(a), P(b)
    st = P(step) if step else sp.Integer(1)
    if lo.free_symbols or hi.free_symbols or st.free_symbols:
        raise Nope("symbolic range")
    if not st.is_number or st == 0:
        raise Nope("bad step")
    lo_i, hi_i = sp.ceiling(lo) if st > 0 else sp.floor(lo), hi
    j = sp.Symbol("j", integer=True, nonnegative=True)
    count = sp.floor((hi_i - lo_i) / st) + 1
    prod = kind.startswith("prod")
    rng = "%s … %s" % (fmt(lo), fmt(hi)) + ("  step %s" % fmt(st) if step else "")
    if count <= 0:
        return [row("1" if prod else "0", "Empty range  %s" % rng)]
    term = lo_i + st * j
    if prod:
        if count > 5000:
            raise Nope("product range too large")
        res = sp.Mul(*[lo_i + st * k for k in range(int(count))])
        return show(res, "Product of every number in %s  (%s terms)" % (rng, fmt(count)), extras=False)
    res = sp.simplify(sp.summation(term, (j, 0, count - 1)))
    return show(res, "Sum of every number in %s  (%s terms)" % (rng, fmt(count)), extras=False)


def h_list(kind, items):
    vals = [P(x) for x in split_top(items)]
    res = sp.Mul(*vals) if kind.startswith("prod") else sp.Add(*vals)
    return show(res, "%s of %d values" % ("Product" if kind.startswith("prod") else "Sum", len(vals)), extras=False)


def h_series(body, var=None, point=None, order=None):
    loc = {var: sp.Symbol(var)} if var else {}
    e = P(body, loc)
    v = pick(e, var)
    x0 = P(point, loc) if point else sp.Integer(0)
    n = int(order) + 1 if order else 6
    res = sp.series(e, v, x0, n)
    return [row(fmt(res).replace("O(", "O("), "Series of %s around %s = %s" % (fmt(e), v, fmt(x0)), fmt(res.removeO()))]


def h_unary(op, body):
    op = op.lower().strip()
    if re.match(r"prime fact|factor", op):
        e = P(body)
        if e.is_Integer:
            f = sp.factorint(e)
            title = " × ".join("%s^%s" % (p, k) if k > 1 else str(p) for p, k in sorted(f.items()))
            return [row(title, "Prime factorization of %s" % fmt(e))]
        return [row(fmt(sp.factor(e)), "Factor  %s" % fmt(e))]
    if op == "expand":
        e = P(body)
        return [row(fmt(sp.expand(e)), "Expand  %s" % fmt(e))]
    if op == "simplify":
        e = P(body)
        return show(sp.simplify(e), "Simplify  %s" % fmt(e), extras=False)
    if op.startswith("partial") or op == "apart":
        e = P(body)
        return [row(fmt(sp.apart(e)), "Partial fractions of %s" % fmt(e))]
    if op.startswith(("roots", "zeros")):
        return do_solve(body)
    e = P(body)
    if not isinstance(e, sp.MatrixBase):
        raise Nope("matrix expected")
    if op.startswith("det"):
        return show(e.det(), "Determinant of %s" % fmt(e), extras=False)
    if op.startswith("inv"):
        return [row(fmt(e.inv()), "Inverse of %s" % fmt(e))]
    if op.startswith("transpose"):
        return [row(fmt(e.T), "Transpose of %s" % fmt(e))]
    if op.startswith("eigen"):
        vals = e.eigenvals()
        return [row(",  ".join("%s%s" % (fmt(k), " (×%d)" % m if m > 1 else "") for k, m in vals.items()),
                    "Eigenvalues of %s" % fmt(e))]
    if op.startswith("rank"):
        return [row(str(e.rank()), "Rank of %s" % fmt(e))]
    raise Nope("unknown")


def h_base(body, base):
    e = P(body)
    if not e.is_Integer:
        raise Nope("integer expected")
    n = int(e)
    b = base.lower()[:3]
    out = {"hex": hex, "bin": bin, "oct": oct}.get(b, str)(n)
    return [row(out, "%s in %s" % (n, {"hex": "hexadecimal", "bin": "binary", "oct": "octal"}.get(b, "decimal")))]


I = re.I

def interpret(s, raw):
    had_decimal = bool(re.search(r"\d\.\d", s))
    fm = lambda pat: re.fullmatch(pat, s, I)

    SUMK = r"(sum|summation|product|prod)"
    TO = r"\s*(?:\.\.|\s+to\s+)\s*"
    # sum f from n=a to b   |  sum f for n = a..b
    m = fm(SUMK + r"\s+(?:of\s+)?(.+?),?\s+(?:from|for)\s+([a-z])\s*=\s*(.+?)" + TO + r"(.+)")
    if m:
        return h_sum(m.group(1).lower(), m.group(2), m.group(3), m.group(4), m.group(5))
    # sum f, n, a, b   (Maxima / Wolfram-like argument order)
    m = fm(SUMK + r"\s+(?:of\s+)?(.+?),\s*([a-z])\s*,\s*(.+?)\s*,\s*(.+)")
    if m:
        return h_sum(m.group(1).lower(), m.group(2), m.group(3), m.group(4), m.group(5))
    # sum 1 to 100  |  sum of all numbers between 1 and 100  |  sum 1..99 step 2
    m = fm(SUMK + r"\s+(?:of\s+)?(?:all\s+)?(?:the\s+)?(?:(?:whole\s+|natural\s+)?(?:numbers|integers)\s+)?"
                  r"(?:from\s+|between\s+)?([^a-z,]+?)\s*(?:\.\.|\s(?:to|and)\s)\s*([^a-z,]+?)(?:\s+step\s+([^a-z]+))?")
    if m:
        return h_range(m.group(1).lower(), m.group(2), m.group(3), m.group(4))
    # sum k^2 from 1 to 10   (variable inferred from the expression)
    m = fm(SUMK + r"\s+(?:of\s+)?(.+?),?\s+(?:from|for|over)\s+(.+?)" + TO + r"(.+)")
    if m:
        e = P(m.group(2))
        return h_sum(m.group(1).lower(), m.group(2), str(pick(e)), m.group(3), m.group(4))
    # sum 1, 2, 3, 4   |  sum [1, 2, 3]
    m = fm(SUMK + r"\s+(?:of\s+)?\[?\s*([^\[\]]+?,[^\[\]]+?)\s*\]?")
    if m:
        return h_list(m.group(1).lower(), m.group(2))
    m = fm(r"(?:solve\s+)?(.+?)[,;]?\s+(?:solve\s+)?for\s+([a-z]\w*(?:\s*(?:,|and)\s*[a-z]\w*)*)")
    if m:
        return do_solve(m.group(1), m.group(2))
    m = fm(r"solve\s+(.+)")
    if m:
        return do_solve(m.group(1))
    m = fm(r"d(?:\^(\d+))?/d([a-z])(?:\^\d+)?\s*(?:of\s+)?(.+)")
    if m:
        return h_deriv(m.group(3), m.group(2), int(m.group(1) or 1))
    m = fm(r"(?:(first|second|third|fourth|\d+)(?:st|nd|rd|th)?\s+)?(?:partial\s+)?(?:derivative|differentiate|diff|derive)\s+(?:of\s+)?(.+?)"
           r"(?:\s+(?:with\s+respect\s+to|wrt)\s+([a-z]))?(?:\s+(?:at|when|where)\s+(.+))?")
    if m:
        n = m.group(1)
        n = int(n) if n and n.isdigit() else ORD.get(n and n.lower(), 1)
        return h_deriv(m.group(2), m.group(3), n, m.group(4))
    INT = r"(?:definite\s+|indefinite\s+)?(?:integrate|integral|antiderivative|int)"
    DV = r"(?:(?:\s+|(?<=[\d)]))d([a-z]))?"
    # int_0^1 x^2 dx   |  int_(0)^(pi) sin x
    m = fm(INT + r"_\s*(\([^()]*\)|[^\s^]+)\s*\^\s*(\([^()]*\)|[^\s(]+)\s+(?:of\s+)?(.+?)" + DV)
    if m:
        return h_integral(m.group(3), m.group(4), m.group(1), m.group(2))
    # int f, x   |  int f, x, a, b
    m = fm(INT + r"\s+(?:of\s+)?(.+?),\s*([a-z])(?:\s*,\s*(.+?)\s*,\s*(.+))?")
    if m:
        return h_integral(m.group(1), m.group(2), m.group(3), m.group(4))
    # int f [dx] [wrt x] [from [x=]a to b | over a..b | x=a..b]
    m = fm(INT + r"\s+(?:of\s+)?(.+?)" + DV + r"(?:\s+(?:with\s+respect\s+to|wrt)\s+([a-z]))?"
           r"(?:,?\s+(?:from|for|over)\s+(?:([a-z])\s*=\s*)?(.+?)" + TO + r"(.+)"
           r"|,?\s+([a-z])\s*=\s*(.+?)\s*\.\.\s*(.+))?")
    if m:
        var = m.group(2) or m.group(3) or m.group(4) or m.group(7)
        a, b = (m.group(5), m.group(6)) if m.group(5) is not None else (m.group(8), m.group(9))
        return h_integral(m.group(1), var, a, b)
    m = fm(r"lim(?:it)?\s+(?:of\s+)?(.+?),?\s+as\s+([a-z])\s*(?:->|to|approaches|goes\s+to|tends\s+to)\s*(.+)")
    if m:
        return h_limit(m.group(1), m.group(2), m.group(3))
    m = fm(r"lim(?:it)?\s+(?:as\s+)?([a-z])\s*(?:->|to|approaches)\s*(\S+?)\s*(?:,|:|\s)\s*(?:of\s+)?(.+)")
    if m:
        return h_limit(m.group(3), m.group(1), m.group(2))
    m = fm(r"(?:taylor|maclaurin|power\s+series|series)(?:\s+series)?(?:\s+expansion)?\s+(?:of\s+)?(.+?)"
           r"(?:\s+(?:at|around|about)\s+(?:([a-z])\s*=\s*)?(.+?))?(?:,?\s+(?:to\s+)?order\s+(\d+))?")
    if m:
        return h_series(m.group(1), m.group(2), m.group(3), m.group(4))
    m = fm(r"is\s+(.+?)\s+(?:a\s+)?prime(?:\s+number)?")
    if m:
        e = P(m.group(1))
        return [row("Yes" if sp.isprime(e) else "No", "Is %s a prime number?" % fmt(e))]
    m = fm(r"(prime\s+factor(?:s|ization)?|factori[sz]e|factor|expand|simplify|partial\s+fractions?|apart|roots|zeros|"
           r"det|determinant|inverse|inv|transpose|eigenvalues|rank)\s+(?:of\s+)?(.+)")
    if m:
        return h_unary(m.group(1), m.group(2))
    m = fm(r"(hex|bin|binary|oct|octal)\s+(.+)")
    if m:
        return h_base(m.group(2), m.group(1))
    m = fm(r"(.+?)\s+(?:in|to|as)\s+(hex|hexadecimal|binary|bin|octal|oct|decimal)")
    if m:
        return h_base(m.group(1), m.group(2))
    m = fm(r"(.+?)\s+(?:to|with)\s+(\d+)\s+(?:digits|decimals|decimal\s+places|places)") or None
    m2 = fm(r"(\d+)\s+digits\s+of\s+(.+)")
    if m or m2:
        body, n = (m.group(1), m.group(2)) if m else (m2.group(2), m2.group(1))
        e = P(body)
        return [row(sp.sstr(sp.N(e, min(int(n), 2000))), "%s to %s digits" % (fmt(e), n))]
    m = fm(r"(?:n|numeric|approx|approximate)\s+(.+)")
    if m:
        e = P(m.group(1))
        return [row(dec(e, 15), "Numeric value of %s" % fmt(e))]
    m = fm(r"(.+?),?\s+(?:where|when|at|with|if|for)\s+([a-z]\w*\s*=.+)")
    if m:
        subs = parse_assign(m.group(2), None)
        e = P(m.group(1), {k.name: k for k in subs})
        return show(e.subs(subs), "%s  where  %s" % (fmt(e), ", ".join("%s = %s" % (k, fmt(x)) for k, x in subs.items())),
                    had_decimal, extras=False)

    # a command word that no handler accepted means the input is incomplete
    m = re.match(r"(solve|int|integrate|integral|antiderivative|sum|summation|prod|product|lim|limit|"
                 r"derivative|differentiate|diff|factor|expand|simplify|taylor|series|det|inverse)\b(?!\s*\()", s, I)
    if m:
        raise Nope("incomplete '%s' command, see Syntax help" % m.group(1).lower())

    # default: equation(s)/inequalities -> solve, otherwise evaluate
    parts = split_top(s)
    if any(REL.search(p) for p in parts):
        return do_solve(s)
    return show(P(s), "Result", had_decimal)


def plausible(raw, s):
    """Global (no keyword) mode: only answer things that really look like math."""
    if len(s.replace(" ", "")) < 2 or not _ALLOWED.fullmatch(s.replace("->", " ")):
        return False
    words = re.findall(r"[A-Za-z_]+", s)
    is_cmd = bool(COMMANDS.match(raw.strip()))
    unknown = [w for w in words if len(w) > 1 and w.lower() not in MATH_WORDS and w.lower() not in NL_WORDS]
    has_op = bool(re.search(r"[=+\-*/^()!%<>|]", s))
    if any(len(w) > 3 for w in unknown):
        return False
    if unknown and not re.search(r"[=+*/^(]", s):
        return False
    if is_cmd:
        return len(words) > 1 or bool(re.search(r"\d", s))
    if not has_op:
        # bare words/numbers: only pure constants & functions like "2 pi", "sqrt 2"
        return bool(words) and all(w.lower() in MATH_WORDS and len(w) > 1 for w in words)
    return True


def squash(t):
    return re.sub(r"[\s*]", "", t).lower()


def evaluate_full(text, strict=False, ans=""):
    """-> (ok, rows). `ans` is the copy value of the previous result."""
    raw = text.strip()
    if not raw:
        return True, []
    try:
        src = raw
        if re.search(r"\bans\b", src):
            if not ans:
                return False, [row("No previous answer", "`ans` refers to the last result in your history", raw)]
            src = re.sub(r"\bans\b", "(%s)" % ans, src)
        s = clean(wolfram(src))
    except Exception as exc:
        return False, [row("Couldn't interpret that", "%s: %s" % (type(exc).__name__, str(exc)[:120]), raw)]
    if strict and not plausible(raw, s):
        return True, []
    try:
        rows = interpret(s, raw)
    except RecursionError:
        return False, [row("Too complex", "The expression is nested too deeply", raw)]
    except Exception as exc:
        if strict:
            return True, []
        msg = str(exc).splitlines()[0][:120] if str(exc) else ""
        if isinstance(exc, Nope):
            return False, [row("Couldn't interpret that", msg.capitalize(), raw)]
        return False, [row("Couldn't interpret that", "%s%s" % (type(exc).__name__, ": " + msg if msg else ""), raw)]
    rows = [r for r in rows if r and r.get("t")]
    if strict and len(rows) == 1 and squash(rows[0]["t"]) in (squash(raw), squash(pretty(s))):
        return True, []
    return True, rows[:10]


def evaluate(text, strict=False, ans=""):
    return evaluate_full(text, strict, ans)[1]


QUICK = False


def evaluate_json(text, ans="", quick=False):
    """Bridge for Kotlin (Chaquopy). Always returns a JSON string, never raises."""
    global QUICK
    t0 = time.perf_counter()
    QUICK = bool(quick)
    try:
        ok, rows = evaluate_full(str(text), False, str(ans or ""))
    except BaseException as exc:  # noqa: B902 - nothing may escape into the JVM
        ok, rows = False, [row("Engine error", "%s: %s" % (type(exc).__name__, str(exc)[:120]), str(text))]
    finally:
        QUICK = False
    rows = [{"t": str(r["t"]), "s": str(r.get("s", "")), "c": str(r.get("c", r["t"]))} for r in rows]
    return json.dumps({"ok": ok, "rows": rows, "ms": int((time.perf_counter() - t0) * 1000)},
                      ensure_ascii=False)


def warmup():
    """Import + exercise the hot paths once so the first real query is fast."""
    for q in ("1+1", "x^2 = 4", "int x^2", "d/dx sin x"):
        evaluate_full(q)
    return "ready"


if __name__ == "__main__":
    for q in sys.argv[1:]:
        print(">>", q)
        for r in evaluate(q, False):
            print("   %-50s | %s" % (r["t"], r["s"]))
