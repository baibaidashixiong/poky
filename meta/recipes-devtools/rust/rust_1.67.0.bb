SUMMARY = "Rust compiler and runtime libaries"
HOMEPAGE = "http://www.rust-lang.org"
SECTION = "devel"
LICENSE = "(MIT | Apache-2.0) & Unicode-TOU"
LIC_FILES_CHKSUM = "file://COPYRIGHT;md5=c2cccf560306876da3913d79062a54b9"

inherit rust
inherit cargo_common

DEPENDS += "file-native python3-native"
DEPENDS:append:class-native = " rust-llvm-native"
DEPENDS:append:class-nativesdk = " nativesdk-rust-llvm"

DEPENDS += "rust-llvm (=${PV})"

# Otherwise we'll depend on what we provide
INHIBIT_DEFAULT_RUST_DEPS:class-native = "1"
# We don't need to depend on gcc-native because yocto assumes it exists
PROVIDES:class-native = "virtual/${TARGET_PREFIX}rust"

S = "${RUSTSRC}"

# Use at your own risk, accepted values are stable, beta and nightly
RUST_CHANNEL ?= "stable"
PV .= "${@bb.utils.contains('RUST_CHANNEL', 'stable', '', '-${RUST_CHANNEL}', d)}"

export FORCE_CRATE_HASH="${BB_TASKHASH}"

RUST_ALTERNATE_EXE_PATH ?= "${STAGING_LIBDIR}/llvm-rust/bin/llvm-config"
RUST_ALTERNATE_EXE_PATH_NATIVE = "${STAGING_LIBDIR_NATIVE}/llvm-rust/bin/llvm-config"

# We don't want to use bitbakes vendoring because the rust sources do their
# own vendoring.
CARGO_DISABLE_BITBAKE_VENDORING = "1"

# We can't use RUST_BUILD_SYS here because that may be "musl" if
# TCLIBC="musl". Snapshots are always -unknown-linux-gnu
setup_cargo_environment () {
    # The first step is to build bootstrap and some early stage tools,
    # these are build for the same target as the snapshot, e.g.
    # x86_64-unknown-linux-gnu.
    # Later stages are build for the native target (i.e. target.x86_64-linux)
    cargo_common_do_configure
}

inherit rust-target-config

do_rust_setup_snapshot () {
    for installer in "${WORKDIR}/rust-snapshot-components/"*"/install.sh"; do
        "${installer}" --prefix="${WORKDIR}/rust-snapshot" --disable-ldconfig
    done

    # Some versions of rust (e.g. 1.18.0) tries to find cargo in stage0/bin/cargo
    # and fail without it there.
    mkdir -p ${RUSTSRC}/build/${BUILD_SYS}
    ln -sf ${WORKDIR}/rust-snapshot/ ${RUSTSRC}/build/${BUILD_SYS}/stage0

    # Need to use uninative's loader if enabled/present since the library paths
    # are used internally by rust and result in symbol mismatches if we don't
    if [ ! -z "${UNINATIVE_LOADER}" -a -e "${UNINATIVE_LOADER}" ]; then
        for bin in cargo rustc rustdoc; do
            patchelf-uninative ${WORKDIR}/rust-snapshot/bin/$bin --set-interpreter ${UNINATIVE_LOADER}
        done
    fi
}
addtask rust_setup_snapshot after do_unpack before do_configure
do_rust_setup_snapshot[dirs] += "${WORKDIR}/rust-snapshot"
do_rust_setup_snapshot[vardepsexclude] += "UNINATIVE_LOADER"

python do_configure() {
    import json
    try:
        import configparser
    except ImportError:
        import ConfigParser as configparser

    # toml is rather similar to standard ini like format except it likes values
    # that look more JSON like. So for our purposes simply escaping all values
    # as JSON seem to work fine.

    e = lambda s: json.dumps(s)

    config = configparser.RawConfigParser()

    # [target.ARCH-poky-linux]
    host_section = "target.{}".format(d.getVar('RUST_HOST_SYS', True))
    config.add_section(host_section)

    llvm_config_target = d.expand("${RUST_ALTERNATE_EXE_PATH}")
    llvm_config_build = d.expand("${RUST_ALTERNATE_EXE_PATH_NATIVE}")
    config.set(host_section, "llvm-config", e(llvm_config_target))

    config.set(host_section, "cxx", e(d.expand("${RUST_TARGET_CXX}")))
    config.set(host_section, "cc", e(d.expand("${RUST_TARGET_CC}")))
    config.set(host_section, "linker", e(d.expand("${RUST_TARGET_CCLD}")))
    if "musl" in host_section:
        config.set(host_section, "musl-root", e(d.expand("${STAGING_DIR_HOST}${exec_prefix}")))

    # If we don't do this rust-native will compile it's own llvm for BUILD.
    # [target.${BUILD_ARCH}-unknown-linux-gnu]
    build_section = "target.{}".format(d.getVar('RUST_BUILD_SYS', True))
    if build_section != host_section:
        config.add_section(build_section)

        config.set(build_section, "llvm-config", e(llvm_config_build))

        config.set(build_section, "cxx", e(d.expand("${RUST_BUILD_CXX}")))
        config.set(build_section, "cc", e(d.expand("${RUST_BUILD_CC}")))
        config.set(build_section, "linker", e(d.expand("${RUST_BUILD_CCLD}")))

    target_section = "target.{}".format(d.getVar('RUST_TARGET_SYS', True))
    if target_section != host_section and target_section != build_section:
        config.add_section(target_section)

        config.set(target_section, "llvm-config", e(llvm_config_target))

        config.set(target_section, "cxx", e(d.expand("${RUST_TARGET_CXX}")))
        config.set(target_section, "cc", e(d.expand("${RUST_TARGET_CC}")))
        config.set(target_section, "linker", e(d.expand("${RUST_TARGET_CCLD}")))

    # [llvm]
    config.add_section("llvm")
    config.set("llvm", "static-libstdcpp", e(False))
    if "llvm" in (d.getVar('TC_CXX_RUNTIME') or ""):
        config.set("llvm", "use-libcxx", e(True))

    # [rust]
    config.add_section("rust")
    config.set("rust", "rpath", e(True))
    config.set("rust", "channel", e(d.expand("${RUST_CHANNEL}")))

    # Whether or not to optimize the compiler and standard library
    config.set("rust", "optimize", e(True))

    # Emits extraneous output from tests to ensure that failures of the test
    # harness are debuggable just from logfiles
    config.set("rust", "verbose-tests", e(True))

    # [build]
    config.add_section("build")
    config.set("build", "submodules", e(False))
    config.set("build", "docs", e(False))

    rustc = d.expand("${WORKDIR}/rust-snapshot/bin/rustc")
    config.set("build", "rustc", e(rustc))

    # Support for the profiler runtime to generate e.g. coverage report,
    # PGO etc.
    config.set("build", "profiler", e(True))

    cargo = d.expand("${WORKDIR}/rust-snapshot/bin/cargo")
    config.set("build", "cargo", e(cargo))

    config.set("build", "vendor", e(True))

    if not "targets" in locals():
        targets = [d.getVar("RUST_TARGET_SYS", True)]
    config.set("build", "target", e(targets))

    if not "hosts" in locals():
        hosts = [d.getVar("RUST_HOST_SYS", True)]
    config.set("build", "host", e(hosts))

    # We can't use BUILD_SYS since that is something the rust snapshot knows
    # nothing about when trying to build some stage0 tools (like fabricate)
    config.set("build", "build", e(d.getVar("RUST_BUILD_SYS", True)))

    # [install]
    config.add_section("install")
    # ./x.py install doesn't have any notion of "destdir"
    # but we can prepend ${D} to all the directories instead
    config.set("install", "prefix",  e(d.getVar("D", True) + d.getVar("prefix", True)))
    config.set("install", "bindir",  e(d.getVar("D", True) + d.getVar("bindir", True)))
    config.set("install", "libdir",  e(d.getVar("D", True) + d.getVar("libdir", True)))
    config.set("install", "datadir", e(d.getVar("D", True) + d.getVar("datadir", True)))
    config.set("install", "mandir",  e(d.getVar("D", True) + d.getVar("mandir", True)))

    with open("config.toml", "w") as f:
        f.write('changelog-seen = 2\n\n')
        config.write(f)

    # set up ${WORKDIR}/cargo_home
    bb.build.exec_func("setup_cargo_environment", d)
}

rust_runx () {
    echo "COMPILE ${PN}" "$@"

    # CFLAGS, LDFLAGS, CXXFLAGS, CPPFLAGS are used by rust's build for a
    # wide range of targets (not just TARGET). Yocto's settings for them will
    # be inappropriate, avoid using.
    unset CFLAGS
    unset LDFLAGS
    unset CXXFLAGS
    unset CPPFLAGS

    export RUSTFLAGS="${RUST_DEBUG_REMAP}"

    # Copy the natively built llvm-config into the target so we can run it. Horrible,
    # but works!
    if [ ${RUST_ALTERNATE_EXE_PATH_NATIVE} != ${RUST_ALTERNATE_EXE_PATH} ]; then
        mkdir -p `dirname ${RUST_ALTERNATE_EXE_PATH}`
        cp ${RUST_ALTERNATE_EXE_PATH_NATIVE} ${RUST_ALTERNATE_EXE_PATH}
        chrpath -d ${RUST_ALTERNATE_EXE_PATH}
    fi

    oe_cargo_fix_env

    python3 src/bootstrap/bootstrap.py ${@oe.utils.parallel_make_argument(d, '-j %d')} "$@" --verbose
}
rust_runx[vardepsexclude] += "PARALLEL_MAKE"

require rust-source.inc
require rust-snapshot.inc

INSANE_SKIP:${PN}:class-native = "already-stripped"
FILES:${PN} += "${libdir}/rustlib"
FILES:${PN} += "${libdir}/*.so"
FILES:${PN}-dev = ""

do_compile () {
    rust_runx build --stage 2
}

do_compile:append:class-target () {
    rust_runx build --stage 2 src/tools/clippy
    rust_runx build --stage 2 src/tools/rustfmt
}

do_compile:append:class-nativesdk () {
    rust_runx build --stage 2 src/tools/clippy
    rust_runx build --stage 2 src/tools/rustfmt
}

ALLOW_EMPTY:${PN} = "1"

PACKAGES =+ "${PN}-tools-clippy ${PN}-tools-rustfmt"
FILES:${PN}-tools-clippy = "${bindir}/cargo-clippy ${bindir}/clippy-driver"
FILES:${PN}-tools-rustfmt = "${bindir}/rustfmt"
RDEPENDS:${PN}-tools-clippy = "${PN}"
RDEPENDS:${PN}-tools-rustfmt = "${PN}"

SUMMARY:${PN}-tools-clippy = "A collection of lints to catch common mistakes and improve your Rust code"
SUMMARY:${PN}-tools-rustfmt = "A tool for formatting Rust code according to style guidelines"

do_install () {
    rust_do_install
}

rust_do_install() {
    rust_runx install
}

rust_do_install:class-nativesdk() {
    export PSEUDO_UNLOAD=1
    rust_runx install
    unset PSEUDO_UNLOAD

    install -d ${D}${bindir}
    for i in cargo-clippy clippy-driver rustfmt; do
        cp build/${RUST_BUILD_SYS}/stage2-tools/${RUST_HOST_SYS}/release/$i ${D}${bindir}
        chrpath -r "\$ORIGIN/../lib" ${D}${bindir}/$i
    done

    chown root:root ${D}/ -R
    rm ${D}${libdir}/rustlib/uninstall.sh
    rm ${D}${libdir}/rustlib/install.log
    rm ${D}${libdir}/rustlib/manifest*
}

EXTRA_TOOLS ?= "cargo-clippy clippy-driver rustfmt"
rust_do_install:class-target() {
    export PSEUDO_UNLOAD=1
    rust_runx install
    unset PSEUDO_UNLOAD

    install -d ${D}${bindir}
    for i in ${EXTRA_TOOLS}; do
        cp build/${RUST_BUILD_SYS}/stage2-tools/${RUST_HOST_SYS}/release/$i ${D}${bindir}
        chrpath -r "\$ORIGIN/../lib" ${D}${bindir}/$i
    done

    install -d ${D}${libdir}/rustlib/${RUST_HOST_SYS}
    install -m 0644 ${WORKDIR}/rust-targets/${RUST_HOST_SYS}.json ${D}${libdir}/rustlib/${RUST_HOST_SYS}/target.json

    chown root:root ${D}/ -R
    rm ${D}${libdir}/rustlib/uninstall.sh
    rm ${D}${libdir}/rustlib/install.log
    rm ${D}${libdir}/rustlib/manifest*
}

RUSTLIB_DEP:class-nativesdk = ""

# musl builds include libunwind.a
INSANE_SKIP:${PN} = "staticdev"

BBCLASSEXTEND = "native nativesdk"
