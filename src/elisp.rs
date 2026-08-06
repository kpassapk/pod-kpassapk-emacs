//! Locate (or materialize) the elisp brain the emacs child loads.
//!
//! The .el sources are compiled into the binary, so a released pod is a single
//! file. A repo checkout is preferred when one encloses the executable, so
//! elisp edits take effect without rebuilding.

use std::fs;
use std::path::{Path, PathBuf};

const RESOURCES: &[(&str, &str)] = &[
    ("pod-emacs.el", include_str!("../resources/pod-emacs.el")),
    ("pod-emacs-util.el", include_str!("../resources/pod-emacs-util.el")),
];

const VENDOR: &[(&str, &str)] = &[
    ("bencode.el", include_str!("../vendor/bencode.el")),
    ("cljbang.el", include_str!("../vendor/cljbang.el")),
    ("cljbang-core.el", include_str!("../vendor/cljbang-core.el")),
    ("cljbang-string.el", include_str!("../vendor/cljbang-string.el")),
];

fn is_elisp_root(dir: &Path) -> bool {
    dir.join("resources/pod-emacs.el").is_file() && dir.join("vendor/bencode.el").is_file()
}

/// Return the directory whose resources/ and vendor/ the emacs child loads.
///
/// Order: `$POD_KPASSAPK_EMACS_ELISP` override; a checkout enclosing the
/// executable (covers target/{debug,release}/ under the repo root); otherwise
/// the embedded sources, extracted under the cache dir.
pub fn elisp_root(cache: &Path) -> PathBuf {
    if let Some(dir) = std::env::var_os("POD_KPASSAPK_EMACS_ELISP") {
        return PathBuf::from(dir);
    }
    if let Ok(exe) = std::env::current_exe().and_then(fs::canonicalize) {
        let mut dir = exe.parent();
        for _ in 0..4 {
            match dir {
                Some(d) if is_elisp_root(d) => return d.to_path_buf(),
                Some(d) => dir = d.parent(),
                None => break,
            }
        }
    }
    extract_embedded(cache)
}

fn extract_embedded(cache: &Path) -> PathBuf {
    let root = cache.join("elisp").join(env!("CARGO_PKG_VERSION"));
    let marker = root.join(".complete");
    if marker.is_file() {
        return root;
    }
    for (sub, files) in [("resources", RESOURCES), ("vendor", VENDOR)] {
        let dir = root.join(sub);
        if let Err(e) = fs::create_dir_all(&dir) {
            crate::die(&format!("cannot create {}: {e}", dir.display()));
        }
        for (name, contents) in files {
            // write + rename: concurrent pod startups never see a partial file
            let tmp = dir.join(format!("{name}.tmp-{}", std::process::id()));
            if let Err(e) = fs::write(&tmp, contents).and_then(|()| fs::rename(&tmp, dir.join(name))) {
                crate::die(&format!("cannot extract {}/{name}: {e}", dir.display()));
            }
        }
    }
    if let Err(e) = fs::write(&marker, "") {
        crate::die(&format!("cannot write {}: {e}", marker.display()));
    }
    root
}
