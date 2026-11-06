# ADR 0007 evidence (#384)

Raw output of the disposable benchmark runs cited in
[ADR 0007](../0007-web-client-approach.md), copied here because GitHub Actions
artifacts expire after 14 days. All data is synthetic; no real transactions.

| File | Source run | Content |
|---|---|---|
| `run-42111099580-chromium-results.json` | [#42111099580](https://github.com/hugiFreezy0815/TrackMyWealth/actions/runs/42111099580) (2026-11-05) | Chromium timings and accessibility-tree counts behind the ADR's results table |
| `run-42111099580-combined-bundle.txt` | same | Size of the single build that held both renderers |
| `run-42179686520-isolated-bundles.json` | [#42179686520](https://github.com/hugiFreezy0815/TrackMyWealth/actions/runs/42179686520) (2026-11-06) | Renderer-isolated production web export sizes |
| `run-42179686520-chromium-results.json` | same | Repeat of the Chromium run, for comparison |

The screenshots from those artifacts are not kept. The code that produced the
files is at commit `fa37fdf8` (tag `spike-384-evidence`); it must not be merged.
