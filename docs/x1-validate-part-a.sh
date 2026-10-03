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
#
# EXIT CODES ARE TRUTHFUL: every check runs through caps(), which reports the real status
# of the command. 127 means "binary not installed" - that is data, not a script failure.

# Run a shell command string and report its true exit status.
caps() {
  local label="$1"
  local cmd="$2"
  echo "### $label"
  echo "\$ $cmd"
  bash -lc "$cmd" 2>&1
  local rc=$?
  echo "--- exit: $rc ---"
  echo
}

# Report installed / not installed without masking the probe status.
have() {
  local label="$1"
  local bin="$2"
  echo "### $label"
  if command -v "$bin" >/dev/null 2>&1; then
    echo "STATUS: INSTALLED ($("$bin" --version 2>&1 | head -1))"
  else
    echo "STATUS: NOT INSTALLED ($bin not on PATH)"
  fi
  echo
}

echo "############################################################"
echo "# BLOCK 1 - hardware, kernel, driver stack"
echo "############################################################"
caps "uname -a"                 'uname -a'
caps "uname -r"                 'uname -r'
caps "os-release"               'cat /etc/os-release'
caps "lspci NPU lines"          "lspci | grep -Ei 'npu|signal processing|xilinx|amd' || echo 'NO MATCHING DEVICE'"
caps "NPU PCI identity"         'd=0000:c6:00.1; for f in vendor device subsystem_vendor subsystem_device revision class modalias; do printf "%s=%s\n" "$f" "$(cat /sys/bus/pci/devices/$d/$f 2>/dev/null)"; done'
caps "/dev/accel contents"      'ls -la /dev/accel/ 2>&1'
caps "amdxdna loaded"           "lsmod | grep -E 'amdxdna|amd_pmf|gpu_sched' || echo 'amdxdna NOT LOADED'"
caps "modinfo metadata"         "modinfo amdxdna 2>&1 | grep -E '^(filename|version|srcversion|vermagic)'"
caps "in-tree vs DKMS"          'if dkms status 2>/dev/null | grep -q amdxdna; then echo "DKMS amdxdna present"; else echo "no DKMS amdxdna -> in-tree driver (expected on kernel 7.0+)"; fi'
caps "ulimit -l (memlock)"      'ulimit -l'
caps "memlock verdict"          'v=$(ulimit -l); if [ "$v" = unlimited ]; then echo "OK: memlock unlimited"; else echo "BLOCKER: memlock is $v KB, not unlimited - see validation doc"; fi'
caps "free -h"                  'free -h'
caps "nproc"                    'nproc'

echo "############################################################"
echo "# BLOCK 2 - installed component versions"
echo "############################################################"
have "flm present"              flm
have "xrt-smi present"          xrt-smi
caps "matching packages"        "dpkg -l 2>/dev/null | grep -Ei 'fastflowlm|amdxdna|xrt|lemonade' || echo 'no matching packages'"
caps "linux-firmware package"   "dpkg-query -W -f='\${Package} \${Version}\n' linux-firmware 2>&1 || echo 'linux-firmware not installed as a package'"
caps "NPU firmware (amdnpu)"    'ls -la /lib/firmware/amdnpu/ 2>/dev/null || echo "absent: /lib/firmware/amdnpu/"'
caps "NPU firmware (amdxdna)"   'ls -la /lib/firmware/amdxdna/ 2>/dev/null || echo "absent: /lib/firmware/amdxdna/"'
caps "NPU firmware verdict"     'd=$(ls -d /lib/firmware/amdnpu /lib/firmware/amdxdna 2>/dev/null | head -1); if [ -z "$d" ]; then echo "BLOCKER: no NPU firmware directory found (need 1.1.0.0 or later)"; else echo "firmware dir: $d"; ls "$d" | grep -Ei "npu|1\.1" || echo "(listing above)"; fi'

echo "############################################################"
echo "# BLOCK 1.1 - DISCOVER THE PORT (do not assume 52625)"
echo "# A bind-address flag here decides the whole firewall plan."
echo "############################################################"
caps "flm port"                 'flm port'
caps "flm --help"               'flm --help'
caps "flm serve --help"         'flm serve --help'

echo "############################################################"
echo "# BLOCK 3 - the two decisive checks"
echo "# Gate: both must succeed and the NPU must be listed."
echo "# flm validate = kernel DRM path.  flm run / xrt-smi = XRT userspace path."
echo "############################################################"
caps "flm validate"             'flm validate'
caps "flm validate --json"      'flm validate --json'
caps "xrt-smi examine"          'xrt-smi examine'

echo "############################################################"
echo "# BLOCK 4.1 - model catalogue discovery (no downloads)"
echo "############################################################"
caps "flm list"                 'flm list'
caps "flm list --filter installed" 'flm list --filter installed'
caps "flm cache list"           'flm cache list'
caps "flm pull --help"          'flm pull --help'

echo "############################################################"
echo "# END PART A. Nothing above was configured, downloaded or started."
echo "#"
echo "# READING THE EXIT CODES:"
echo "#   0   = ran and succeeded"
echo "#   127 = binary not installed  -> provisioning gap, not a hardware fault"
echo "#   126 = found but not executable"
echo "# Report the output; Part B (Blocks 4-8) follows."
echo "############################################################"