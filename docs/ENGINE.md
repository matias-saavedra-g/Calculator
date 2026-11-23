# The CAS engine

**Short version:** the app runs the real **SymPy 1.14** computer-algebra system, on the phone,
inside an embedded **CPython 3.14** (via Chaquopy), in a separate Android process. Your input
goes through a small parser/translator (`app/src/main/python/calc_engine.py`), which is
the engine of the Flow Launcher **Solve** plugin, extended for the app. That translator picks
the right SymPy operation. Results are exact (`π/2`, `sqrt(2)`, `x^3/3 + C`) whenever SymPy finds
a closed form, with a decimal approximation next to them. When there is no closed form, results
fall back to arbitrary-precision numerics (mpmath).

## 1. Where it runs

```text
┌──────────── UI process (Kotlin / Compose) ───────────┐        ┌────── :engine process ──────┐
│ CalculatorViewModel                                  │        │ EngineService               │
│   preview (debounced 250 ms, quick=true, 8 s limit)  │ Binder │   one "cas-engine" thread   │
│   submit  (Enter,            quick=false, 45 s limit)├───────►│   (64 MB stack)             │
│ EngineClient: one request at a time, per-request     │Messenger   Chaquopy → CPython 3.14  │
│   watchdog; on timeout: kill :engine pid, re-bind,   │◄───────┤   calc_engine.evaluate_json │
│   warm-up again                                      │  JSON  │   SymPy 1.14 + mpmath 1.3   │
└──────────────────────────────────────────────────────┘        └─────────────────────────────┘
```

* **Why a separate process:** CPU-bound Python code can't be interrupted from another thread.
  The only reliable way to stop `100000000!` or a hopeless integral is to kill the process. This
  is the same idea as `worker.py` in the Flow plugin. Killing it never affects the UI, and the
  service is re-bound and warmed up automatically. Verified on the Android 17
  emulator: the engine pid changed after the 8 s preview limit, and the next query worked.
* **Why a 64 MB thread stack:** SymPy recurses deeply (simplification, Risch). Android's
  default thread stack is about 1 MB, which would crash with a native stack overflow.
* **Warm-up:** at app start the engine imports SymPy and runs `1+1`, `x^2 = 4`, `int x^2`, `d/dx sin x`,
  so the first real query does not pay import/JIT costs. The UI shows "Starting math engine…" meanwhile.
* **Bytecode:** Chaquopy compiles all Python (stdlib, SymPy, the engine) to `.pyc` at build time
  using the local Python 3.14, so nothing is compiled on the phone.

## 2. The pipeline for one query

`evaluate_json(text, ans, quick)` → JSON `{"ok": bool, "rows": [{"t","s","c"}…], "ms": int}`
(`t` = result shown big, `s` = what was computed, `c` = what "Copy" puts on the clipboard).

1. **`ans`**: `\bans\b` is replaced by `(previous result)`, which is the copy value of the newest successful entry.
2. **Wolfram Language translation** (`wolfram()`): capitalised `Head[args]` calls are rewritten with a
   bracket-matching recursive descent. A command that is the *whole* input becomes the natural-language
   form (`Integrate[f, {x,a,b}]` → `integrate (f) wrt x from (a) to (b)`) so it gets the nice labels.
   Nested commands become SymPy constructors (`Integral(f, (x, a, b))`) that are evaluated later.
   `{…}` → `[…]`, `==` → `=`, `Infinity` → `oo`, `Sin[x]` → `sin(x)`.
3. **Cleaning** (`clean()`): Unicode (`× ÷ − π √ ∞ ² ≤ ° ∫ ∑`), words (`plus`, `times`, `squared`,
   `square root of`, `infinity`, `mod`), `%` and `% of`, `0x…/0b…` literals, `|x|` → `Abs(x)`,
   lower-casing of known words, and phone shortcuts (`sinx` → `sin(x)`, `cos2x` → `cos(2x)`).
4. **Intent dispatch** (`interpret()`): an ordered list of anchored regexes recognises the command:
   sums (5 forms) → `solve … for …` → `solve` → `d/dx` → derivative → integrals (3 forms) → limits →
   series → `is … prime` → unary algebra/matrix commands → number bases → `to N digits` → `numeric`
   → `… where x = …`. A command word that matched nothing raises "incomplete command". Otherwise an input
   with `= < > <= >=` is solved, and anything else is evaluated.
5. **Parsing** (`P()`): SymPy's `parse_expr` with a **restricted namespace** (`NS`: only math functions
   and constants, `__builtins__` emptied, a character whitelist, no `__` or attribute access). The
   transformations are implicit multiplication (`2x`, `x y`), implicit application (`sin x`), function
   exponentiation (`sin^2 x`), `^` as power, decimals as rationals (`0.1 + 0.2` = exactly `3/10`), and
   splitting of unknown multi-letter symbols (`xy` → `x*y`) except known names.
6. **Compute** with SymPy (below), then **format**: `pretty()` turns `**` into `^`, `pi` into `π`,
   `oo` into `∞`, `I` into `i`, `E` into `e`, and drops `2*x` stars. Numbers get an exact row plus a
   10-digit decimal row. A bare expression also gets expanded/factored forms, roots, derivative and
   antiderivative, but not in `quick` (preview) mode, which keeps typing responsive.

## 3. Integrals: what you get and how SymPy finds it

### Indefinite: `int f`, `int f dx`, `Integrate[f, x]`
The result is an antiderivative `F + C`, plus a simplified form when `simplify` finds a shorter one.
SymPy's `integrate()` tries, in order of speed (from `Integral._eval_integral`):

1. **Table / pattern heuristics**: polynomials, `x^n`, `exp`, `sin`, … and linearity.
2. **Rational functions** (`ratint`): exact partial fractions + log/atan terms (Lazard–Rioboo–Trager).
3. **Risch algorithm** (`risch_integrate`): a decision procedure for elementary functions built from
   `exp`/`log` towers. When it *proves* there's no elementary antiderivative, SymPy moves on to
   special functions.
4. **Meijer G-function** method (`meijerint`): rewrites the integrand as G-functions, integrates, and
   converts back. This is where `erf`, `Si`, `Ei`, `gamma`, Bessel results come from.
5. **Manual integration** (`manualintegrate`): textbook rules such as substitution, parts, trig
   identities and partial fractions.
6. **Heuristic Risch** (`heurisch`, Risch–Norman): an ansatz-based fallback.

If nothing works, the answer is "No closed form found". Examples:

| Input | Result |
|---|---|
| `int x^2` | `x^3/3 + C` |
| `int x e^x dx` | `(x - 1)*exp(x) + C` |
| `int x^5 sin x` | `-x^5*cos(x) + 5x^4*sin(x) + … + 120sin(x) + C` |
| `int 1/(1+x^2)` | `atan(x) + C` |
| `int e^(-x^2)` | `sqrt(π)*erf(x)/2 + C` (non-elementary → special function) |
| `int x*y dy` | `x*y^2/2 + C` (other symbols are constants) |
| `int e^(x^x)` | No closed form found |

### Definite: `int f from a to b`, `int_a^b f`, `int f, x, a, b`, `Integrate[f, {x,a,b}]`
The result is the exact value plus a decimal. SymPy computes `F(b) − F(a)` with limits at the bounds,
checking for singularities inside the interval. For infinite bounds it tries the **Meijer G definite
integration** first, which handles many improper integrals directly. Possible outcomes:

* exact: `int x^2 from 0 to 1` → `1/3`; `int_0^pi sin x` → `2`; `int e^(-x^2) from -oo to oo` → `sqrt(π)`;
  `int sin(x)/x from 0 to oo` → `π/2`; `int ln x from 0 to 1` → `-1`;
* **divergent**: `int 1/x from 1 to oo` → `∞`, "The integral diverges";
* **depends on parameters**: a `Piecewise` result listing the cases (e.g. `int x^a from 0 to 1` → `1/(a+1)` when `a ≠ -1`, else `∞`);
* **numeric**: when no antiderivative exists, mpmath **quadrature** (tanh-sinh / Gauss-Legendre,
  adaptive) gives a 10-digit value marked "(numeric)": `int x^x from 0 to 1` → `0.7834305107`.

Multiple integrals (`int int …`) are not supported as a single command. Integrate twice using `ans`:
`int x*y dy`, then `int ans dx`.

## 4. Sums and products

* **Range sums** `sum a to b [step s]` (your "sum of all numbers in a range"): the bounds must be numeric.
  The engine counts the terms `n = ⌊(b − a)/s⌋ + 1` and uses the arithmetic-series closed form, so
  `sum 1 to 10^12` is instant. Bounds are inclusive, an empty range gives `0` (product: `1`), and a
  non-integer start is rounded up.
* **Series** `sum f from n=a to b` / `sum f from a to b` / `sum f, n, a, b`: SymPy `summation`, which
  uses closed forms for polynomial and geometric terms, **Gosper's algorithm** for hypergeometric
  terms, telescoping and known special series (ζ, …). For infinite series it runs convergence tests:
  `sum 1/n^2 from n=1 to oo` → `π^2/6`, `sum 1/2^n from n=0 to oo` → `2`. A symbolic answer that
  isn't available is evaluated numerically when the sum converges.
* **Lists** `sum 3, 5, 7`, **products** `prod 1 to 5`, `product of k from k=1 to 5`.

## 5. Solving

* **One equation**: `sympy.solve` gives exact roots via radicals, Lambert W, logs and so on. Roots
  that need `CRootOf` (degree ≥ 5) are shown numerically. With trig, `solveset` over ℝ also gives the
  **general solution** (`x = 2πn + π/6`). Without a symbolic solution, `nsolve` (mpmath `findroot`)
  runs from 8 starting points and the distinct roots are listed as "(numeric)".
* **Systems**: `solve([...], vars, dict=True)`, up to 6 solution sets.
* **Inequalities**: `reduce_inequalities`, shown both as `-2 < x < 2` and in interval notation.
* An equation with no free variables is checked: `2+2=4` → `True`.

## 6. Derivatives, limits, series, algebra

`diff` (any order, at a point), limits via the **Gruntz algorithm** (one-sided with `x -> 0+`),
`series` (Taylor/Laurent with `O(·)` term), `factor` (polynomials over ℚ, or integer factorisation),
`expand`, `simplify`, `apart`, matrix `det/inv/eigenvals/rank/transpose`, arbitrary precision `N`.

## 7. Limits and known gaps

* SymPy is strong but not Mathematica. Some integrals that Wolfram Alpha solves in closed form come
  back as numeric values or "No closed form found".
* Only a subset of the Wolfram Language is translated (see SYNTAX.md), not full Mathematica.
* No step-by-step solutions, units, plots or multiple integrals (yet).
* Very large exact computations hit the time limits (8 s preview, 45 s on Enter), are then
  killed, and show "Timed out".

## 8. Testing

`engine-tests/test_engine.py` runs 122 assertions on desktop CPython with the same SymPy version
(whole suite ≈ 2 s): every row of the plugin's original test list, all new `int`/`sum` forms, the
Wolfram subset, `ans`, quick mode, phone shortcuts and inputs that must fail gracefully. Every example
shown inside the app (help sheet and empty state) is also run through the engine.
