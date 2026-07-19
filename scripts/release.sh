#!/usr/bin/env bash
set -euo pipefail

# Cuts a resiliencia release: bumps the reactor version, commits, tags, and pushes the tag.
# Pushing the tag triggers .github/workflows/release.yml, which builds, signs, and stages the
# publishable modules (core, patterns, compose, metrics, micrometer, opentelemetry, test) on the
# Central Publisher Portal. A manual "Publish" click on the Portal is still required after that,
# since Central artifacts are immutable once released.
#
# Usage: scripts/release.sh <version>   e.g. scripts/release.sh 1.0.0-beta.1

if [ $# -ne 1 ]; then
  echo "Usage: $0 <version>" >&2
  exit 1
fi

VERSION="$1"
TAG="v${VERSION}"

if [[ "$VERSION" == *SNAPSHOT* ]]; then
  echo "Refusing to release a SNAPSHOT version: Central rejects SNAPSHOT deployments." >&2
  exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
  echo "Working tree is not clean. Commit or stash changes first." >&2
  exit 1
fi

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$CURRENT_BRANCH" != "main" ]; then
  echo "Refusing to release from branch '$CURRENT_BRANCH' (expected 'main')." >&2
  exit 1
fi

if git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "Tag $TAG already exists." >&2
  exit 1
fi

echo "Bumping reactor version to $VERSION..."
mvn -q org.codehaus.mojo:versions-maven-plugin:2.21.0:set \
  -DnewVersion="$VERSION" -DprocessAllModules=true -DgenerateBackupPoms=false

echo "Verifying the reactor still builds (compile, test, package sources/javadoc)..."
mvn -q clean verify

git add pom.xml resiliencia-*/pom.xml
git commit -m "Release $VERSION"

read -r -p "About to tag $TAG and push it, which triggers the release workflow. Continue? [y/N] " CONFIRM
if [[ "$CONFIRM" != "y" && "$CONFIRM" != "Y" ]]; then
  echo "Aborted before tagging. The version bump commit is still local; run 'git reset --hard HEAD~1' if you want to undo it." >&2
  exit 1
fi

git tag -a "$TAG" -m "Release $VERSION"
git push origin main "$TAG"

echo "Pushed $TAG. Watch the Release workflow, then review and Publish the staged bundle at https://central.sonatype.com/publishing."
