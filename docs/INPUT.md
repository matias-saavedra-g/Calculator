# Input: keyboard, autocomplete, memory, fuzzy search

## The keyboard is your keyboard

The input is a Compose `BasicTextField` with `KeyboardType.Text`. That's the same full keyboard
(Gboard, SwiftKey, …) you use in every app: no calculator keypad, no numeric-only pad. Options:

| Setting | Why |
|---|---|
| `autoCorrectEnabled = false` | `sin` must never become `sun` (checked on Gboard: real key taps typed `sinx sinx` unchanged) |
| `TYPE_TEXT_FLAG_NO_SUGGESTIONS` (via `InterceptPlatformTextInput`) | asks the keyboard to hide its word strip so our bar is the only one |
| `KeyboardCapitalization.None` | no `Sin` at the start of a line |
| `ImeAction.Go` | the keyboard's action key evaluates (also the ↵ button and hardware Enter) |
| `IME_FLAG_NO_EXTRACT_UI` | no full-screen editor in landscape |

**Caveat:** some Gboard versions ignore the no-suggestions flag and still show words (in | I'm | into).
Autocorrect stays off, so they never replace what you type. Only tapping one inserts a word.

## The accessory bar (top of the keyboard)

```text
[⇥]  (solve) (int) (sum) (d/dx) …   [↑] [↓] [🔍]
```

It sits in the layout directly above the IME (`imePadding`), so it moves with the keyboard.

* **⇥ Tab:** accepts the gray **ghost** suggestion. With no ghost, it completes the current word
  with the first chip. Hardware Tab and → at the end of the line do the same.
* **Chips** (M3 *suggestion chips*): completions for the word at the caret.
  * Empty input: starters `solve int sum d/dx lim factor sqrt() pi ans`.
  * While typing a word: prefix matches (exact case first, commands first at line start, then shorter
    labels), then fuzzy matches (`itg` → `integrate`). Capitalised words prefer Wolfram heads
    (`Sq` → `Sqrt[]`).
  * After a space: context keywords. `int x^2 ` offers `dx` `from`, `int … from 0 ` offers `to`,
    `sum 1 ` offers `to` `from`, `solve x^2=4 ` offers `for`, `limit of …` offers `as`.
  * Functions insert `name()` with the caret inside. Keywords get a leading space when needed.
* **↑ / ↓:** walk history like fish. The text typed when you first press ↑ becomes a filter
  (substring, case-insensitive), so `int` + ↑↑ steps through your previous integrals. ↓ past the newest
  match restores what you had typed.
* **🔍:** fuzzy history search (below). Hardware **Ctrl+R** opens it too.

## Ghost suggestions (fish-style autosuggestions)

Drawn after the caret in a faded colour by a Compose `OutputTransformation`. It isn't part of the
text and the caret can't enter it.

1. If a previous input **starts with** everything typed so far, the most recent one is suggested
   (`in` → `int x^2 from 0 to 3`).
2. Otherwise the best completion of the current word is suggested (`sq` → `sqrt()`).
3. Only when the caret is at the end of the text and nothing is selected.

## Memory

* **History** is the memory: every evaluated line, newest at the bottom like a terminal, persisted
  (see HISTORY_FORMAT.md). Re-running a line moves it to the end (fish de-duplication).
* **`ans`** is the last successful result.
* Tap an entry to edit it again. Long-press to copy the result, any extra row (e.g. the decimal),
  or the input, or to delete it. Tap the live preview to copy it.

## Fuzzy search (Ctrl+R)

A bottom sheet with a search field and every distinct history input, ranked by an **fzf-style**
matcher (`input/Fuzzy.kt`):

* the query is split on spaces and every term must match (AND), each as a case-insensitive subsequence;
* for each term: forward scan to the first full match, then a backward scan to the **shortest window**
  (fzf v1). Scoring is +16 per matched char, +8 consecutive, +10 word boundary, +12 match at start,
  −3 gap open, −1 per gap char;
* ties are broken by recency. Matched characters are highlighted in the primary colour.

`intx2` finds `int x^2 from 0 to 1`. `sum 100` finds `sum 1 to 100` but not `sum 1 to 99`.
Tap a result, or press the keyboard's search key for the top result, to put it in the input.

Unit tests: `app/src/test/java/com/mattia/calculator/input/InputLogicTest.kt` (16 tests).
