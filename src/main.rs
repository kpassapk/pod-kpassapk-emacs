//! The pod executable. Owns babashka-facing stdio and transcodes the raw
//! bencode byte stream to/from base64 lines for an `emacs --batch` child.
//!
//! See doc/adr/0001-transport-architecture.md.

mod elisp;

use std::fs::{self, File};
use std::io::{BufRead, BufReader, Read, Write};
use std::path::PathBuf;
use std::process::{exit, Command, Stdio};

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine as _;

// pod stdout is the protocol channel; everything human-facing goes to stderr.
fn log(msg: &str) {
    eprintln!("[pod-kpassapk-emacs] {msg}");
}

pub fn die(msg: &str) -> ! {
    log(msg);
    exit(1);
}

pub fn cache_dir() -> PathBuf {
    if let Some(dir) = std::env::var_os("POD_KPASSAPK_EMACS_CACHE") {
        return PathBuf::from(dir);
    }
    if let Some(xdg) = std::env::var_os("XDG_CACHE_HOME") {
        return PathBuf::from(xdg).join("pod-kpassapk-emacs");
    }
    match std::env::var_os("HOME") {
        Some(home) => PathBuf::from(home).join(".cache/pod-kpassapk-emacs"),
        None => die("cannot locate a cache dir: none of POD_KPASSAPK_EMACS_CACHE, XDG_CACHE_HOME, HOME are set"),
    }
}

#[cfg(unix)]
fn is_executable(path: &std::path::Path) -> bool {
    use std::os::unix::fs::PermissionsExt;
    path.is_file()
        && fs::metadata(path)
            .map(|m| m.permissions().mode() & 0o111 != 0)
            .unwrap_or(false)
}

#[cfg(not(unix))]
fn is_executable(path: &std::path::Path) -> bool {
    path.is_file()
}

fn which(name: &str) -> Option<PathBuf> {
    let paths = std::env::var_os("PATH")?;
    std::env::split_paths(&paths)
        .map(|d| d.join(name))
        .find(|p| is_executable(p))
}

/// Well-known Emacs locations to try when it is not on PATH.
fn fallback_paths() -> Vec<PathBuf> {
    if cfg!(target_os = "macos") {
        // macOS GUI builds (emacs-plus, emacs-mac, official cask) ship inside a
        // .app bundle whose binary is not on PATH.
        let mut paths = Vec::new();
        if let Some(home) = std::env::var_os("HOME") {
            paths.push(PathBuf::from(home).join("Applications/Emacs.app/Contents/MacOS/Emacs"));
        }
        paths.push(PathBuf::from("/Applications/Emacs.app/Contents/MacOS/Emacs"));
        paths
    } else {
        // Linux/other: distro and manual installs that may live off PATH.
        ["/usr/local/bin/emacs", "/usr/bin/emacs", "/snap/bin/emacs"]
            .iter()
            .map(PathBuf::from)
            .collect()
    }
}

fn resolve_emacs() -> Option<PathBuf> {
    if let Some(bin) = std::env::var_os("POD_KPASSAPK_EMACS_BIN") {
        return Some(PathBuf::from(bin));
    }
    which("emacs").or_else(|| fallback_paths().into_iter().find(|p| is_executable(p)))
}

/// babashka stdin -> base64 lines -> emacs stdin.
/// Reads raw bytes, base64-encodes each chunk, writes one line per chunk.
/// Dropping `emacs_in` on EOF closes the child's stdin so emacs can finish.
fn pump_in(mut emacs_in: std::process::ChildStdin) {
    let mut stdin = std::io::stdin().lock();
    let mut buf = [0u8; 65536];
    loop {
        match stdin.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                let mut line = B64.encode(&buf[..n]).into_bytes();
                line.push(b'\n');
                if emacs_in.write_all(&line).and_then(|()| emacs_in.flush()).is_err() {
                    break;
                }
            }
            Err(e) => {
                log(&format!("pump-in error: {e}"));
                break;
            }
        }
    }
}

/// emacs stdout (base64 lines) -> raw bytes -> babashka stdout.
fn pump_out(emacs_out: std::process::ChildStdout) {
    let mut reader = BufReader::new(emacs_out);
    let mut out = std::io::stdout().lock();
    let mut line = String::new();
    loop {
        line.clear();
        match reader.read_line(&mut line) {
            Ok(0) => break,
            Ok(_) => {
                let trimmed = line.trim_end();
                if trimmed.is_empty() {
                    continue;
                }
                match B64.decode(trimmed) {
                    Ok(bytes) => {
                        if out.write_all(&bytes).and_then(|()| out.flush()).is_err() {
                            break;
                        }
                    }
                    Err(e) => {
                        log(&format!("undecodable base64 line from emacs: {e}"));
                        break;
                    }
                }
            }
            Err(e) => {
                log(&format!("pump-out error: {e}"));
                break;
            }
        }
    }
}

const QUIET: &str = "(setq byte-compile-warnings nil \
warning-minimum-level :emergency \
native-comp-jit-compilation nil \
native-comp-async-report-warnings-errors 'silent)";

fn main() {
    let cache = cache_dir();
    if let Err(e) = fs::create_dir_all(&cache) {
        die(&format!("cannot create cache dir {}: {e}", cache.display()));
    }

    let root = elisp::elisp_root(&cache);
    let emacs = resolve_emacs()
        .unwrap_or_else(|| die("no emacs found: set POD_KPASSAPK_EMACS_BIN or put emacs on PATH"));
    log(&format!("emacs: {}", emacs.display()));

    let logfile = cache.join("emacs.log");
    let err_stream = File::create(&logfile)
        .unwrap_or_else(|e| die(&format!("cannot open {}: {e}", logfile.display())));

    let mut child = Command::new(&emacs)
        .arg("--batch")
        .arg("-Q")
        .arg("--eval")
        .arg(QUIET)
        .arg("-L")
        .arg(root.join("vendor"))
        .arg("-L")
        .arg(root.join("resources"))
        .arg("-l")
        .arg("pod-emacs")
        .arg("-f")
        .arg("pod-emacs-main")
        .env("EMACS_INHIBIT_AUTOMATIC_NATIVE_COMPILATION", "1")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::from(err_stream))
        .spawn()
        .unwrap_or_else(|e| die(&format!("cannot launch {}: {e}", emacs.display())));

    let emacs_in = child.stdin.take().expect("child stdin is piped");
    let emacs_out = child.stdout.take().expect("child stdout is piped");

    std::thread::spawn(move || pump_in(emacs_in));

    // pump emacs->babashka on the main thread; returns when emacs closes stdout
    pump_out(emacs_out);

    // exit() also reaps the pump-in thread, which may be blocked reading stdin
    let code = child.wait().map(|s| s.code().unwrap_or(1)).unwrap_or(1);
    exit(code);
}
