//! Report rendering: JSON for the app, text for humans.

use serde_json::{Map, Value, json};

use crate::symbols::{Check, Offset};

/// Everything the report needs.
pub struct Report<'a> {
    pub release: Option<&'a str>,
    /// `_text` link address from kallsyms
    pub text_base: u64,
    /// KERNEL_PHYS_BASE passed via `--phys` (None when omitted)
    pub kernel_phys_load: Option<u64>,
    pub offsets: &'a [Offset],
    pub init_task_check: Option<&'a Check>,
    pub swapper_check: Option<&'a Check>,
}

impl Report<'_> {
    /// Success requires both offsets to be resolved.
    pub fn required_ok(&self) -> bool {
        let get = |key: &str| {
            self.offsets
                .iter()
                .find(|offset| offset.key == key)
                .and_then(|offset| offset.value)
        };
        get("init_task").is_some() && get("swapper_pg_dir").is_some()
    }

    /// Both structure checks passed.
    pub fn verified(&self) -> bool {
        match (self.init_task_check, self.swapper_check) {
            (Some(init), Some(swapper)) => init.ok && swapper.ok,
            _ => false,
        }
    }

    pub fn missing(&self) -> Vec<String> {
        self.offsets
            .iter()
            .filter(|offset| offset.value.is_none())
            .map(|offset| offset.symbol.to_string())
            .collect()
    }
}

fn number(value: u64) -> Value {
    json!(value)
}

/// Stable JSON shape; consumers parse exactly this.
pub fn render_json(report: &Report<'_>) -> String {
    let mut offsets = Map::new();
    let mut offsets_hex = Map::new();
    for offset in report.offsets {
        offsets.insert(
            offset.key.to_string(),
            match offset.value {
                Some(value) => number(value),
                None => Value::Null,
            },
        );
        offsets_hex.insert(
            offset.key.to_string(),
            match offset.value {
                Some(value) => json!(format!("{value:#x}")),
                None => Value::Null,
            },
        );
    }
    let mut checks = Map::new();
    let mut verified = Map::new();
    for (key, check) in [
        ("init_task", report.init_task_check),
        ("swapper_pg_dir", report.swapper_check),
    ] {
        match check {
            Some(check) => {
                checks.insert(key.to_string(), json!(check.detail));
                verified.insert(key.to_string(), json!(check.ok));
            }
            None => {
                checks.insert(key.to_string(), Value::Null);
                verified.insert(key.to_string(), json!(false));
            }
        }
    }
    verified.insert("all".to_string(), json!(report.verified()));

    let document = json!({
        "tool": "cheese-extract",
        "release": report.release,
        // Link addresses above i64 are hex strings to avoid JSON number precision loss
        "kimage_text_base": format!("{:#x}", report.text_base),
        "kernel_phys_load": report.kernel_phys_load,
        "offsets": offsets,
        "offsets_hex": offsets_hex,
        "verified": verified,
        "checks": checks,
        "missing": report.missing(),
    });
    serde_json::to_string_pretty(&document).unwrap_or_else(|_| "{}".to_string()) + "\n"
}

/// Human-readable output for the CLI.
pub fn render_text(report: &Report<'_>) -> String {
    use std::fmt::Write;

    let mut out = String::new();
    let _ = writeln!(
        out,
        "release            = {}",
        report.release.unwrap_or("(未知)")
    );
    let _ = writeln!(out, "_text              = {:#x}", report.text_base);
    if let Some(phys) = report.kernel_phys_load {
        let _ = writeln!(out, "KERNEL_BASE        = {phys:#x}");
    }
    for offset in report.offsets {
        match offset.value {
            Some(value) => {
                let _ = writeln!(out, "{:<18} = {value:#x}", offset.key);
            }
            None => {
                let _ = writeln!(out, "{:<18} = (缺失：{})", offset.key, offset.symbol);
            }
        }
    }
    for (key, check) in [
        ("init_task", report.init_task_check),
        ("swapper_pg_dir", report.swapper_check),
    ] {
        match check {
            Some(check) => {
                let _ = writeln!(
                    out,
                    "校验 {key}: {}{}",
                    if check.ok { "通过 " } else { "失败 " },
                    check.detail
                );
            }
            None => {
                let _ = writeln!(out, "校验 {key}: 无法进行（偏移缺失）");
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn offset(key: &'static str, value: Option<u64>) -> Offset {
        Offset {
            key,
            symbol: key,
            value,
        }
    }

    #[test]
    fn json_carries_numbers_and_hex_strings() {
        let offsets = vec![
            offset("init_task", Some(0x2f1_2bc0)),
            offset("swapper_pg_dir", Some(0x2ad_6000)),
        ];
        let report = Report {
            release: Some("5.15.178-test"),
            text_base: 0xffff_ffc0_0800_0000,
            kernel_phys_load: Some(0xa800_0000),
            offsets: &offsets,
            init_task_check: None,
            swapper_check: None,
        };
        let json = render_json(&report);
        let value: Value = serde_json::from_str(&json).expect("valid json");
        assert_eq!(value["offsets"]["init_task"], json!(0x2f1_2bc0u64));
        assert_eq!(value["offsets_hex"]["init_task"], json!("0x2f12bc0"));
        assert_eq!(
            value["kimage_text_base"],
            json!("0xffffffc008000000"),
            "超长地址必须是字符串"
        );
        assert_eq!(value["verified"]["all"], json!(false));
        assert_eq!(value["missing"], json!([]));
        assert!(report.required_ok());
        assert!(render_text(&report).contains("init_task"));
    }

    #[test]
    fn missing_offsets_are_reported() {
        let offsets = vec![
            offset("init_task", Some(0x1000)),
            offset("swapper_pg_dir", None),
        ];
        let report = Report {
            release: None,
            text_base: 0xffff_ffc0_0800_0000,
            kernel_phys_load: None,
            offsets: &offsets,
            init_task_check: None,
            swapper_check: None,
        };
        let json = render_json(&report);
        let value: Value = serde_json::from_str(&json).expect("valid json");
        assert_eq!(value["offsets"]["swapper_pg_dir"], Value::Null);
        assert_eq!(value["missing"], json!(["swapper_pg_dir"]));
        assert!(!report.required_ok());
    }
}
