//! cheese-extract: derive the two offsets cheese needs from a kernel image.
//!
//! Usage:
//!   cheese-extract boot.img --format json --out offsets.json
//!   cheese-extract kernel.img --format text
//!
//! Input: boot.img, raw arm64 Image, gzip Image, payload.bin or an OTA ZIP.
//! The embedded kallsyms table is recovered in place (no root, no vmlinux-to-elf).

use std::path::PathBuf;

use clap::Parser;

use cheese_extract::boot::BootImage;
use cheese_extract::error::{ExtractError, Result};
use cheese_extract::kallsyms;
use cheese_extract::kallsyms_finder;
use cheese_extract::payload;
use cheese_extract::report::{self, Report};
use cheese_extract::symbols::{self, Check};

#[derive(Parser, Debug)]
#[command(
    name = "cheese-extract",
    about = "从 boot.img / arm64 Image / payload.bin 里算出 cheese 的 INIT_TASK_OFF / SWAPPER_PG_DIR_OFF",
    after_help = "例:\n  cheese-extract boot.img --format json --out offsets.json\n  cheese-extract kernel.img --format text\n\n镜像内嵌的 kallsyms 表由本工具直接恢复；若手头有 /proc/kallsyms 转储，可用 --kallsyms 指定，结果更稳。"
)]
struct Cli {
    /// boot.img, raw arm64 Image, gzip Image, payload.bin or OTA ZIP
    image: PathBuf,
    /// kallsyms text dump; recovered from the image when omitted
    #[arg(long)]
    kallsyms: Option<PathBuf>,
    /// KERNEL_PHYS_BASE (hex or decimal), echoed into the report
    #[arg(long, value_parser = parse_int)]
    phys: Option<u64>,
    /// Output format: text or json
    #[arg(long, value_parser = ["text", "json"], default_value = "text")]
    format: String,
    /// Write to a file instead of stdout
    #[arg(long)]
    out: Option<PathBuf>,
    /// Work directory for payload/OTA extraction (app-writable on Android)
    #[arg(long)]
    work_dir: Option<PathBuf>,
}

fn parse_int(text: &str) -> Result<u64> {
    let trimmed = text.trim();
    if let Some(hex) = trimmed
        .strip_prefix("0x")
        .or_else(|| trimmed.strip_prefix("0X"))
    {
        u64::from_str_radix(hex, 16)
            .map_err(|_| ExtractError::new(format!("--phys 不是合法数值: {text}")))
    } else {
        trimmed
            .parse::<u64>()
            .map_err(|_| ExtractError::new(format!("--phys 不是合法数值: {text}")))
    }
}

/// Extract the boot partition from a payload.bin / OTA ZIP and return its path.
fn resolve_boot_path(cli: &Cli, work_root: &PathBuf) -> Result<PathBuf> {
    let input = cli.image.to_string_lossy().to_string();
    if !payload::looks_like_payload(&input) {
        return Ok(cli.image.clone());
    }
    let work_dir = work_root.join(format!("cheese-extract-{}", std::process::id()));
    let meta = payload::open_payload_meta(&input)
        .map_err(|err| ExtractError::new(format!("{err:#}")))?;
    let want = payload::analysis_partition_names(&meta)
        .map_err(|err| ExtractError::new(format!("{err:#}")))?;
    eprintln!("info: 正在从 payload 中提取分区: {}", want.join(", "));
    let view = payload::open_payload_for(&input, &work_dir, &want, None)
        .map_err(|err| ExtractError::new(format!("{err:#}")))?;
    let (boot, _, _) = payload::extract_analysis_inputs(&view, &work_dir)
        .map_err(|err| ExtractError::new(format!("{err:#}")))?;
    Ok(boot)
}

/// Position of `_text` inside the image bytes. Usually 0; a few images put it
/// at the header's `text_offset`. Both candidates go through the init_task check.
fn detect_text_delta(kernel: &[u8], init_offset: u64, text_va: u64) -> (Option<Check>, u64) {
    let header_delta = kernel
        .get(0x8..0x10)
        .map(|bytes| u64::from_le_bytes(bytes.try_into().unwrap()))
        .filter(|delta| *delta > 0 && *delta < kernel.len() as u64)
        .unwrap_or(0);
    let mut candidates = vec![0u64];
    if header_delta != 0 {
        candidates.push(header_delta);
    }
    let mut last = None;
    for delta in candidates {
        let Some(probe) = init_offset.checked_sub(delta) else {
            continue;
        };
        let check = symbols::verify_init_task(kernel, probe, text_va.wrapping_sub(delta));
        if check.ok {
            return (Some(check), delta);
        }
        last = Some(check);
    }
    (last, 0)
}

fn run(cli: &Cli) -> Result<i32> {
    let work_root = cli.work_dir.clone().unwrap_or_else(std::env::temp_dir);
    let boot_path = resolve_boot_path(cli, &work_root)?;
    let boot = BootImage::load(&boot_path)?;

    let kallsyms = match &cli.kallsyms {
        Some(path) => {
            let text = std::fs::read_to_string(path)?;
            kallsyms::parse(&text)?
        }
        None => kallsyms_finder::recover(&boot.kernel, None)
            .map_err(|err| ExtractError::kallsyms(err.to_string()))?,
    };
    let base = symbols::text_base(&kallsyms.symbols)
        .ok_or_else(|| ExtractError::new("kallsyms 里没有 _text/_stext/_head，无法确定链接基址"))?;

    let offsets = symbols::resolve(&kallsyms.symbols, base);
    let init_task = offsets
        .iter()
        .find(|offset| offset.key == "init_task")
        .and_then(|offset| offset.value);
    let swapper = offsets
        .iter()
        .find(|offset| offset.key == "swapper_pg_dir")
        .and_then(|offset| offset.value);
    let idmap = offsets
        .iter()
        .find(|offset| offset.key == "idmap_pg_dir")
        .and_then(|offset| offset.value);

    let (init_check, text_delta) = match init_task {
        Some(offset) => detect_text_delta(&boot.kernel, offset, base),
        None => (None, 0),
    };
    if text_delta != 0 {
        eprintln!("info: _text 位于镜像 0x{text_delta:x} 处");
    }
    let swapper_check = swapper.map(|offset| symbols::verify_swapper(&boot.kernel, offset, idmap));

    let release = boot.release();
    let report = Report {
        release: release.as_deref(),
        text_base: base,
        kernel_phys_load: cli.phys,
        offsets: &offsets,
        init_task_check: init_check.as_ref(),
        swapper_check: swapper_check.as_ref(),
    };

    if let Some(release) = &report.release {
        eprintln!("info: 镜像内核版本 {release}");
    }
    if !report.required_ok() {
        eprintln!("error: 镜像里缺少必需符号: {}", report.missing().join(", "));
    } else if !report.verified() {
        eprintln!("warning: 结构校验未全部通过，偏移可能不可用（详见报告）");
    }

    let output = if cli.format == "json" {
        report::render_json(&report)
    } else {
        report::render_text(&report)
    };
    match &cli.out {
        Some(path) => std::fs::write(path, output)?,
        None => print!("{output}"),
    }

    if report.required_ok() {
        Ok(0)
    } else {
        Err(ExtractError::unsupported(report.missing().join(", ")))
    }
}

fn main() {
    let cli = Cli::parse();
    match run(&cli) {
        Ok(code) => std::process::exit(code),
        Err(err) => {
            eprintln!("error: {err}");
            // Exit codes let the app tell failure classes apart:
            // 2 image parse failure, 4 required offsets missing, 5 kallsyms recovery failure
            let code = match &err {
                ExtractError::Unsupported(_) => 4,
                ExtractError::Kallsyms(_) => 5,
                _ => 2,
            };
            std::process::exit(code);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn phys_accepts_hex_and_decimal() {
        assert_eq!(parse_int("0xa8000000").unwrap(), 0xa800_0000);
        assert_eq!(parse_int("  2818572288 ").unwrap(), 0xa800_0000);
        assert!(parse_int("0xzz").is_err());
    }
}
