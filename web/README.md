# web/ — GitHub Pages site

Source for the project's GitHub Pages site. Both files are self-contained — fonts and the banner image
are embedded as base64 — so there's nothing to install to view or deploy them.

- `index.html` — the homepage.
- `resiliencia-javadoc.css` — a `stylesheetfile` override for `maven-javadoc-plugin`'s aggregate report,
  reskinned to match the homepage (same palette, same Indie Flower / Rock Salt / JetBrains Mono fonts).

The Javadoc HTML itself is **not** committed — it's generated fresh from source every time, locally or in CI.

## Regenerating the aggregated Javadoc locally

`resiliencia-stress` and `resiliencia-examples` are excluded — both fail `javadoc:aggregate` on a JPMS
module-path conflict, and neither is part of the public API surface.

```bash
# from the repository root
mvn -pl '!resiliencia-stress,!resiliencia-examples' org.apache.maven.plugins:maven-javadoc-plugin:3.10.1:aggregate -Dstylesheetfile="$(pwd)/web/resiliencia-javadoc.css"
```

```powershell
# from the repository root (PowerShell)
mvn -pl '!resiliencia-stress,!resiliencia-examples' org.apache.maven.plugins:maven-javadoc-plugin:3.10.1:aggregate "-Dstylesheetfile=$PWD\web\resiliencia-javadoc.css"
```

Output lands in `target/reports/apidocs/`. If you only change `-Dstylesheetfile` and no Java source,
Maven's staleness check won't notice the difference — delete `target/reports/apidocs` first to force a
full rebuild.

## Previewing the full site locally

```bash
mkdir -p _site
cp web/index.html _site/index.html
cp -r target/reports/apidocs _site/apidocs
python -m http.server 8000 --directory _site
# -> http://localhost:8000/
```

## Deploying

`.github/workflows/pages.yml` runs the two steps above and publishes the result to GitHub Pages on every
push to `main`, or on demand from the Actions tab ("Deploy GitHub Pages" → "Run workflow" — this also
works from a feature branch, useful for testing before merging).

One-time setup, by a repo admin, before the first deploy can succeed:
**Settings → Pages → Source → GitHub Actions.**

Live at: https://tec-eli.github.io/resiliencia/
