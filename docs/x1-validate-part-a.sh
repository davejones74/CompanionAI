#!/usr/bin/env bash
# X1 Pro / FastFlowLM validation - Part A (read-only).
#
# Implements Blocks 1, 2, 3 and 4.1 of docs/X1Pro-FastFlowLM-Validation.md.
#
# SAFETY: makes no configuration changes, downloads no models, starts no services.
#         Safe to run repeatedly.
#
# USAGE:  bash docs/x1-validate-part-a.sh 2>&1 | tee x1-a.log
#
# WHY PART A ONLY: Blocks 4-8 need a model tag and a port that this script *discovers*
# rather than assumes. Part B embeds those values and is issued after Part A is read.

cap() {
  local label="$1"; shift
  echo "### $label"
  echo "\$ $*"
  "$@"
  local rc=$?
  echo "--- exit: $rc ---"
  echo
}

echo "############################################################"
echo "# BLOCK 1 - hardware, kernel, driver stack"
echo "############################################################"
cap "uname -a"            uname -a
cap "uname -r"            uname -r
cap "os-release"          bash -lc 'cat /etc/os-release'
cap "lspci NPU lines"     bash -lc "lspci | grep -Ei 'npu|signal processing|xilinx|amd' || echo 'NO MATCHING DEVICE'"
cap "/dev/accel contents" bash -lc 'ls -la /dev/accel/ 2>&1'
cap "amdxdna loaded"      bash -lc "lsmod | grep amdxdna || echo 'amdxdna NOT LOADED'"
cap "modinfo filename"    bash -lc 'modinfo -F filename amdxdna 2>&1'
cap "modinfo version"     bash -lc "modinfo amdxdna 2>&1 | grep -E '^(filename|version|srcversion|vermagic)'"
cap "ulimit -l"           bash -lc 'ulimit -l'
cap "free -h"             free -h
cap "nproc"               nproc

echo "############################################################"
echo "# BLOCK 2 - installed component versions"
echo "############################################################"
cap "flm --version"       bash -lc 'flm --version 2>&1 || echo "flm NOT FOUND"'
cap "which flm"           bash -lc 'which flm 2>&1 || echo "flm NOT ON PATH"'
cap "installed packages"  bash -lc "dpkg -l 2>/dev/null | grep -Ei 'fastflowlm|amdxdna|xrt|lemonade' || echo 'no matching packages'"
cap "dkms status"         bash -lc 'dkms status 2>&1 || echo "dkms not installed"'
cap "NPU firmware"        bash -lc 'ls -la /lib/firmware/amdxdna/ 2>/dev/null || echo "no /lib/firmware/amdxdna"'

echo "############################################################"
echo "# BLOCK 1.1 - DISCOVER THE PORT (do not assume 52625)"
echo "# A bind-address flag here decides the whole firewall plan."
echo "############################################################"
cap "flm port"            bash -lc 'flm port 2>&1 || echo "flm port unsupported"'
cap "flm serve --help"    bash -lc 'flm serve --help 2>&1 || echo "unavailable"'
cap "flm --help"          bash -lc 'flm --help 2>&1 || echo "unavailable"'

echo "############################################################"
echo "# BLOCK 3 - the two decisive checks"
echo "# Gate: both must succeed and the NPU must be listed."
echo "############################################################"
cap "flm validate"        bash -lc 'flm validate 2>&1; echo "exit=$?"'
cap "xrt-smi examine"     bash -lc 'xrt-smi examine 2>&1; echo "exit=$?"'

echo "############################################################"
echo "# BLOCK 4.1 - model catalogue discovery (no downloads)"
echo "############################################################"
cap "flm list"            bash -lc 'flm list 2>&1 || true'
cap "flm cache list"      bash -lc 'flm cache list 2>&1 || true'
cap "flm pull --help"     bash -lc 'flm pull --help 2>&1 || true'

echo "############################################################"
echo "# END PART A. Nothing above was configured, downloaded or started."
echo "# Report the output; Part B (Blocks 4-8) follows."
echo "############################################################"