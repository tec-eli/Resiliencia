# Website

This website is built with [Docusaurus](https://docusaurus.io/), a static site generator. All commands below are run
from this directory (`site/`), not the repository root.

## Prerequisites

- Node.js `>= 20.0` (see the `engines` field in `package.json`)
- A Node package manager: `npm`, `yarn`, or `pnpm` — examples below use `npm`, substitute your own

## Quick start

Run these commands as a single block to install dependencies and start the local dev server in one go. Most
IDE terminals (including IntelliJ's) let you select multiple lines and execute them together, which avoids
stopping partway through:

```bash
cd site
npm install
npm run start
```

`npm run start` starts a local dev server and opens a browser window at `http://localhost:3000`. Most changes
(content, config, components) are reflected live without restarting the server. If the browser doesn't open
automatically, or the port is already in use, check the terminal output — Docusaurus prints the actual URL and
picks a different port automatically when 3000 is taken.

## Build

```bash
cd site
npm run build
```

Generates static content into the `build/` directory. The output can be served by any static file host — no
Node.js runtime is required to serve it.

To sanity-check a production build locally before deploying:

```bash
npm run serve
```

## Deployment

Using SSH:

```bash
USE_SSH=true npm run deploy
```

Not using SSH:

```bash
GIT_USER=<your GitHub username> npm run deploy
```

If the site is hosted on GitHub Pages, `npm run deploy` builds the site and pushes the result to the `gh-pages`
branch.

## Troubleshooting

- **Nothing happens / errors when running from an IDE run configuration**: run the commands directly in a terminal
  (integrated or external) instead of through a preconfigured IDE run/npm configuration — those can silently use the
  wrong working directory or a different Node version than the one on your `PATH`.
- **`npm run start` fails immediately**: confirm `node -v` reports `20.0` or higher, and that `npm install` completed
  without errors before starting the server.
- **Stale content after pulling changes**: run `npm run clear` to wipe the Docusaurus cache, then `npm run start`
  again.
