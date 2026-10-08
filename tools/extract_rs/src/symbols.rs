//! Symbol resolution and structure checks for cheese.
//!
//! Same semantics as `calc_offsets.py`: **symbol link address minus `_text` link address**,
//! i.e. the in-image offset; cheese adds `KERNEL_PHYS_BASE` to get a physical address.

use std::collections::{BTreeMap, BTreeSet};

use crate::kallsyms::unique;

/// Symbol name -> report key. `_text` is the base, so its offset is always 0.
pub const SYMBOLS: &[(&str, &str)] = &[
    ("init_task", "init_task"),
    ("swapper_pg_dir", "swapper_pg_dir"),
    ("idmap_pg_dir", "idmap_pg_dir"),
];

/// `task_struct` field offsets for the 5.15 KMI. Structure check only - they never
/// reach the reported offsets: a different layout fails the check, nothing more.
pub const OFF_TASKS: usize = 0x4D0;
pub const OFF_MM: usize = 0x520;
pub const OFF_PID: usize = 0x5D8;
pub const OFF_COMM: usize = 0x7A8;
pub const SIZEOF_COMM: usize = 16;

/// `swapper_pg_dir` and `idmap_pg_dir` share one page-table array, 0x5000 apart.
pub const SWAPPER_IDMAP_DELTA: u64 = 0x5000;

/// One resolved symbol: `value` is the in-image offset, `None` when absent.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Offset {
    pub key: &'static str,
    pub symbol: &'static str,
    pub value: Option<u64>,
}

/// Convert absolute addresses into offsets relative to `_text`.
pub fn resolve(symbols: &BTreeMap<String, BTreeSet<u64>>, base: u64) -> Vec<Offset> {
    SYMBOLS
        .iter()
        .map(|(symbol, key)| Offset {
            key,
            symbol,
            value: unique(symbols, symbol).and_then(|value| value.checked_sub(base)),
        })
        .collect()
}

/// Pick the link base from `_text`/`_stext`/`_head`, as upstream does.
pub fn text_base(symbols: &BTreeMap<String, BTreeSet<u64>>) -> Option<u64> {
    unique(symbols, "_text")
        .or_else(|| unique(symbols, "_stext"))
        .or_else(|| unique(symbols, "_head"))
}

/// Result of one check.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Check {
    pub ok: bool,
    pub detail: String,
}

impl Check {
    fn new(ok: bool, detail: impl Into<String>) -> Self {
        Check {
            ok,
            detail: detail.into(),
        }
    }
}

fn read_i32(kernel: &[u8], at: usize) -> Option<i32> {
    kernel
        .get(at..at + 4)
        .map(|bytes| i32::from_le_bytes(bytes.try_into().unwrap()))
}

fn read_u64(kernel: &[u8], at: usize) -> Option<u64> {
    kernel
        .get(at..at + 8)
        .map(|bytes| u64::from_le_bytes(bytes.try_into().unwrap()))
}

/// Verify the offset against init_task's shape: pid=0, comm="swapper",
/// self-referencing tasks list and mm=NULL. Any mismatch makes the offset suspect.
pub fn verify_init_task(kernel: &[u8], offset: u64, text_va: u64) -> Check {
    let Ok(start) = usize::try_from(offset) else {
        return Check::new(false, "init_task 偏移超出可寻址范围");
    };
    let Some(end) = start.checked_add(OFF_COMM + SIZEOF_COMM) else {
        return Check::new(false, "init_task 偏移溢出");
    };
    if end > kernel.len() {
        return Check::new(
            false,
            format!("镜像里读不到 init_task+0x{OFF_COMM:x}（镜像太短？）"),
        );
    }
    let pid = read_i32(kernel, start + OFF_PID).unwrap_or(-1);
    let comm = &kernel[start + OFF_COMM..start + OFF_COMM + 8];
    let tasks_next = read_u64(kernel, start + OFF_TASKS).unwrap_or(0);
    let tasks_prev = read_u64(kernel, start + OFF_TASKS + 8).unwrap_or(0);
    let mm = read_u64(kernel, start + OFF_MM).unwrap_or(0);
    let tasks_va = text_va.wrapping_add(offset).wrapping_add(OFF_TASKS as u64);
    let pid_ok = pid == 0;
    let comm_ok = comm.starts_with(b"swapper");
    let tasks_ok = tasks_next == tasks_va && tasks_prev == tasks_va;
    let mm_ok = mm == 0;
    Check::new(
        pid_ok && comm_ok && tasks_ok && mm_ok,
        format!(
            "pid={pid}{} comm={:?}{} tasks.next/prev={tasks_next:#x}/{tasks_prev:#x}（应为 {tasks_va:#x}）{} mm={mm:#x}{}",
            mark(pid_ok),
            String::from_utf8_lossy(comm),
            mark(comm_ok),
            mark(tasks_ok),
            mark(mm_ok),
        ),
    )
}

/// Check `swapper_pg_dir`: page aligned, inside the image, expected idmap distance.
pub fn verify_swapper(kernel: &[u8], offset: u64, idmap: Option<u64>) -> Check {
    let mut ok = true;
    let mut notes: Vec<String> = Vec::new();
    if offset % 0x1000 != 0 {
        ok = false;
        notes.push(format!("偏移 {offset:#x} 未按 4 KiB 页对齐"));
    }
    match usize::try_from(offset) {
        Ok(start) if start + 0x1000 <= kernel.len() => {}
        _ => {
            ok = false;
            notes.push("偏移落在镜像之外".to_string());
        }
    }
    match idmap {
        Some(idmap) => {
            let delta = offset.wrapping_sub(idmap);
            if delta == SWAPPER_IDMAP_DELTA {
                notes.push("与 idmap_pg_dir 相差 0x5000 ✓".to_string());
            } else {
                notes.push(format!(
                    "与 idmap_pg_dir 相差 {delta:#x}（预期 0x{SWAPPER_IDMAP_DELTA:x}）"
                ));
            }
        }
        None => notes.push("镜像里没有 idmap_pg_dir，跳过间距校验".to_string()),
    }
    Check::new(ok, notes.join("；"))
}

fn mark(ok: bool) -> &'static str {
    if ok { "✓" } else { "✗" }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn kallsyms_with(entries: &[(&str, u64)]) -> BTreeMap<String, BTreeSet<u64>> {
        entries
            .iter()
            .map(|(name, value)| ((*name).to_string(), BTreeSet::from([*value])))
            .collect()
    }

    #[test]
    fn resolve_subtracts_the_text_base() {
        let symbols = kallsyms_with(&[
            ("_text", 0xffff_ffc0_0800_0000),
            ("init_task", 0xffff_ffc0_0af1_2bc0),
            ("swapper_pg_dir", 0xffff_ffc0_0aad_6000),
        ]);
        let offsets = resolve(&symbols, text_base(&symbols).unwrap());
        let get = |key: &str| {
            offsets
                .iter()
                .find(|offset| offset.key == key)
                .unwrap()
                .value
        };
        assert_eq!(get("init_task"), Some(0x2f1_2bc0));
        assert_eq!(get("swapper_pg_dir"), Some(0x2ad_6000));
        assert_eq!(get("idmap_pg_dir"), None);
    }

    /// Synthetic image holding only an init_task structure; every condition must matter.
    #[test]
    fn init_task_check_requires_every_field() {
        let text_va: u64 = 0xffff_ffc0_0800_0000;
        let offset: u64 = 0x1000;
        let mut kernel = vec![0u8; 0x2000];
        let start = offset as usize;
        kernel[start + OFF_COMM..start + OFF_COMM + 7].copy_from_slice(b"swapper");
        let tasks_va = text_va + offset + OFF_TASKS as u64;
        kernel[start + OFF_TASKS..start + OFF_TASKS + 8].copy_from_slice(&tasks_va.to_le_bytes());
        kernel[start + OFF_TASKS + 8..start + OFF_TASKS + 16]
            .copy_from_slice(&tasks_va.to_le_bytes());
        assert!(verify_init_task(&kernel, offset, text_va).ok);

        // pid != 0 must fail
        kernel[start + OFF_PID..start + OFF_PID + 4].copy_from_slice(&1i32.to_le_bytes());
        assert!(!verify_init_task(&kernel, offset, text_va).ok);

        // a wrong comm must fail
        kernel[start + OFF_PID..start + OFF_PID + 4].copy_from_slice(&0i32.to_le_bytes());
        kernel[start + OFF_COMM] = b'x';
        assert!(!verify_init_task(&kernel, offset, text_va).ok);

        // a non-zero mm must fail
        kernel[start + OFF_COMM] = b's';
        kernel[start + OFF_MM..start + OFF_MM + 8].copy_from_slice(&8u64.to_le_bytes());
        assert!(!verify_init_task(&kernel, offset, text_va).ok);

        // out of bounds must fail
        assert!(!verify_init_task(&kernel, kernel.len() as u64, text_va).ok);
    }

    #[test]
    fn swapper_check_uses_alignment_and_idmap_delta() {
        let kernel = vec![0u8; 0x8000];
        // swapper_pg_dir sits 0x5000 above idmap_pg_dir, as in real kernels
        assert!(verify_swapper(&kernel, 0x7000, Some(0x2000)).ok);
        // not page aligned
        assert!(!verify_swapper(&kernel, 0x2001, None).ok);
        // outside the image
        assert!(!verify_swapper(&kernel, 0x9000, None).ok);
        // a wrong idmap distance is a note, not a failure
        let check = verify_swapper(&kernel, 0x7000, Some(0x1000));
        assert!(check.ok);
        assert!(check.detail.contains("预期"));
    }
}
