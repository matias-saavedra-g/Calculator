# Syntax reference

Type with the normal keyboard. Spaces are optional almost everywhere; `*` is optional
(`2x`, `3 sin x`, `x y`). `^` or `**` is power. Case does not matter for words (`Sin x` = `sin x`),
except Wolfram Language calls, which must be capitalised (`Sin[x]`).

## Numbers and arithmetic

| Input | Result |
|---|---|
| `2^64`, `5!`, `1/3 + 1/6` | exact: `18446744073709551616`, `120`, `1/2` |
| `sqrt(2)`, `√16`, `cbrt 27` | `sqrt(2)` + decimal `1.414213562`, `4`, `3` |
| `15% of 200`, `10 mod 3` | `30`, `1` |
| `pi to 50 digits`, `N[E, 30]`, `numeric sqrt 2` | arbitrary precision decimals |
| `e^(i pi)`, `(3+4i)(1-2i)` | complex numbers: `-1`, `11 - 2i` |
| `sin(30 deg)`, `sin(30°)` | degrees: `1/2` |
| `log(8, 2)`, `ln e^3`, `log10 1000` | logarithms |
| `255 in hex`, `0xff + 1`, `bin 10` | bases |
| `factor 360`, `is 97 prime` | `2^3 × 3^2 × 5`, `Yes` |
| `what is 3 times 4 plus 2` | natural language: `14` |
| `ans * 2` | `ans` = the last successful result |

Functions: `sin cos tan cot sec csc asin acos atan sinh cosh tanh ln log log2 log10 exp sqrt cbrt
abs floor ceil gcd lcm binomial factorial gamma erf zeta max min mod re im arg conjugate isprime
prime fibonacci totient`. Constants: `pi` (π), `e`, `i`, `oo`/`infinity` (∞), `phi`, `tau`, `deg`.

**Phone shortcut:** a function name followed directly by one letter is applied to it:
`sinx` = `sin(x)`, `lnx` = `ln(x)`, `cos2x` = `cos(2x)`, `sinhx` = `sinh(x)`.

## solve

Any input containing `=`, `<`, `>`, `<=`, `>=` is solved automatically; `solve` makes it explicit.

| Input | Result |
|---|---|
| `2x + 2 = 0`, `solve x^2 - 4 = 0` | `x = -1`;  `x = -2,  x = 2` |
| `solve x^2 = 4 for x`, `ax^2 + bx + c = 0 for x` | choose the variable |
| `x^2 + y^2 = 25, x - y = 1` | systems (comma or `and`) |
| `x^2 < 4`, `2x + 3 >= 7` | inequalities: `-2 < x < 2` plus interval notation |
| `sin(x) = 1/2` | principal solutions **and** the general solution with n ∈ ℤ |
| `cos(x) = x`, `x^5 - x + 1 = 0` | numeric roots when no closed form exists |
| `roots of x^3 - 6x^2 + 11x - 6` | `x = 1, 2, 3` |

## int (integrals)

| Input | Meaning |
|---|---|
| `int x^2` · `integrate x^2` · `integral of x^2` · `antiderivative of x^2` | indefinite → `x^3/3 + C` |
| `int x e^x dx` · `int x*y dy` · `int x*y wrt y` · `int x^2, x` | choose the variable |
| `int x^2 from 0 to 1` · `int x^2 dx from 0 to 1` · `int x^2 from x=0 to 1` | definite → `1/3` |
| `int_0^1 x^2 dx` · `int_(0)^(pi/2) cos x` | sub/superscript style bounds |
| `int x^2 x=0..3` · `int x^2, x, 0, 3` · `int x^2 over 0..3` | other definite forms |
| `int e^(-x^2) from -oo to oo` · `int 1/x^2 from 1 to oo` | improper integrals |
| `int 1/x from 1 to oo` | `∞` + "The integral diverges" |
| `int x^x from 0 to 1` | no closed form → numeric `0.7834305107…` |
| `Integrate[f, x]` · `Integrate[f, {x, a, b}]` · `∫ x^2` | Wolfram / symbol forms |

## sum / prod

| Input | Meaning |
|---|---|
| `sum 1 to 100` · `sum 1..100` · `sum from 1 to 100` | every integer from 1 to 100 → `5050` |
| `sum of all numbers between 1 and 100` | same, in words (bounds inclusive) |
| `sum 1 to 99 step 2` | 1 + 3 + … + 99 → `2500` |
| `sum 1 to 10^9` | instant (closed form) → `500000000500000000` |
| `sum k^2 from 1 to 10` · `sum k^2, k, 1, 10` | a series, variable inferred → `385` |
| `sum 1/n^2 from n=1 to oo` · `sum i for i = 1 to 100` | explicit variable, infinite series → `π^2/6` |
| `sum 3, 5, 7` · `sum [1, 2, 3]` | a list of values |
| `prod 1 to 5` · `product of k from k=1 to 5` | products → `120` |
| `Sum[k, {k, 1, 100}]` · `∑` | Wolfram / symbol forms |

## Derivatives, limits, series, algebra

| Input | Result |
|---|---|
| `d/dx x^3 e^x` · `derivative of sin(x)^2` · `diff x^2 y wrt y` | derivatives |
| `second derivative of x^4` · `3rd derivative of …` · `d^2/dx^2 …` | higher order |
| `derivative of x^2 at x=3` | evaluated → `6` |
| `lim x->0 sin(x)/x` · `limit of (1+1/x)^x as x->oo` · `limit of 1/x as x -> 0+` | limits, one-sided with `+`/`-` |
| `taylor series of cos x` · `series e^x at 0 order 4` | series |
| `factor x^3 - 8` · `expand (x+1)^4` · `simplify sin(x)^2 + cos(x)^2` | algebra |
| `partial fractions 1/(x^2-1)` | `apart` |
| `x^2 + y where x = 3, y = 1` | substitution |
| `x^2 - 5x + 6` (bare expression) | simplified, expanded, factored, roots, derivative, integral |

## Matrices

`det [[1,2],[3,4]]`, `inverse of [[1,2],[3,4]]`, `transpose …`, `eigenvalues of [[2,1],[1,2]]`,
`rank …`. Typing a bare square matrix also shows its determinant and inverse.

## Wolfram Language subset

Capitalised `Head[args]` calls are translated (lists `{…}` become matrices / specs):

`Solve[x^2 == 2, x]`, `Solve[{x + y == 3, x - y == 1}, {x, y}]`, `Integrate[f, x]`,
`Integrate[f, {x, a, b}]`, `D[f, x]`, `D[f, {x, n}]`, `Sum[f, {k, a, b}]`, `Product[…]`,
`Limit[f, x -> a]`, `Series[f, {x, a, n}]`, `Factor[…]`, `Expand[…]`, `Simplify[…]`,
`FullSimplify[…]`, `Apart[…]`, `N[x]`, `N[x, digits]`, `FactorInteger[n]`, `PrimeQ[n]`,
`Det[m]`, `Inverse[m]`, `Transpose[m]`, `Eigenvalues[m]`, `MatrixRank[m]`, `BaseForm[n, 16]`,
and functions `Sin[x]`, `Sqrt[x]`, `Log[x]`, `Exp[x]`, `ArcTan[x]`, `Abs[x]`, `Gamma[x]`, …
Constants `Pi`, `E`, `I`, `Infinity`, `Degree`, `GoldenRatio`. Nested calls work:
`1 + Integrate[x, {x, 0, 2}]` → `3`.
