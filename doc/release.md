# Releasing

One command:

```
bb release 0.4.0            # cut and publish v0.4.0
bb release 0.4.0 --dry-run  # run every check, change nothing
```

## What `bb release <version>` does

1. **Guards** — refuses to run unless: you are on `main`, the working tree is
   clean, tag `v<version>` exists neither locally nor on origin, and
   `CHANGELOG.md` has a non-empty `[Unreleased]` section.
2. **Bumps** the version in `Cargo.toml`.
3. **Cuts the changelog** — inserts `## [<version>] - <today>` under
   `## [Unreleased]` and updates the compare links at the bottom. (Skipping
   this step by hand is how v0.2.0 ended up tagged with no changelog section —
   the guard makes that impossible now.)
4. **Builds and tests** — `cargo build --release` (refreshes `Cargo.lock`),
   then the full test suite. Any failure aborts before anything is committed.
5. **Commits, tags, pushes** — commit `Release v<version>`, tag `v<version>`,
   push `main` and the tag together.

Pushing the tag triggers `.github/workflows/release.yml`, which builds the
four platform binaries (linux/macos × amd64/aarch64, static musl on linux),
zips them with sha256 checksums, and publishes a GitHub Release. Watch it:

```
gh run watch
gh release view v0.4.0     # should list 8 assets (4 zips + 4 checksums)
```

The implementation lives in `scripts/release.clj` (namespace `release`, on the
classpath via `:paths ["scripts"]` in `bb.edn`, following the pattern of
babashka's own pods, e.g. babashka-sql-pods). Unlike pods that upload release
artifacts from the release machine with `borkdude/gh-release-artifact`, this
pod needs a cross-platform build matrix, so CI owns the artifact uploads and
the local script only tags.

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

Registration is a PR per version:

1. Wait for the GitHub Release assets to be up (`gh release view v0.4.0`).
2. Fork/clone `babashka/pod-registry`.
3. Generate the manifest — `bb release` prints it at the end, or run it any
   time for the current `Cargo.toml` version:

   ```
   bb manifest > <pod-registry>/manifests/kpassapk/emacs/0.4.0/manifest.edn
   ```

4. First registration only: add a short usage example under
   `examples/kpassapk_emacs.clj` in the registry repo (theirs run in CI, so
   keep it self-contained; note the pod needs an Emacs on the host).
5. Open the PR.

The manifest's `:os/name` values are regex patterns matched against the JVM's
`os.name` (`Linux.*`, `Mac.*`), and `:os/arch` against `os.arch` — note macOS
Intel reports `x86_64` where Linux reports `amd64`, which is why the two amd64
artifacts carry different `:os/arch` values.

## Manual fallback

If the script can't run, the steps it automates, in order: bump `Cargo.toml`,
cut `CHANGELOG.md` (new section + compare links), `bb test`, commit
`Release vX.Y.Z`, `git tag vX.Y.Z`, `git push origin main vX.Y.Z`.
