# Releasing

One command:

```
bb release 0.4.0            # cut and publish v0.4.0, then open its pod-registry PR
bb release 0.4.0 --dry-run  # run every check, change nothing
```

## What `bb release <version>` does

1. **Guards** — refuses to run unless: you are on `main`, the working tree is
   clean, tag `v<version>` exists neither locally nor on origin,
   `CHANGELOG.md` has a non-empty `[Unreleased]` section, and `gh` can reach
   the `kpassapk/pod-registry` fork.
2. **Bumps** the version in `Cargo.toml`.
3. **Cuts the changelog** — inserts `## [<version>] - <today>` under
   `## [Unreleased]` and updates the compare links at the bottom. (Skipping
   this step by hand is how v0.2.0 ended up tagged with no changelog section —
   the guard makes that impossible now.)
4. **Builds and tests** — `bb test`: `cargo build --release` (refreshes
   `Cargo.lock`), then the full test suite in a temporary user dir, so packages
   already in `<cache>/emacs.d` can't affect it. Any failure aborts before
   anything is committed.
5. **Commits, tags, pushes** — commit `Release v<version>`, tag `v<version>`,
   push `main` and the tag together.
6. **Opens the pod-registry PR** — once CI has published the GitHub Release;
   see [Publishing to the pod registry](#publishing-to-the-pod-registry).

Pushing the tag triggers `.github/workflows/release.yml`, which builds the
four platform binaries (linux/macos × amd64/aarch64, static musl on linux),
zips them with sha256 checksums, and publishes a GitHub Release. Step 6 watches
that run (`gh run watch`) before it does anything else.

The implementation lives in `scripts/release.clj` (namespace `release`, on the
classpath via `:paths ["scripts"]` in `bb.edn`, following the pattern of
babashka's own pods, e.g. babashka-sql-pods). Unlike pods that upload release
artifacts from the release machine with `borkdude/gh-release-artifact`, this
pod needs a cross-platform build matrix, so CI owns the artifact uploads and
the local script tags, then waits for them.

## Version numbering

Pre-1.0 semver: breaking changes (renamed namespaces, changed op semantics,
changed EDN shapes) bump the **minor** version; everything else bumps the
patch version. Renames are especially breaking for a pod — clients hardcode
namespace strings in `require` — so when in doubt, bump minor and call it out
under a **BREAKING** heading in the changelog.

## Publishing to the pod registry

The [pod registry](https://github.com/babashka/pod-registry) lets users load
the pod by name, no download step:

```clojure
(require '[babashka.pods :as pods])
(pods/load-pod 'kpassapk/emacs "0.4.0")
```

Registration is a PR per version against `babashka/pod-registry`, which
`bb release` opens as its last step. To run that step on its own — to retry
after it failed, or for a release cut before it existed:

```
bb registry-pr 0.4.0            # version defaults to the one in Cargo.toml
bb registry-pr 0.4.0 --dry-run  # commit in a temporary clone and show it; no push, no PR
```

It refuses if a PR for the version is already open or the registry already has
its manifest, then:

1. Waits for the tag's `release.yml` run to succeed and checks the GitHub
   Release has every platform zip, so the PR never points at missing files.
2. Clones the fork `kpassapk/pod-registry` into a temporary directory and
   branches `kpassapk-emacs-<version>` from the registry's own `master` (gh
   adds it as the `upstream` remote), so the fork's `master` can be stale.
3. Commits `Update kpassapk/emacs pod to <version>`: the manifest at
   `manifests/kpassapk/emacs/<version>/manifest.edn` (the text `bb manifest`
   prints), and the version bumped in the registry README's pod table and in
   `examples/kpassapk_emacs.clj`.
4. Force-pushes the branch to the fork, so a rerun can replace a branch whose
   PR never got opened, and opens the PR.

It needs `gh` logged in with push access to the fork.

The manifest's `:os/name` values are regex patterns matched against the JVM's
`os.name` (`Linux.*`, `Mac.*`), and `:os/arch` against `os.arch` — note macOS
Intel reports `x86_64` where Linux reports `amd64`, which is why the two amd64
artifacts carry different `:os/arch` values.

## Manual fallback

If the script can't run, the steps it automates, in order: bump `Cargo.toml`,
cut `CHANGELOG.md` (new section + compare links), `bb test`, commit
`Release vX.Y.Z`, `git tag vX.Y.Z`, `git push origin main vX.Y.Z`. Once the
GitHub Release has its assets, open the registry PR from a branch of the fork:
`bb manifest > manifests/kpassapk/emacs/X.Y.Z/manifest.edn`, and bump the
version in the README's pod table and in `examples/kpassapk_emacs.clj`.
