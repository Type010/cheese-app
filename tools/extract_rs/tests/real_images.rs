//! Regression test against real kernel images.
//!
//! Images are far too large to commit, so the list comes from an env var:
//!
//! ```text
//! CHEESE_TEST_IMAGES="kernel.img=0x2f12bc0:0x2ad6000;boot.img=0x2f12bc0:0x2ad6000" \
//!   cargo test --release --test real_images -- --nocapture
//! ```
//!
//! Without it the test skips (and still passes), so CI needs no images.

use std::path::{Path, PathBuf};
use std::process::Command;

/// Each entry: `image=init_task:swapper_pg_dir`, offsets in hex (0x optional).
fn parse_cases(spec: &str) -> Vec<(PathBuf, u64, u64)> {
    spec.split(';')
        .filter(|entry| !entry.trim().is_empty())
        .filter_map(|entry| {
            let (path, offsets) = entry.split_once('=')?;
            let (init, swapper) = offsets.split_once(':')?;
            Some((
                PathBuf::from(path.trim()),
                parse_hex(init.trim())?,
                parse_hex(swapper.trim())?,
            ))
        })
        .collect()
}

fn parse_hex(text: &str) -> Option<u64> {
    let trimmed = text.strip_prefix("0x").unwrap_or(text);
    u64::from_str_radix(trimmed, 16).ok()
}

fn extract_json(binary: &Path, image: &Path) -> (i32, String, String) {
    let output = Command::new(binary)
        .arg(image)
        .args(["--format", "json"])
        .output()
        .expect("run cheese-extract");
    (
        output.status.code().unwrap_or(-1),
        String::from_utf8_lossy(&output.stdout).to_string(),
        String::from_utf8_lossy(&output.stderr).to_string(),
    )
}

/// Slice out a top-level object (`"key": { ... }`). The report is generated here,
/// so brace matching is enough: values never contain unescaped braces.
fn json_object<'a>(json: &'a str, key: &str) -> Option<&'a str> {
    let needle = format!("\"{key}\": {{");
    let start = json.find(&needle)? + needle.len();
    let rest = &json[start..];
    let mut depth = 1usize;
    for (index, ch) in rest.char_indices() {
        match ch {
            '{' => depth += 1,
            '}' => {
                depth -= 1;
                if depth == 0 {
                    return Some(&rest[..index]);
                }
            }
            _ => {}
        }
    }
    None
}

/// Read a numeric field from an object slice.
fn json_number(object: &str, key: &str) -> Option<u64> {
    let needle = format!("\"{key}\": ");
    let start = object.find(&needle)? + needle.len();
    let rest = &object[start..];
    let end = rest.find(|c: char| !c.is_ascii_digit())?;
    rest[..end].parse().ok()
}

/// Read a boolean field from an object slice.
fn json_bool(object: &str, key: &str) -> Option<bool> {
    let needle = format!("\"{key}\": ");
    let start = object.find(&needle)? + needle.len();
    let rest = &object[start..];
    if rest.starts_with("true") {
        Some(true)
    } else if rest.starts_with("false") {
        Some(false)
    } else {
        None
    }
}

#[test]
fn real_images_match_the_recorded_offsets() {
    let Ok(spec) = std::env::var("CHEESE_TEST_IMAGES") else {
        eprintln!("跳过：未设置 CHEESE_TEST_IMAGES");
        return;
    };
    // Cargo exposes the freshly built binary path through this env var, so the
    // test always exercises the artifact of this very build.
    let binary = PathBuf::from(env!("CARGO_BIN_EXE_cheese-extract"));
    let cases = parse_cases(&spec);
    assert!(!cases.is_empty(), "CHEESE_TEST_IMAGES 里没有可解析的条目");

    let mut failures: Vec<String> = Vec::new();
    for (image, expected_init, expected_swapper) in cases {
        if !image.is_file() {
            failures.push(format!("{}: 文件不存在", image.display()));
            continue;
        }
        let (code, stdout, stderr) = extract_json(&binary, &image);
        if code != 0 {
            failures.push(format!("{}: 退出码 {code} ({})", image.display(), stderr.trim()));
            continue;
        }
        let Some(offsets) = json_object(&stdout, "offsets") else {
            failures.push(format!("{}: 报告里没有 offsets 段", image.display()));
            continue;
        };
        let init = json_number(offsets, "init_task");
        let swapper = json_number(offsets, "swapper_pg_dir");
        let verified = json_object(&stdout, "verified").and_then(|v| json_bool(v, "all"));
        let mut notes = Vec::new();
        if init != Some(expected_init) {
            notes.push(format!(
                "init_task={init:?}（期望 0x{expected_init:x}）"
            ));
        }
        if swapper != Some(expected_swapper) {
            notes.push(format!(
                "swapper_pg_dir={swapper:?}（期望 0x{expected_swapper:x}）"
            ));
        }
        if verified != Some(true) {
            notes.push(format!("结构校验未通过: {verified:?}"));
        }
        if notes.is_empty() {
            eprintln!("✓ {}", image.display());
        } else {
            failures.push(format!("{}: {}", image.display(), notes.join("；")));
        }
    }
    assert!(failures.is_empty(), "回归失败:\n{}", failures.join("\n"));
}
