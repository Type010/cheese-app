# Cheese

一键提权 App：跑 cheese（CVE-2025-21479，Adreno GPU）拿到临时 root shell，
再由 `ROOT_CMD` 用 KernelSU 管理器的 `libksud.so late-load` 激活 KernelSU。

## 它做什么

```
配置来源（四选一）：解析本地 boot.img / payload.bin、给一个全量包链接（只下 boot 分区）、
                    导入 offsets.json、从云端共享仓库取一份
  → 设备内解析：定位 arm64 Image → 恢复内嵌 kallsyms → 算出
      INIT_TASK_OFF / SWAPPER_PG_DIR_OFF
  → 结构校验：init_task 的 pid/comm/tasks/mm 特征 + swapper_pg_dir 页对齐与 idmap 间距
  → 内核核对：镜像的 release 必须等于本机 `uname -r`，否则不生成配置
  → 运行 cheese：两个偏移作为环境变量传入，提权成功后由 ROOT_CMD 加载 KernelSU
```

默认以 **app 身份**直接执行 cheese。应用进程继承的 seccomp 过滤器会拦掉触发用的
`vhangup(2)` 和 ksud late-load 用的 `finit_module(2)`，所以 cheese 拿到内核写能力后
会先清掉自己 `task_struct` 上的 `TIF_SECCOMP` 与 `seccomp.mode`。主页的
「通过 Shizuku 执行」开关改为以 shell 身份运行（`Seccomp: 0`，跳过这一步，需要
Stellar 就绪）。

`ROOT_CMD` 默认是：

```sh
SO=$(find /data/app -type f -name libksud.so 2>/dev/null | head -1); \
[ -z $SO ] && { echo NO_LIBKSUD; exit 1; }; \
echo INJECT_SO=$SO; \
$SO late-load --kmi android13-5.15 && echo KSU_INSMOD_OK || echo KSU_INSMOD_FAIL
```

## 目录

```
app/                         Android 应用（Kotlin + Compose + Miuix）
  src/main/kotlin/com/cheese/app/
    ui/                      CheeseUI（主页 + 日志面板 + 对话框）、OffsetsScreen、
                             AdvancedScreen、AboutScreen、CheeseViewModel
    data/                    CheeseRepository（跑两个原生程序）、OffsetStore
    domain/                  偏移模型、默认 ROOT_CMD、日志判定
  src/main/jniLibs/          libcheese.so（cheese）、libextract.so（偏移提取器）
tools/extract_rs/            Rust 偏移提取器（kallsyms 恢复 + 结构校验）
```

## 许可与致谢

Apache License 2.0（见 [LICENSE](LICENSE)）。

- UI/构建/提取器骨架参考 [YuKongA/ghostlock-app](https://github.com/YuKongA/ghostlock-app)
- ghostlock-app 又基于 NebuSec/CyberMeowfia、JoinChang/ghostlock-oneplus、x-spy/CVE-2026-43499-popsicle
