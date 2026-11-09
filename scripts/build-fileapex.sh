#!/bin/bash

# FileApex build helper.
# Gradle ships finished, signed artifacts into <project>/current with their final names.
# After every build this script rescues any finished file Gradle left behind, discards
# everything else (unsigned APKs, Play APKs, staging folders), verifies the shipped .app
# signature, then deletes composeApp/build.
#
# Machines and tool locations live in a per-user settings file outside the repo (see
# CONFIG_FILE below); secrets tied to this checkout stay in signing.local.env.

PROJECT_DIR="${FILEAPEX_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
BUILD_DIR="$PROJECT_DIR/composeApp/build"
CURRENT_DIR="$PROJECT_DIR/current"

SKIPPED=64
NEEDS_SIGNING=1

# ---------- host OS and settings file ----------

host_os() {
    case "${FILEAPEX_UNAME:-$(uname -s)}" in
        Darwin) echo mac ;;
        Linux) echo linux ;;
        MINGW*|MSYS*|CYGWIN*) echo windows ;;
        *) echo unknown ;;
    esac
}
HOST_OS="$(host_os)"

config_path() {
    if [[ -n "${FILEAPEX_CONFIG:-}" ]]; then
        echo "$FILEAPEX_CONFIG"
    elif [[ "$HOST_OS" == windows && -n "${APPDATA:-}" ]]; then
        echo "$APPDATA/FileApex/machines.env"
    else
        echo "${XDG_CONFIG_HOME:-$HOME/.config}/fileapex/machines.env"
    fi
}
CONFIG_FILE="$(config_path)"
# Older location of the Windows PC and Linux machine; read once and migrated, never deleted.
LEGACY_HOSTS_FILE="$PROJECT_DIR/scripts/hosts.local.env"

# ---------- styling ----------
if [[ -t 1 ]]; then
    BOLD=$'\e[1m'; DIM=$'\e[2m'; RESET=$'\e[0m'
    RED=$'\e[31m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; CYAN=$'\e[36m'; MAGENTA=$'\e[35m'
else
    BOLD=; DIM=; RESET=; RED=; GREEN=; YELLOW=; CYAN=; MAGENTA=
fi

get_version() {
    sed -n 's/^name=//p' "$PROJECT_DIR/version.md" 2>/dev/null | head -n 1 | tr -d '[:space:]'
}

term_cols() {
    local cols=""
    if [[ -t 0 ]]; then
        cols="$(stty size </dev/tty 2>/dev/null | awk '{print $2}')"
    fi
    if [[ "$cols" =~ ^[0-9]+$ ]] && ((cols > 0)); then
        echo "$cols"
        return
    fi
    if [[ "${COLUMNS:-}" =~ ^[0-9]+$ ]] && ((COLUMNS > 0)); then
        echo "$COLUMNS"
        return
    fi
    echo 80
}

sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        sha256sum "$1" | cut -d' ' -f1
    fi
}

make_tmp_dir() { mktemp -d "${TMPDIR:-/tmp}/fileapex-$1.XXXXXX"; }

# Horizontal rule that fits the terminal.
rule() {
    local w=$(( $(term_cols) - 4 )) line
    ((w > 100)) && w=100
    ((w < 10)) && w=10
    printf -v line '%*s' "$w" ''
    echo "  ${DIM}${line// /─}${RESET}"
}

banner() {
    [[ -t 1 ]] && clear
    local v cols; v="$(get_version)"; cols="$(term_cols)"
    local path="$PROJECT_DIR"
    [[ "$path" == "$HOME"* ]] && path="~${path#"$HOME"}"
    if ((cols >= 52)); then
        echo "${CYAN}${BOLD}"
        echo "  ╔══════════════════════════════════════════════╗"
        echo "  ║                                              ║"
        echo "  ║     ◆  F I L E A P E X   B U I L D E R  ◆    ║"
        echo "  ║                                              ║"
        printf "  ║  %-44s║\n" "  version ${v:-unknown}"
        echo "  ╚══════════════════════════════════════════════╝${RESET}"
    else
        echo ""
        echo "  ${CYAN}${BOLD}◆ FILEAPEX BUILDER${RESET}  ${DIM}v${v:-?}${RESET}"
    fi
    echo "  ${DIM}${path:0:$((cols - 4))}${RESET}"
    echo ""
}

# menu "Title" "Option 1" "Option 2" ...  -> sets MENU_CHOICE to the 1-based number
# The last option is treated as the back/quit entry by the caller.
menu() {
    local title="$1"; shift
    local opts=("$@") n=${#opts[@]} i
    while true; do
        banner
        echo "  ${BOLD}${title}${RESET}"
        echo ""
        for ((i = 0; i < n; i++)); do
            if ((i == n - 1)); then
                echo "   ${DIM}$((i + 1))${RESET}  ${DIM}${opts[i]}${RESET}"
            else
                echo "   ${CYAN}${BOLD}$((i + 1))${RESET}  ${opts[i]}"
            fi
        done
        echo ""
        read -r -p "  ${BOLD}›${RESET} Choose [1-$n]: " MENU_CHOICE
        if [[ "$MENU_CHOICE" =~ ^[0-9]+$ ]] && ((MENU_CHOICE >= 1 && MENU_CHOICE <= n)); then
            return 0
        fi
        echo "  ${RED}Please enter a number from 1 to $n.${RESET}"
        sleep 1
    done
}

# after_build [logfile] [status] - Y builds something else (returns); L views the full build
# log in less; any other key ends the session (exit with the build's status).
# In CLI_MODE=1, skips the interactive prompt and returns immediately.
after_build() {
    local log="$1" status="$2" key
    if (( ${CLI_MODE:-0} )); then
        [[ -n "$log" ]] && rm -f "$log"
        return "$status"
    fi
    echo ""
    while true; do
        if [[ -n "$log" && -s "$log" ]]; then
            read -r -n1 -s -p "  ${BOLD}Build something else?${RESET} ${DIM}[y/N]   L = view full build log${RESET} " key
        else
            read -r -n1 -s -p "  ${BOLD}Build something else?${RESET} ${DIM}[y/N]${RESET} " key
        fi
        echo ""
        case "$key" in
            [lL]) [[ -n "$log" && -s "$log" ]] && less -R +G "$log" ;;
            [yY]) return 0 ;;
            *) [[ -n "$log" ]] && rm -f "$log"; echo "  Goodbye!"; exit "$status" ;;
        esac
    done
}

step() { echo "  ${CYAN}▸${RESET} $*"; }
ok()   { echo "  ${GREEN}✔${RESET} $*"; }
warn() { echo "  ${YELLOW}!${RESET} $*"; }
fail() { echo "  ${RED}✘${RESET} $*"; }

# ---------- settings file ----------
# Plain KEY=value lines, never sourced, so a value can never run a command.

config_get() { # key [file]
    local file="${2:-$CONFIG_FILE}" line
    [[ -f "$file" ]] || return 0
    line="$(grep -E "^$1=" "$file" | tail -n 1)" || true
    line="${line#*=}"
    line="${line%$'\r'}"
    if [[ "$line" == \"*\" || "$line" == \'*\' ]]; then
        line="${line:1:${#line}-2}"
    fi
    printf '%s' "$line"
}

config_set() { # key value
    local key="$1" value="$2" dir tmp
    if [[ ! "$key" =~ ^[A-Z0-9_]+$ ]]; then
        fail "Bad setting name: $key" >&2
        return 1
    fi
    if [[ "$value" == *$'\n'* || "$value" == *$'\r'* ]]; then
        fail "A value for $key spans several lines." >&2
        return 1
    fi
    dir="$(dirname "$CONFIG_FILE")"
    if [[ ! -d "$dir" ]]; then
        mkdir -p "$dir" && chmod 700 "$dir" || return 1
    fi
    tmp="$(mktemp "$dir/.machines.XXXXXX")" || return 1
    {
        [[ -f "$CONFIG_FILE" ]] && grep -v "^$key=" "$CONFIG_FILE"
        printf '%s=%s\n' "$key" "$value"
    } >"$tmp"
    chmod 600 "$tmp" && mv "$tmp" "$CONFIG_FILE"
}

# First run after the move: carry the old hosts file over without ever running it as code.
migrate_legacy_hosts() {
    [[ -f "$CONFIG_FILE" || ! -f "$LEGACY_HOSTS_FILE" ]] && return 0
    local k v
    for k in WIN_HOST WIN_DIR LIN_HOST LIN_KEY; do
        v="$(config_get "$k" "$LEGACY_HOSTS_FILE")"
        [[ -n "$v" ]] && config_set "$k" "$v"
    done
    [[ -n "$(config_get WIN_HOST)" ]] && config_set WIN_MODE ssh
    [[ -n "$(config_get LIN_HOST)" ]] && config_set LIN_MODE ssh
    [[ -f "$CONFIG_FILE" ]] || return 0
    warn "Moved your build hosts from scripts/hosts.local.env to $CONFIG_FILE (the old file is untouched)."
}

# Values that end up inside a command sent to another machine. Anything outside this set is refused.
check_remote_value() { # label value
    local re='^[A-Za-z0-9_./:@+=,~\\-]+$'
    if [[ ! "$2" =~ $re || "$2" == -* || "$2" == *\\ ]]; then
        fail "$1 contains characters that cannot be sent to another machine safely: $2" >&2
        return 1
    fi
}

check_ref_value() { # label value
    local re='^[A-Za-z0-9._/-]+$'
    if [[ ! "$2" =~ $re || "$2" == -* ]]; then
        fail "$1 is not a plain git name: $2" >&2
        return 1
    fi
}

# Local key file of a target. The old hosts file was shell code, so values like $HOME/.ssh/id exist; only a leading
# $HOME or ~ is replaced, as text.
expand_home_text() {
    case "$1" in
        '$HOME/'*) printf '%s' "$HOME/${1#\$HOME/}" ;;
        '~/'*) printf '%s' "$HOME/${1#\~/}" ;;
        *) printf '%s' "$1" ;;
    esac
}

key_path() { # WIN|LIN|MAC
    expand_home_text "$(config_get "${1}_KEY")"
}

git_remote_name() {
    local r; r="$(config_get REMOTE)"
    echo "${r:-origin}"
}

ensure_configured() {
    migrate_legacy_hosts
    [[ -f "$CONFIG_FILE" ]] && return 0
    fail "No settings file yet ($CONFIG_FILE)."
    echo "    Run: $(basename "$0") -initialize"
    if [[ -t 0 && -t 1 ]]; then
        local a
        read -r -p "  Run it now? [y/N] " a
        [[ "$a" =~ ^[yY] ]] && { initialize; return $?; }
    fi
    return 1
}

# Tool locations saved by -initialize reach the builds through the variables their scripts already read.
export_tool_env() {
    local v
    v="$(config_get ANDROID_SDK)"
    if [[ -n "$v" && -z "${ANDROID_HOME:-}" ]]; then export ANDROID_HOME="$v"; fi
    if [[ "$HOST_OS" == linux ]]; then
        v="$(config_get LIN_GO)"
        if [[ -n "$v" && -z "${FILEAPEX_GO:-}" ]]; then
            export FILEAPEX_GO="$v/bin/go"
            export PATH="$v/bin:$PATH"
        fi
        v="$(config_get LIN_JDK)"
        if [[ -n "$v" && -z "${FILEAPEX_JDK:-}" ]]; then export FILEAPEX_JDK="$v"; fi
    else
        v="$(config_get GO)"
        if [[ -n "$v" && -z "${FILEAPEX_GO:-}" ]]; then export FILEAPEX_GO="$v"; fi
    fi
}

prepare_build() {
    ensure_configured || { after_build "" 1; return 1; }
    export_tool_env
}

# ---------- post-build housekeeping ----------

# Move one stray finished file into current/ under its final name.
salvage() {
    local src="$1" name="$2"
    mkdir -p "$CURRENT_DIR"
    rm -rf "${CURRENT_DIR:?}/$name"
    mv "$src" "$CURRENT_DIR/$name" && ok "rescued → current/$name"
}

# Rescue finished artifacts Gradle failed to move. Unsigned APKs and Play APKs are never
# rescued; they are discarded with the build folder.
salvage_leftovers() {
    [[ -d "$BUILD_DIR" ]] || return 0
    local v f
    v="$(get_version)"
    [[ -n "$v" ]] || { warn "no version in version.md; skipping rescue"; return 0; }

    while IFS= read -r -d '' f; do
        case "$f" in
            *unsigned*) step "discarding unsigned APK ${f##*/}" ;;
            */outputs/apk/play/*) step "discarding Play APK ${f##*/} (Play uses AAB only)" ;;
            */outputs/apk/github/debug/*armeabi-v7a*) salvage "$f" "FileApex-v$v-debug-armeabi-v7a.apk" ;;
            */outputs/apk/github/debug/*) salvage "$f" "FileApex-v$v-debug.apk" ;;
            */outputs/apk/github/release/*armeabi-v7a*) salvage "$f" "FileApex-v$v-armeabi-v7a.apk" ;;
            */outputs/apk/github/release/*) salvage "$f" "FileApex-v$v.apk" ;;
        esac
    done < <(find "$BUILD_DIR/outputs/apk" -name '*.apk' -print0 2>/dev/null)

    while IFS= read -r -d '' f; do
        salvage "$f" "FileApex-v$v-release.aab"
    done < <(find "$BUILD_DIR/outputs/bundle/playRelease" -name '*.aab' -print0 2>/dev/null)

    while IFS= read -r -d '' f; do
        case "${f##*/}" in
            *x86*|*x64*|*Intel*) salvage "$f" "FileApex-v$v-Intel.dmg" ;;
            *) salvage "$f" "FileApex-v$v-Silicon.dmg" ;;
        esac
    done < <(find "$BUILD_DIR/compose/binaries/main/dmg" -name '*.dmg' -print0 2>/dev/null)

    while IFS= read -r -d '' f; do
        salvage "$f" "FileApex-v$v.xpi"
    done < <(find "$BUILD_DIR/firefox-extension" -name '*.xpi' -print0 2>/dev/null)
}

# Gradle signs and ships FileApex.app itself; just confirm the shipped copy is intact.
verify_shipped_app() {
    local app="$CURRENT_DIR/FileApex.app"
    [[ -d "$app" ]] || return 0
    if codesign --verify --verbose=1 "$app" >/dev/null 2>&1; then
        ok "current/FileApex.app signature valid"
    else
        fail "current/FileApex.app signature is INVALID"
    fi
}

cleanup_build_dir() {
    echo ""
    step "Cleaning up…"
    ./gradlew --stop >/dev/null 2>&1   # release file handles held by the daemon
    salvage_leftovers
    # Ensure no Play Store APK files ever remain in current/
    if compgen -G "$CURRENT_DIR/FileApex-v*-play*.apk" >/dev/null 2>&1; then
        rm -f "$CURRENT_DIR"/FileApex-v*-play*.apk
        step "removed stray Play APK from current/"
    fi
    if [[ -d "$BUILD_DIR" ]]; then
        local size
        size="$(du -sh "$BUILD_DIR" 2>/dev/null | cut -f1)"
        rm -rf "$BUILD_DIR" && ok "deleted composeApp/build (freed ${size// /})"
    fi
    verify_shipped_app
}

show_current() {
    echo ""
    echo "  ${BOLD}Shipped files in current/${RESET}"
    local f any=0
    for f in "$CURRENT_DIR"/*; do
        [[ -e "$f" ]] || continue
        any=1
        printf "   • %-44s %s\n" "${f##*/}" "$(du -sh "$f" 2>/dev/null | cut -f1 | tr -d ' ')"
    done
    ((any)) || echo "   ${DIM}(empty)${RESET}"
}

# Run a backgrounded command/function with the live spinner + V toggle, then clean up.
# Usage: run_with_live_view "Label" "Subtitle" command_fn [args...]
# NEEDS_SIGNING=1 (the default) requires signing.local.env; builds that use no secrets set it to 0.
run_with_live_view() {
    local label="$1" subtitle="$2"; shift 2
    local status
    banner
    echo "  ${BOLD}Building:${RESET} ${MAGENTA}${label}${RESET}  ${DIM}(${subtitle})${RESET}"
    rule

    if ((NEEDS_SIGNING)) && [[ ! -f "$PROJECT_DIR/signing.local.env" ]]; then
        fail "This build needs signing.local.env in $PROJECT_DIR (release signing, maintainer only)."
        echo "    Without it you can run: -doctor, -initialize, -machines, -mac-app -unsigned, -github-debug,"
        echo "    and Windows or Linux builds on a machine set to build locally."
        after_build "" 1
        return 1
    fi

    local log; log="$(mktemp "${TMPDIR:-/tmp}/fileapex-build.XXXXXX")"
    echo "  ${DIM}Press ${RESET}${BOLD}V${RESET}${DIM} at any time to show/hide the raw build output.${RESET}"
    echo ""
    SECONDS=0
    (
        set -e
        if [[ -f "$PROJECT_DIR/signing.local.env" ]]; then
            set -a
            # shellcheck disable=SC1091
            source "$PROJECT_DIR/signing.local.env"
            set +a
        fi
        unset JAVA_HOME
        "$@"
    ) </dev/null >"$log" 2>&1 &
    local pid=$!

    # Live view. Default: spinner, elapsed time, and the latest line from Gradle or from
    # the nested packaging subprocess (Mac builds log there and are otherwise silent).
    # V toggles a scrolling raw-output view of that same source.
    local frames=(⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏) i=0 last width sublog src shown_src="" shown=0 total key
    local verbose=0 oldtty=""
    if [[ -t 0 ]]; then
        oldtty="$(stty -g </dev/tty 2>/dev/null || stty -g)"
        stty -echo -icanon min 0 time 2 </dev/tty 2>/dev/null || stty -echo -icanon min 0 time 2
    fi
    trap 'kill "$pid" 2>/dev/null; [[ -n "$oldtty" ]] && stty "$oldtty" </dev/tty 2>/dev/null; echo; exit 130' INT

    while kill -0 "$pid" 2>/dev/null; do
        sublog="$(ls -t "$BUILD_DIR"/package-*-subprocess.log 2>/dev/null | head -n 1)"
        src="${sublog:-$log}"
        if ((verbose)); then
            if [[ "$src" != "$shown_src" ]]; then
                [[ -n "$shown_src" ]] && printf "\r\e[K  ${DIM}── switched to packaging log ──${RESET}\n"
                shown_src="$src"; shown=0
            fi
            total=$(wc -l <"$src" 2>/dev/null | tr -d ' ')
            if ((total > shown)); then
                printf "\r\e[K"
                tail -n +$((shown + 1)) "$src" | head -n $((total - shown)) | cut -c1-"$(term_cols)"
                shown=$total
            fi
        else
            last="$(tail -n 40 "$src" 2>/dev/null | grep -v '^[[:space:]]*$' | tail -n 1)"
            width=$(( $(term_cols) - 14 ))
            ((width < 10)) && width=10
            printf "\r\e[K  ${CYAN}%s${RESET} %02d:%02d  ${DIM}%s${RESET}" \
                "${frames[i % 10]}" $((SECONDS / 60)) $((SECONDS % 60)) "${last:0:$width}"
        fi
        i=$((i + 1))
        if [[ -n "$oldtty" ]]; then
            # In macOS /bin/bash (3.2), `read -n 1` overrides VMIN/VTIME to VMIN=1,VTIME=0
            # and blocks forever. `dd` performs a raw 1-byte read respecting min 0 time 2 (0.2s).
            key="$(dd bs=1 count=1 </dev/tty 2>/dev/null)"
        else
            sleep 1; key=""
        fi
        case "$key" in
            [vV])
                if ((verbose)); then
                    verbose=0; printf "\r\e[K  ${DIM}── raw output hidden ──${RESET}\n"
                else
                    verbose=1; shown=0; shown_src=""
                    printf "\r\e[K  ${DIM}── raw output (V to hide) ──${RESET}\n"
                fi
                ;;
        esac
    done
    [[ -n "$oldtty" ]] && stty "$oldtty" </dev/tty 2>/dev/null
    trap - INT
    wait "$pid"; status=$?
    printf "\r\e[K"
    local took="$((SECONDS / 60))m $((SECONDS % 60))s"
    # Fold the nested packaging log into the main log so the viewer has everything.
    sublog="$(ls -t "$BUILD_DIR"/package-*-subprocess.log 2>/dev/null | head -n 1)"
    if [[ -n "$sublog" ]]; then
        { echo; echo "===== packaging subprocess log ====="; cat "$sublog"; } >>"$log"
    fi

    rule
    if ((status == 0)); then
        ok "${BOLD}Build succeeded${RESET} in $took"
    else
        fail "${BOLD}Build FAILED${RESET} (exit $status) after $took"
        echo ""
        tail -n 25 "$log" | cut -c1-"$(( $(term_cols) - 4 ))" | sed 's/^/    /'
    fi
    cleanup_build_dir
    show_current
    after_build "$log" "$status"
    rm -f "$log"
    return $status
}

# Run one or more Gradle invocations sequentially.
# Each argument is a full task string passed to ./gradlew --console=plain.
run_gradle_steps() {
    local step_args
    for step_args in "$@"; do
        echo ">>> ./gradlew --console=plain $step_args"
        # shellcheck disable=SC2086
        ./gradlew --console=plain $step_args
    done
}

# The Play bundle check lives in a local-only file; a missing copy fails the AAB step late, so stop early.
preflight_play() {
    [[ -f "$PROJECT_DIR/scripts/check_play_bundle.py" ]] && return 0
    fail "scripts/check_play_bundle.py is missing, so the Play bundle cannot be checked."
    return 1
}

# Release signing is only needed for builds that ship release artifacts. A debug APK uses the
# Android debug key, and an unsigned Mac app uses an ad-hoc signature.
build_needs_signing() {
    local t="$*"
    if [[ "$t" == *assembleGithubDebug* && "$t" != *Release* && "$t" != *ship* ]]; then return 1; fi
    if [[ "$t" == *packageSiliconApp* && "$t" != *ship* && "${FILEAPEX_MAC_UNSIGNED:-}" == 1 ]]; then return 1; fi
    return 0
}

execute_build() {
    local label="$1"; shift
    prepare_build || return 1
    case "$*" in
        *packageSiliconApp*|*packageSiliconDmg*|*packageIntelDmg*|*packageBothDmg*)
            if [[ "$HOST_OS" != mac ]]; then
                fail "Mac builds need a Mac."
                after_build "" 1
                return 1
            fi
            ;;
    esac
    case "$*" in *bundlePlayRelease*) preflight_play || { after_build "" 1; return 1; } ;; esac
    NEEDS_SIGNING=1
    build_needs_signing "$@" || NEEDS_SIGNING=0
    local subtitle="./gradlew $*"
    run_with_live_view "$label" "$subtitle" run_gradle_steps "$@"
}

# ---------- remote and local Windows and Linux builds ----------

# ssh_target <key> <host> <command...>; the key is optional.
ssh_target() {
    local key="$1" host="$2"; shift 2
    if [[ -n "$key" ]]; then
        ssh -n -i "$key" "$host" "$@"
    else
        ssh -n "$host" "$@"
    fi
}

# scp_target <key> <scp args...>
scp_target() {
    local key="$1"; shift
    if [[ -n "$key" ]]; then
        scp -q -i "$key" "$@"
    else
        scp -q "$@"
    fi
}

note_working_tree() {
    echo "Building the working tree at $(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null), not a pushed commit."
    local dirty; dirty="$(git -C "$PROJECT_DIR" status --short 2>/dev/null)"
    if [[ -n "$dirty" ]]; then
        echo "Uncommitted changes:"
        echo "$dirty"
    fi
    return 0
}

# Remote Windows build over SSH (WIN_HOST, WIN_DIR, WIN_KEY, WIN_MSYS from the settings file).
run_remote_windows() {
    local version="$1" branch="$2" remote_tmp
    local host dir key msys remote expected
    host="$(config_get WIN_HOST)"; dir="$(config_get WIN_DIR)"; key="$(key_path WIN)"
    msys="$(config_get WIN_MSYS)"; msys="${msys:-C:\\msys64\\ucrt64\\bin}"
    remote="$(git_remote_name)"
    if [[ -z "$host" || -z "$dir" ]]; then
        echo "[Windows] WIN_HOST or WIN_DIR is missing: run $(basename "$0") -machines"
        return 1
    fi
    check_remote_value WIN_HOST "$host" && check_remote_value WIN_DIR "$dir" &&
        check_remote_value WIN_MSYS "$msys" && check_remote_value REMOTE "$remote" &&
        check_ref_value "Branch" "$branch" || return 1
    [[ -z "$key" ]] || check_remote_value WIN_KEY "$key" || return 1

    remote_tmp="$(make_tmp_dir win)"
    trap 'rm -rf "$remote_tmp"' RETURN

    cat > "$remote_tmp/build_full.ps1" <<'EOF'
param([string]$Branch, [string]$Expected, [string]$Remote, [string]$Dir, [string]$Msys)
$ErrorActionPreference = 'Stop'
$env:Path = "$Msys;" + $env:Path
Set-Location $Dir
Remove-Item -ErrorAction SilentlyContinue current\FileApex-v*.exe
git fetch $Remote
if ($LASTEXITCODE -ne 0) { exit 1 }
git checkout -B $Branch "$Remote/$Branch"
if ($LASTEXITCODE -ne 0) { exit 1 }
if ((git rev-parse HEAD) -ne $Expected) { Write-Output "This host is not on the pushed commit $Expected"; exit 1 }
git log --oneline -1
.\gradlew.bat copyWindowsBuilds --console=plain
exit $LASTEXITCODE
EOF

    mkdir -p "$CURRENT_DIR"
    echo "[Windows] Connecting to $host ($branch)..."
    scp_target "$key" "$remote_tmp/build_full.ps1" "$host:build_full.ps1"
    expected="$(git -C "$PROJECT_DIR" rev-parse HEAD)"
    ssh_target "$key" "$host" "powershell -NoProfile -ExecutionPolicy Bypass -File build_full.ps1 -Branch \"$branch\" -Expected \"$expected\" -Remote \"$remote\" -Dir \"$dir\" -Msys \"$msys\"" || {
        echo "[Windows] The remote build failed; nothing was copied."; return 1; }
    scp_target "$key" "$host:$dir/current/FileApex-v$version.exe" "$CURRENT_DIR/" || { echo "[Windows] Copying the installer failed."; return 1; }
    ssh_target "$key" "$host" "(Get-FileHash \"$dir/current/FileApex-v$version.exe\" -Algorithm SHA256).Hash.ToLower()" \
        > "$remote_tmp/windows.sha" 2>/dev/null
    local local_sha remote_sha
    local_sha="$(sha256_of "$CURRENT_DIR/FileApex-v$version.exe")"
    remote_sha="$(tr -d '\r\n ' < "$remote_tmp/windows.sha")"
    [[ "$local_sha" == "$remote_sha" ]] || { echo "[Windows] SHA-256 mismatch for FileApex-v$version.exe"; return 1; }
    echo "[Windows] Finished and verified FileApex-v$version.exe"
}

# Windows build on this Windows machine, from Git for Windows' bash.
run_local_windows() {
    local version="$1" msys
    msys="$(config_get WIN_MSYS)"; msys="${msys:-C:\\msys64\\ucrt64\\bin}"
    if command -v cygpath >/dev/null 2>&1; then msys="$(cygpath -u "$msys")"; fi
    note_working_tree
    mkdir -p "$CURRENT_DIR"
    PATH="$msys:$PATH" ./gradlew.bat copyWindowsBuilds --console=plain || { echo "[Windows] The build failed."; return 1; }
    [[ -f "$CURRENT_DIR/FileApex-v$version.exe" ]] || { echo "[Windows] FileApex-v$version.exe was not produced."; return 1; }
    echo "[Windows] Finished FileApex-v$version.exe"
}

run_windows_target() {
    local version="$1" branch="$2"
    case "$(config_get WIN_MODE)" in
        ssh) run_remote_windows "$version" "$branch" ;;
        local)
            if [[ "$HOST_OS" != windows ]]; then
                echo "[Windows] WIN_MODE=local needs a Windows machine."
                return 1
            fi
            run_local_windows "$version"
            ;;
        *) echo "[Windows] Not configured, skipped (run $(basename "$0") -machines)."; return "$SKIPPED" ;;
    esac
}

# Remote Linux build over SSH (LIN_HOST, LIN_DIR, LIN_KEY, LIN_GO, LIN_JDK from the settings file).
run_remote_linux() {
    local version="$1" branch="$2" remote_tmp
    local host dir dir_rel key go jdk remote expected
    host="$(config_get LIN_HOST)"; dir="$(config_get LIN_DIR)"; dir="${dir:-FileApex}"
    key="$(key_path LIN)"; go="$(config_get LIN_GO)"; jdk="$(config_get LIN_JDK)"
    remote="$(git_remote_name)"
    if [[ -z "$host" ]]; then
        echo "[Linux] LIN_HOST is missing: run $(basename "$0") -machines"
        return 1
    fi
    check_remote_value LIN_HOST "$host" && check_remote_value LIN_DIR "$dir" &&
        check_remote_value REMOTE "$remote" && check_ref_value "Branch" "$branch" || return 1
    [[ -z "$key" ]] || check_remote_value LIN_KEY "$key" || return 1
    [[ -z "$go" ]] || check_remote_value LIN_GO "$go" || return 1
    [[ -z "$jdk" ]] || check_remote_value LIN_JDK "$jdk" || return 1
    dir_rel="${dir#\~/}"

    remote_tmp="$(make_tmp_dir lin)"
    trap 'rm -rf "$remote_tmp"' RETURN

    cat > "$remote_tmp/build_full_linux.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
BRANCH="$1"
EXPECTED="$2"
REMOTE="$3"
DIR="${4/#\~/$HOME}"
GO_HOME="${5:-}"
JDK_HOME="${6:-}"
export PATH="/usr/local/bin:$PATH"
if [ -n "$GO_HOME" ]; then export PATH="${GO_HOME/#\~/$HOME}/bin:$PATH"; fi
if [ -n "$JDK_HOME" ]; then export FILEAPEX_JDK="${JDK_HOME/#\~/$HOME}"; fi
cd "$DIR"
if [ -n "$(git status --porcelain)" ]; then
  echo "The Linux checkout has local changes; refusing to build. Commit or clear them on the VM first:"
  git status --short
  exit 1
fi
rm -rf current
git fetch "$REMOTE"
git checkout -B "$BRANCH" "$REMOTE/$BRANCH"
[ "$(git rev-parse HEAD)" = "$EXPECTED" ] || { echo "This host is not on the pushed commit $EXPECTED"; exit 1; }
git log --oneline -1
bash scripts/build_linux.sh
EOF

    mkdir -p "$CURRENT_DIR"
    echo "[Linux] Connecting to $host ($branch)..."
    expected="$(git -C "$PROJECT_DIR" rev-parse HEAD)"
    scp_target "$key" "$remote_tmp/build_full_linux.sh" "$host:build_full_linux.sh"
    ssh_target "$key" "$host" "bash build_full_linux.sh '$branch' '$expected' '$remote' '$dir' '$go' '$jdk'" || {
        echo "[Linux] The remote build failed; nothing was copied."; return 1; }
    scp_target "$key" "$host:$dir_rel/current/FileApex-v$version-*" "$CURRENT_DIR/" || { echo "[Linux] Copying the packages failed."; return 1; }
    local f local_sha remote_sha
    for f in "$CURRENT_DIR/FileApex-v$version-amd64.deb" \
             "$CURRENT_DIR/FileApex-v$version-x86_64.rpm" \
             "$CURRENT_DIR/FileApex-v$version-x86_64.AppImage"; do
        local_sha="$(sha256_of "$f")"
        remote_sha="$(ssh_target "$key" "$host" "sha256sum '$dir_rel/current/$(basename "$f")'" | cut -d' ' -f1)"
        [[ "$local_sha" == "$remote_sha" ]] || { echo "[Linux] SHA-256 mismatch for $(basename "$f")"; return 1; }
    done
    echo "[Linux] Finished and verified .deb, .rpm, and .AppImage"
}

# Mac apps (MAC_*) or Android release files (ANDROID_*) built on another computer over SSH. That computer runs this same
# script with -mac or -android, so it needs its own settings file, signing.local.env and signing identity.
run_remote_signed() { # MAC|ANDROID version branch
    local t="$1" version="$2" branch="$3" remote_tmp label flag
    local host dir dir_rel key remote expected files=() fargs="" f n local_sha remote_sha
    case "$t" in
        MAC) label=Mac; flag=-mac; files=("FileApex-v$version-Silicon.dmg" "FileApex-v$version-Intel.dmg") ;;
        *) label=Android; flag=-android; files=("FileApex-v$version.apk" "FileApex-v$version-armeabi-v7a.apk" "FileApex-v$version-release.aab") ;;
    esac
    host="$(config_get "${t}_HOST")"; dir="$(config_get "${t}_DIR")"; key="$(key_path "$t")"
    remote="$(git_remote_name)"
    if [[ -z "$host" || -z "$dir" ]]; then
        echo "[$label] ${t}_HOST or ${t}_DIR is missing: run $(basename "$0") -machines"
        return 1
    fi
    check_remote_value "${t}_HOST" "$host" && check_remote_value "${t}_DIR" "$dir" &&
        check_remote_value REMOTE "$remote" && check_ref_value "Branch" "$branch" && check_ref_value "Version" "$version" || return 1
    [[ -z "$key" ]] || check_remote_value "${t}_KEY" "$key" || return 1
    dir_rel="${dir#\~/}"
    for f in "${files[@]}"; do fargs="$fargs '$f'"; done

    remote_tmp="$(make_tmp_dir signed)"
    trap 'rm -rf "$remote_tmp"' RETURN

    cat > "$remote_tmp/build_remote_signed.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
BRANCH="$1"
EXPECTED="$2"
REMOTE="$3"
DIR="${4/#\~/$HOME}"
FLAG="$5"
shift 5
cd "$DIR"
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "This checkout has local changes; refusing to build. Commit or clear them here first:"
  git status --short
  exit 1
fi
git fetch "$REMOTE"
git checkout -B "$BRANCH" "$REMOTE/$BRANCH"
[ "$(git rev-parse HEAD)" = "$EXPECTED" ] || { echo "This host is not on the pushed commit $EXPECTED"; exit 1; }
git log --oneline -1
for f in "$@"; do rm -f "current/$f"; done
bash scripts/build-fileapex.sh "$FLAG"
EOF

    mkdir -p "$CURRENT_DIR"
    echo "[$label] Connecting to $host ($branch)..."
    expected="$(git -C "$PROJECT_DIR" rev-parse HEAD)"
    scp_target "$key" "$remote_tmp/build_remote_signed.sh" "$host:build_remote_signed.sh"
    ssh_target "$key" "$host" "bash build_remote_signed.sh '$branch' '$expected' '$remote' '$dir' '$flag'$fargs" || {
        echo "[$label] The remote build failed; nothing was copied."; return 1; }
    for n in "${files[@]}"; do
        scp_target "$key" "$host:$dir_rel/current/$n" "$CURRENT_DIR/" || { echo "[$label] Copying $n failed."; return 1; }
        local_sha="$(sha256_of "$CURRENT_DIR/$n")"
        remote_sha="$(ssh_target "$key" "$host" "shasum -a 256 '$dir_rel/current/$n'" | cut -d' ' -f1)"
        [[ "$local_sha" == "$remote_sha" ]] || { echo "[$label] SHA-256 mismatch for $n"; return 1; }
    done
    echo "[$label] Finished and verified ${files[*]}"
}

run_linux_target() {
    local version="$1" branch="$2"
    case "$(config_get LIN_MODE)" in
        ssh) run_remote_linux "$version" "$branch" ;;
        local)
            if [[ "$HOST_OS" != linux ]]; then
                echo "[Linux] LIN_MODE=local needs a Linux machine."
                return 1
            fi
            note_working_tree
            bash "$PROJECT_DIR/scripts/build_linux.sh"
            ;;
        *) echo "[Linux] Not configured, skipped (run $(basename "$0") -machines)."; return "$SKIPPED" ;;
    esac
}

# Verify clean working tree and pushed HEAD on the configured git remote before remote SSH builds.
verify_git_pushed_for_remote() {
    local branch="$1" remote
    remote="$(git_remote_name)"
    if [[ -n "$(git status --porcelain --untracked-files=no 2>/dev/null)" ]]; then
        fail "Uncommitted tracked changes in repository."
        warn "Commit and push to $remote/$branch first so remote hosts build the same commit."
        after_build "" 1
        return 1
    fi
    step "Checking $remote/$branch..."
    if ! git fetch -q "$remote" "$branch" 2>/dev/null || [[ "$(git rev-parse HEAD)" != "$(git rev-parse "$remote/$branch" 2>/dev/null)" ]]; then
        fail "HEAD is not pushed to $remote/$branch."
        warn "Push the branch to $remote first so remote hosts build the same commit."
        after_build "" 1
        return 1
    fi
    return 0
}

any_ssh_target() {
    [[ "$(config_get WIN_MODE)" == ssh || "$(config_get LIN_MODE)" == ssh || "$(config_get ANDROID_MODE)" == ssh ]] && return 0
    [[ "$HOST_OS" != mac && "$(config_get MAC_MODE)" == ssh ]]
}

# Android builds on any system. Without a setting a Mac builds Android itself, as it always did; other systems skip it.
android_mode() {
    local m; m="$(config_get ANDROID_MODE)"
    if [[ -z "$m" ]]; then
        if [[ "$HOST_OS" == mac ]]; then m=local; else m=off; fi
    fi
    echo "$m"
}

show_log() { # label logfile
    [[ -f "$2" ]] || return 0
    echo ""
    echo "===== $1 build log ====="
    cat "$2"
}

# Complete build: Mac apps, Android files, Windows and Linux, each where it is set up (this computer, or another over SSH).
# SSH targets run in the background; local ones run in turn because they share this checkout. Two SSH targets on the same
# computer (for example Mac and Android) run one after the other because they share that checkout too.
run_complete_all_platforms() {
    local version="$1" branch="$2" remote_tmp android mac_ssh=0 same=0
    local mac_status=0 and_status=0 win_status=0 lin_status=0 win_pid="" lin_pid="" mac_pid="" and_pid=""
    android="$(android_mode)"
    remote_tmp="$(make_tmp_dir remote)"
    trap 'rm -rf "$remote_tmp"' RETURN
    [[ "$HOST_OS" != mac && "$(config_get MAC_MODE)" == ssh ]] && mac_ssh=1
    if ((mac_ssh)) && [[ "$android" == ssh && "$(config_get MAC_HOST)" == "$(config_get ANDROID_HOST)" && "$(config_get MAC_DIR)" == "$(config_get ANDROID_DIR)" ]]; then
        same=1
    fi

    if ((mac_ssh)); then
        run_remote_signed MAC "$version" "$branch" > "$remote_tmp/mac.log" 2>&1 &
        mac_pid=$!
    fi
    if [[ "$android" == ssh ]] && ((!same)); then
        run_remote_signed ANDROID "$version" "$branch" > "$remote_tmp/android.log" 2>&1 &
        and_pid=$!
    fi
    if [[ "$(config_get WIN_MODE)" == ssh ]]; then
        run_windows_target "$version" "$branch" > "$remote_tmp/windows.log" 2>&1 &
        win_pid=$!
    fi
    if [[ "$(config_get LIN_MODE)" == ssh ]]; then
        run_linux_target "$version" "$branch" > "$remote_tmp/linux.log" 2>&1 &
        lin_pid=$!
    fi

    if [[ "$HOST_OS" == mac ]]; then
        if [[ "$android" == local ]]; then
            run_gradle_steps \
                "shipGithubRelease packageBothDmg verifyReleaseSigning verifyReleaseApkSigned :verifyGitExecutableScripts" \
                ":composeApp:bundlePlayRelease -PbundleBuild=true" || mac_status=$?
        else
            run_gradle_steps "packageBothDmg :verifyGitExecutableScripts" || mac_status=$?
        fi
    else
        ((mac_ssh)) || echo "Mac apps skipped: they need a Mac (add one over SSH with -machines)."
        if [[ "$android" == local ]]; then
            run_gradle_steps \
                "shipGithubRelease verifyReleaseSigning verifyReleaseApkSigned" \
                ":composeApp:bundlePlayRelease -PbundleBuild=true" || and_status=$?
        fi
    fi
    [[ "$android" == off ]] && echo "Android release steps skipped: not set up (add them with -machines)."

    if [[ -n "$mac_pid" ]]; then
        echo "Waiting for the Mac SSH build to finish..."
        wait "$mac_pid" || mac_status=$?
        show_log Mac "$remote_tmp/mac.log"
    fi
    if ((same)); then
        run_remote_signed ANDROID "$version" "$branch" > "$remote_tmp/android.log" 2>&1 || and_status=$?
        show_log Android "$remote_tmp/android.log"
    elif [[ -n "$and_pid" ]]; then
        echo "Waiting for the Android SSH build to finish..."
        wait "$and_pid" || and_status=$?
        show_log Android "$remote_tmp/android.log"
    fi

    if [[ -n "$win_pid" ]]; then
        echo "Waiting for the Windows SSH build to finish..."
        wait "$win_pid" || win_status=$?
        show_log Windows "$remote_tmp/windows.log"
    else
        run_windows_target "$version" "$branch" || win_status=$?
    fi

    if [[ -n "$lin_pid" ]]; then
        echo "Waiting for the Linux SSH build to finish..."
        wait "$lin_pid" || lin_status=$?
        show_log Linux "$remote_tmp/linux.log"
    else
        run_linux_target "$version" "$branch" || lin_status=$?
    fi

    local skipped=""
    ((win_status == SKIPPED)) && { win_status=0; skipped="$skipped Windows"; }
    ((lin_status == SKIPPED)) && { lin_status=0; skipped="$skipped Linux"; }
    [[ -n "$skipped" ]] && echo "Not built (not configured):$skipped"

    if ((mac_status != 0 || and_status != 0 || win_status != 0 || lin_status != 0)); then
        echo "Complete build status: mac=$mac_status android=$and_status windows=$win_status linux=$lin_status"
        return 1
    fi
}

execute_windows_build() {
    local version branch mode
    version="$(get_version)"
    branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
    prepare_build || return 1
    mode="$(config_get WIN_MODE)"
    banner
    echo "  ${BOLD}Building:${RESET} ${MAGENTA}Windows (.exe)${RESET}"
    rule
    if [[ "$mode" != ssh && "$mode" != local ]]; then
        fail "Windows builds are not set up. Run: $(basename "$0") -machines"
        after_build "" 1
        return 1
    fi
    [[ "$mode" == ssh ]] && { verify_git_pushed_for_remote "$branch" || return 1; }
    NEEDS_SIGNING=0
    run_with_live_view "Windows ($version)" "$mode $(config_get WIN_HOST)" run_windows_target "$version" "$branch"
}

execute_linux_build() {
    local version branch mode
    version="$(get_version)"
    branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
    prepare_build || return 1
    mode="$(config_get LIN_MODE)"
    banner
    echo "  ${BOLD}Building:${RESET} ${MAGENTA}Linux (.deb, .rpm, AppImage)${RESET}"
    rule
    if [[ "$mode" != ssh && "$mode" != local ]]; then
        fail "Linux builds are not set up. Run: $(basename "$0") -machines"
        after_build "" 1
        return 1
    fi
    [[ "$mode" == ssh ]] && { verify_git_pushed_for_remote "$branch" || return 1; }
    NEEDS_SIGNING=0
    run_with_live_view "Linux ($version)" "$mode $(config_get LIN_HOST)" run_linux_target "$version" "$branch"
}

execute_complete_build() {
    local version branch
    version="$(get_version)"
    branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
    prepare_build || return 1
    banner
    echo "  ${BOLD}Building:${RESET} ${MAGENTA}Complete Build (every platform this setup can reach)${RESET}"
    rule
    any_ssh_target && { verify_git_pushed_for_remote "$branch" || return 1; }
    NEEDS_SIGNING=0
    [[ "$HOST_OS" == mac || "$(android_mode)" == local ]] && NEEDS_SIGNING=1
    if [[ "$(android_mode)" == local ]]; then
        preflight_play || { after_build "" 1; return 1; }
    fi
    run_with_live_view \
        "Complete Build ($version)" \
        "Mac, Android, Windows and Linux where set up" \
        run_complete_all_platforms "$version" "$branch"
}

usage() {
    cat <<EOF
Usage: $(basename "$0") [flag ...]

With no arguments, launches the interactive build menu.
With one or more flags, runs the requested build(s) directly and exits:

  -mac              Build both Mac DMGs (Silicon + Intel)
  -mac-app          Build Mac Silicon .app bundle only
  -silicon          Build Mac Silicon DMG
  -intel            Build Mac Intel DMG
  -android          Build both Android targets (GitHub APK + Play AAB)
  -github, -apk     Build GitHub APKs (64-bit + armeabi-v7a)
  -github-debug     Build debug GitHub APKs with the Android debug key (no keystore needed)
  -aab, -play       Build Play Store bundle (.aab)
  -quick            Build Quick Test (GitHub APK + Mac Silicon .app)
  -full             Build Full Mac + Android release (DMGs + GitHub APKs + Play AAB)
  -complete, -all   Build every platform this setup can reach (Mac and Android here or on a Mac over SSH, Windows, Linux)
  -windows, -win    Build Windows .exe (on this machine or over SSH, as set up)
  -linux            Build Linux .deb, .rpm, and .AppImage (on this machine or over SSH, as set up)
  -xpi, -firefox    Build Firefox extension (.xpi)
  -initialize       First run: check this machine, save tool locations, ask which machines build Windows and Linux
  -machines         Add or change the Windows and Linux build machines (-machines --show prints the settings)
  -doctor           Check this machine has everything the builds need (read-only; names what is missing and how to fix it)
  -setup-signing    First time only: create the Mac signing identity and pin it
  -restore-signing <p12>  Restore the pinned signing identity from its backup on a new Mac
  -unsigned         Mac builds only: allow an ad-hoc signature (privacy grants reset on every update)
  -h, --help        Show this help

Settings file: $CONFIG_FILE (override with FILEAPEX_CONFIG)
EOF
}

# ---------- Mac signing and setup check ----------
# One certificate signs every Mac release so macOS keeps the user's privacy grants across updates. Its SHA-1 is pinned in
# macos/signing-identity.sha1; a second certificate would reset those grants for everyone.

SIGN_NAME="FileApex Local Signing"
PIN_FILE="${FILEAPEX_PIN_FILE:-$PROJECT_DIR/macos/signing-identity.sha1}"
SIGN_ENV_FILE="${FILEAPEX_ENV_FILE:-$PROJECT_DIR/signing.local.env}"

# Where the keychain, p12 and release keystore live: the SIGNING_DIR setting, else the folder of the keychain that
# signing.local.env already names, else ~/.fileapex/signing. Only that one path is read from the env file.
resolve_signing_dir() {
    local d keychain
    d="$(config_get SIGNING_DIR)"
    if [[ -z "$d" && -f "$SIGN_ENV_FILE" ]]; then
        keychain="$(grep -E '^export FILEAPEX_MAC_SIGN_KEYCHAIN=' "$SIGN_ENV_FILE" | tail -n 1 | sed 's/^[^=]*=//; s/^"//; s/"$//')"
        [[ -n "$keychain" ]] && d="$(dirname "$keychain")"
    fi
    echo "${d:-$HOME/.fileapex/signing}"
}
SIGNING_DIR="${FILEAPEX_SIGNING_DIR:-$(resolve_signing_dir)}"

signing_keychain() { echo "$SIGNING_DIR/fileapex-signing.keychain-db"; }

pinned_hash() { tr -d '[:space:]' <"$PIN_FILE" 2>/dev/null | tr 'a-f' 'A-F'; }

keychain_has_hash() { security find-identity -p codesigning "$1" 2>/dev/null | grep -q "$2"; }

# Adds the two settings the Gradle build reads; leaves an existing setting alone.
write_signing_env() {
    touch "$SIGN_ENV_FILE"
    [[ -s "$SIGN_ENV_FILE" && -n "$(tail -c1 "$SIGN_ENV_FILE")" ]] && echo >>"$SIGN_ENV_FILE"
    grep -q '^export FILEAPEX_MAC_SIGN_IDENTITY=' "$SIGN_ENV_FILE" ||
        echo "export FILEAPEX_MAC_SIGN_IDENTITY=\"$SIGN_NAME\"" >>"$SIGN_ENV_FILE"
    grep -q '^export FILEAPEX_MAC_SIGN_KEYCHAIN=' "$SIGN_ENV_FILE" ||
        echo "export FILEAPEX_MAC_SIGN_KEYCHAIN=\"$(signing_keychain)\"" >>"$SIGN_ENV_FILE"
}

# Imports a p12 into a new keychain at $1 using the password file $2.
import_signing_p12() {
    local keychain="$1" p12="$2" pwfile="$3"
    security create-keychain -p "" "$keychain" &&
        security set-keychain-settings "$keychain" &&
        security unlock-keychain -p "" "$keychain" &&
        security import "$p12" -k "$keychain" -P "$(cat "$pwfile")" -T /usr/bin/codesign >/dev/null &&
        security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "" "$keychain" >/dev/null
}

setup_signing() {
    local keychain; keychain="$(signing_keychain)"
    if [[ -e "$keychain" ]]; then
        fail "Signing is already set up: $keychain (nothing changed)."
        return 1
    fi
    if [[ -s "$PIN_FILE" ]]; then
        fail "An identity is already pinned ($(pinned_hash)). A new certificate would reset every user's privacy grants."
        echo "    Restore the backup instead: build-fileapex.sh -restore-signing <fileapex-signing.p12>"
        return 1
    fi
    local work; work="$(mktemp -d)"
    mkdir -p "$SIGNING_DIR" && chmod 700 "$SIGNING_DIR" || return 1
    cat >"$work/cert.cnf" <<CNF
[req]
distinguished_name=dn
x509_extensions=ext
prompt=no
[dn]
CN=$SIGN_NAME
O=FileApex
[ext]
basicConstraints=critical,CA:false
keyUsage=critical,digitalSignature
extendedKeyUsage=critical,codeSigning
CNF
    openssl rand -base64 24 >"$SIGNING_DIR/p12-password.txt" && chmod 600 "$SIGNING_DIR/p12-password.txt"
    openssl req -x509 -newkey rsa:2048 -nodes -keyout "$work/key.pem" -out "$work/cert.pem" -days 10950 \
        -config "$work/cert.cnf" 2>/dev/null &&
        openssl pkcs12 -export -inkey "$work/key.pem" -in "$work/cert.pem" -out "$SIGNING_DIR/fileapex-signing.p12" \
            -passout "file:$SIGNING_DIR/p12-password.txt" &&
        chmod 600 "$SIGNING_DIR/fileapex-signing.p12" &&
        import_signing_p12 "$keychain" "$SIGNING_DIR/fileapex-signing.p12" "$SIGNING_DIR/p12-password.txt" || {
        fail "Could not create the signing identity."
        rm -r "$work"
        return 1
    }
    openssl x509 -in "$work/cert.pem" -noout -fingerprint -sha1 | sed 's/.*=//; s/://g' >"$PIN_FILE"
    rm -r "$work"
    write_signing_env
    chmod 600 "$keychain"
    ok "Signing identity created and pinned ($(pinned_hash))."
    warn "Back up $SIGNING_DIR now: losing it resets every user's privacy grants."
}

restore_signing() {
    local p12="${1:-}"
    local keychain; keychain="$(signing_keychain)"
    [[ -f "$p12" ]] || { fail "Usage: build-fileapex.sh -restore-signing <fileapex-signing.p12>"; return 1; }
    [[ -s "$PIN_FILE" ]] || { fail "No pinned identity in $PIN_FILE to restore."; return 1; }
    [[ -e "$keychain" ]] && { fail "Signing is already set up: $keychain (nothing changed)."; return 1; }
    local pwfile="${FILEAPEX_P12_PASSWORD_FILE:-$(dirname "$p12")/p12-password.txt}"
    [[ -f "$pwfile" ]] || { fail "Password file not found: $pwfile"; return 1; }
    local work; work="$(mktemp -d)"
    import_signing_p12 "$work/fileapex-signing.keychain-db" "$p12" "$pwfile" || {
        fail "Could not import $p12."
        rm -r "$work"
        return 1
    }
    if ! keychain_has_hash "$work/fileapex-signing.keychain-db" "$(pinned_hash)"; then
        fail "This backup is a different identity than the pinned one ($(pinned_hash)). Nothing was installed."
        rm -r "$work"
        return 1
    fi
    mkdir -p "$SIGNING_DIR" && chmod 700 "$SIGNING_DIR"
    mv "$work/fileapex-signing.keychain-db" "$keychain" && rm -r "$work"
    [[ "$p12" -ef "$SIGNING_DIR/fileapex-signing.p12" ]] || cp "$p12" "$SIGNING_DIR/fileapex-signing.p12"
    [[ "$pwfile" -ef "$SIGNING_DIR/p12-password.txt" ]] || cp "$pwfile" "$SIGNING_DIR/p12-password.txt"
    write_signing_env
    ok "Signing identity restored and matches the pinned one."
}

# ---------- doctor (read-only) ----------

DOCTOR_MISSING=0
doctor_check() { # label, ok(0/1), detail, fix
    if (($2)); then
        echo "  ${GREEN}✔${RESET} $1: ${DIM}$3${RESET}"
    elif [[ "$1" == *"(optional)"* ]]; then
        echo "  ${YELLOW}!${RESET} $1: ${YELLOW}not found${RESET} ($3)"
        echo "      Fix: $4"
    else
        echo "  ${RED}✘${RESET} $1: ${RED}MISSING${RESET} ($3)"
        echo "      Fix: $4"
        DOCTOR_MISSING=1
    fi
}

env_has() { grep -q "^export $1=" "$SIGN_ENV_FILE" 2>/dev/null; }

flag() { "$@" >/dev/null 2>&1 && echo 1 || echo 0; }

# A saved location when there is one, else the default.
saved_or() { # key default
    local v; v="$(config_get "$1")"
    echo "${v:-$2}"
}

# Names only: values from signing.local.env are never read out.
doctor_mac() {
    local jdk_name
    jdk_name="jdk-$(sed -n 's/^jdk=//p' "$PROJECT_DIR/toolchain.properties" 2>/dev/null | head -n 1 | tr -d '[:space:]')"
    local silicon intel sdk go
    silicon="$(saved_or JDK_SILICON "$HOME/.jdks/$jdk_name/Contents/Home/bin/java")"
    intel="$(saved_or JDK_INTEL "$HOME/.jdks/jdk-21-x64/Contents/Home/bin/java")"
    sdk="$(saved_or ANDROID_SDK "$HOME/Library/Android/sdk")"
    go="${FILEAPEX_GO:-$(saved_or GO "$HOME/Library/FileApex-build/go/bin/go")}"
    local go_version; go_version="$(sed -n 's/^go //p' "$PROJECT_DIR/native/tsnet/go.mod" 2>/dev/null | head -n 1)"
    local keychain; keychain="$(signing_keychain)"
    doctor_check "JDK 21 (Silicon)" "$([[ -x "$silicon" ]] && echo 1 || echo 0)" "$silicon" \
        "download Temurin 21 (aarch64, $jdk_name) from adoptium.net into ~/.jdks/$jdk_name"
    doctor_check "JDK 21 (Intel)" "$([[ -x "$intel" ]] && echo 1 || echo 0)" "$intel" \
        "download Temurin 21 (x64, same version) from adoptium.net into ~/.jdks/jdk-21-x64"
    doctor_check "Android SDK" "$([[ -d "$sdk/platforms/android-36" ]] && echo 1 || echo 0)" "$sdk platform 36" \
        "install Android Studio, open SDK Manager, install Platform 36 and Build-Tools 35"
    doctor_check "Go" "$([[ -x "$go" ]] && echo 1 || echo 0)" "$go" \
        "download Go ${go_version:-from go.mod} for macOS from go.dev into ~/Library/FileApex-build/go"
    doctor_check "Go module cache" "$(compgen -G "$HOME/Library/FileApex-build/gomodcache/tailscale.com@*" >/dev/null && echo 1 || echo 0)" "tailscale.com sources" \
        "cd native/tsnet and run go mod download with GOMODCACHE=~/Library/FileApex-build/gomodcache"
    doctor_check "Rust" "$([[ -x "$HOME/.cargo/bin/cargo" && -x "$HOME/.cargo/bin/wasm-bindgen" ]] && echo 1 || echo 0)" "cargo and wasm-bindgen" \
        "install rustup, add target wasm32-unknown-unknown and llvm-tools, cargo install wasm-bindgen-cli at the Cargo.lock version"
    doctor_check "Rosetta" "$(/usr/bin/arch -x86_64 /usr/bin/true 2>/dev/null && echo 1 || echo 0)" "needed for the Intel DMG" \
        "sudo softwareupdate --install-rosetta --agree-to-license"
    doctor_check "Xcode tools" "$(command -v swiftc >/dev/null && command -v codesign >/dev/null && echo 1 || echo 0)" "swiftc and codesign" \
        "install Xcode from the App Store, then run xcode-select --install"
    local pin_ok=0
    [[ -s "$PIN_FILE" && -e "$keychain" ]] && keychain_has_hash "$keychain" "$(pinned_hash)" && pin_ok=1
    doctor_check "Signing identity" "$pin_ok" "$SIGN_NAME in $keychain matches the pinned hash" \
        "run build-fileapex.sh -restore-signing <backup p12>, or -setup-signing only if no identity is pinned yet"
    doctor_check "Signing settings" "$(env_has FILEAPEX_MAC_SIGN_IDENTITY && env_has FILEAPEX_MAC_SIGN_KEYCHAIN && echo 1 || echo 0)" "FILEAPEX_MAC_SIGN_* in signing.local.env" \
        "run build-fileapex.sh -setup-signing or -restore-signing"
    doctor_check "Android release keystore" "$([[ -f "$SIGNING_DIR/fileapex-release.jks" ]] && echo 1 || echo 0)" "$SIGNING_DIR/fileapex-release.jks" \
        "copy fileapex-release.jks from the maintainer backup (never commit it); not needed for -github-debug"
    doctor_check "Android signing settings" "$(env_has KEYSTORE_PASSWORD && env_has KEY_PASSWORD && env_has KEY_ALIAS && echo 1 || echo 0)" "names only in signing.local.env" \
        "copy signing.local.env.example to signing.local.env and fill it in; not needed for -github-debug"
}

doctor_linux() {
    local jdk go sdk
    jdk="$(saved_or LIN_JDK "${FILEAPEX_JDK:-}")"
    go="${FILEAPEX_GO:-}"
    [[ -z "$go" && -n "$(config_get LIN_GO)" ]] && go="$(config_get LIN_GO)/bin/go"
    [[ -z "$go" ]] && go="$(command -v go 2>/dev/null)"
    sdk="$(saved_or ANDROID_SDK "${ANDROID_HOME:-$HOME/Android/Sdk}")"
    doctor_check "JDK 21 (Temurin)" "$([[ -n "$jdk" && -x "$jdk/bin/java" || -x "$PROJECT_DIR/.build-jdk/temurin-21-linux-$(uname -m | sed 's/x86_64/x64/; s/arm64/aarch64/')/bin/java" ]] && echo 1 || echo 0)" "${jdk:-.build-jdk (build_linux.sh downloads it)}" \
        "nothing to do: scripts/build_linux.sh downloads Temurin 21 on its first run, or set LIN_JDK to your own"
    doctor_check "Go" "$([[ -x "$go" ]] && echo 1 || echo 0)" "${go:-not found}" \
        "install Go (version in native/tsnet/go.mod) from go.dev and set LIN_GO to its folder"
    doctor_check "Rust (optional)" "$(flag command -v cargo)" "cargo; used by native/dropweb and the Iroh rebuild, not by this machine's packages" \
        "install rustup from rustup.rs when you work on those"
    doctor_check "appimagetool" "$(flag command -v appimagetool)" "on PATH" \
        "download appimagetool from the AppImageKit releases and put it on PATH"
    doctor_check "rpm tools" "$(flag command -v rpmbuild)" "rpmbuild" "install the rpm package (apt install rpm)"
    doctor_check "dpkg tools" "$(flag command -v dpkg-deb)" "dpkg-deb" "install dpkg and fakeroot (apt install dpkg-dev fakeroot)"
    doctor_check "Android SDK (optional)" "$([[ -d "$sdk/platforms" ]] && echo 1 || echo 0)" "$sdk" \
        "only needed for Android builds: install the Android command line tools and platform 36"
}

doctor_windows() {
    local go sdk msys inno
    go="${FILEAPEX_GO:-$(saved_or GO "$(command -v go 2>/dev/null)")}"
    sdk="$(saved_or ANDROID_SDK "${ANDROID_HOME:-${LOCALAPPDATA:-}/Android/Sdk}")"
    msys="$(saved_or WIN_MSYS 'C:\msys64\ucrt64\bin')"
    command -v cygpath >/dev/null 2>&1 && msys="$(cygpath -u "$msys")"
    inno="$HOME/AppData/Local/Programs/Inno Setup 6/ISCC.exe"
    doctor_check "JDK 21" "$(flag command -v java)" "java on PATH" \
        "install Temurin 21 from adoptium.net and add it to PATH"
    doctor_check "Go" "$([[ -x "$go" ]] && echo 1 || echo 0)" "${go:-not found}" \
        "install Go (version in native/tsnet/go.mod) from go.dev"
    doctor_check "Rust (optional)" "$(flag command -v cargo)" "cargo; used by native/dropweb and the Iroh rebuild, not by this machine's packages" \
        "install rustup from rustup.rs when you work on those"
    doctor_check "MSYS2 gcc" "$([[ -f "$msys/gcc.exe" ]] && echo 1 || echo 0)" "$msys/gcc.exe" \
        "install MSYS2 from msys2.org, then run pacman -S mingw-w64-ucrt-x86_64-gcc in the UCRT64 shell"
    doctor_check "Inno Setup" "$([[ -f "$inno" ]] && echo 1 || echo 0)" "$inno" \
        "install Inno Setup 6 for the current user only (the build looks in AppData/Local/Programs) from jrsoftware.org"
    doctor_check "Android SDK (optional)" "$([[ -d "$sdk/platforms" ]] && echo 1 || echo 0)" "$sdk" \
        "only needed for Android builds: install Android Studio and SDK platform 36"
}

# 1 when the command, run on the remote machine, prints True.
remote_flag() { # key host command
    [[ "$(ssh_target "$1" "$2" "$3" 2>/dev/null | tr -d '\r\n ')" == True ]] && echo 1 || echo 0
}

doctor_remote_target() { # WIN|LIN
    local t="$1" label host key dir dir_rel go msys
    [[ "$(config_get "${t}_MODE")" == ssh ]] || return 0
    host="$(config_get "${t}_HOST")"; key="$(key_path "$t")"
    [[ "$t" == MAC && "$HOST_OS" == mac ]] && return 0
    case "$t" in
        WIN) label=Windows; dir="$(config_get WIN_DIR)" ;;
        MAC) label=Mac; dir="$(config_get MAC_DIR)" ;;
        ANDROID) label=Android; dir="$(config_get ANDROID_DIR)" ;;
        *) label=Linux; dir="$(config_get LIN_DIR)"; dir="${dir:-FileApex}" ;;
    esac
    echo ""
    echo "  ${BOLD}$label over SSH${RESET}"
    if ! { check_remote_value "${t}_HOST" "$host" && check_remote_value "${t}_DIR" "$dir"; } 2>/dev/null; then
        doctor_check "$label settings" 0 "host or folder missing or unsafe" "run build-fileapex.sh -machines"
        return 0
    fi
    dir_rel="${dir#\~/}"
    if ! ssh_target "$key" "$host" echo ok >/dev/null 2>&1; then
        doctor_check "$label connection" 0 "ssh to the saved host failed" "check the host, key and that the machine is on"
        return 0
    fi
    doctor_check "$label connection" 1 "answers over SSH"
    if [[ "$t" == MAC || "$t" == ANDROID ]]; then
        doctor_check "$label checkout" "$(remote_flag "$key" "$host" "[ -d '$dir_rel/.git' ] && echo True || echo False")" "$dir" "clone the repository there"
        doctor_check "$label signing settings" "$(remote_flag "$key" "$host" "[ -f '$dir_rel/signing.local.env' ] && echo True || echo False")" "signing.local.env there" "set up signing on that Mac (-setup-signing or -restore-signing, then signing.local.env)"
    elif [[ "$t" == WIN ]]; then
        msys="$(saved_or WIN_MSYS 'C:\msys64\ucrt64\bin')"
        doctor_check "$label checkout" "$(remote_flag "$key" "$host" "Test-Path \"$dir/.git\"")" "$dir" "clone the repository there"
        doctor_check "$label Go" "$(remote_flag "$key" "$host" "[bool](Get-Command go -ErrorAction SilentlyContinue)")" "go on PATH" "install Go on that machine"
        doctor_check "$label MSYS2 gcc" "$(remote_flag "$key" "$host" "Test-Path \"$msys/gcc.exe\"")" "$msys" "install MSYS2 with the UCRT64 gcc"
    else
        go="$(config_get LIN_GO)"
        doctor_check "$label checkout" "$(remote_flag "$key" "$host" "[ -d '$dir_rel/.git' ] && echo True || echo False")" "$dir" "clone the repository there"
        if [[ -n "$go" ]]; then
            doctor_check "$label Go" "$(remote_flag "$key" "$host" "g='$go'; g=\"\${g/#\\~/\$HOME}\"; [ -x \"\$g/bin/go\" ] && echo True || echo False")" "$go/bin/go" "install Go there or fix LIN_GO"
        else
            doctor_check "$label Go" "$(remote_flag "$key" "$host" "command -v go >/dev/null 2>&1 && echo True || echo False")" "go on PATH" "install Go there or set LIN_GO"
        fi
        doctor_check "$label appimagetool" "$(remote_flag "$key" "$host" "command -v appimagetool >/dev/null 2>&1 && echo True || echo False")" "on PATH" "put appimagetool on PATH there"
    fi
}

doctor() {
    DOCTOR_MISSING=0
    echo "  ${BOLD}FileApex build setup check${RESET}"
    case "$HOST_OS" in
        mac) doctor_mac ;;
        linux) doctor_linux ;;
        windows) doctor_windows ;;
        *) fail "Unsupported system: $(uname -s)"; return 1 ;;
    esac
    doctor_remote_target WIN
    doctor_remote_target LIN
    doctor_remote_target MAC
    doctor_remote_target ANDROID
    return "$DOCTOR_MISSING"
}

# ---------- -initialize and -machines ----------

ask() { # label default -> ASK_REPLY
    local reply
    printf '  %s%s: ' "$1" "${2:+ [$2]}"
    read -r reply
    ASK_REPLY="${reply:-$2}"
}

confirm() { # question, default yes
    local reply
    printf '  %s [Y/n] ' "$1"
    read -r reply
    [[ ! "$reply" =~ ^[nN] ]]
}

# Saves a located location only when nothing is saved yet, so a re-run keeps earlier answers.
save_found() { # key path
    [[ -n "$2" && -z "$(config_get "$1")" ]] || return 0
    config_set "$1" "$2" && ok "found $1: $2"
}

first_existing() { # test-operator candidates...
    local op="$1" p; shift
    for p in "$@"; do
        [[ -n "$p" ]] && test "$op" "$p" && { echo "$p"; return 0; }
    done
    return 1
}

locate_components() {
    local p
    case "$HOST_OS" in
        mac)
            p="$(first_existing -x "$HOME"/.jdks/jdk-21*/Contents/Home/bin/java)"; save_found JDK_SILICON "$p"
            p="$(first_existing -x "$HOME/.jdks/jdk-21-x64/Contents/Home/bin/java")"; save_found JDK_INTEL "$p"
            p="$(first_existing -d "$HOME/Library/Android/sdk" "${ANDROID_HOME:-}")"; save_found ANDROID_SDK "$p"
            p="$(first_existing -x "${FILEAPEX_GO:-}" "$HOME/Library/FileApex-build/go/bin/go" "$(command -v go 2>/dev/null)")"; save_found GO "$p"
            ;;
        linux)
            p="$(first_existing -d "${ANDROID_HOME:-}" "$HOME/Android/Sdk")"; save_found ANDROID_SDK "$p"
            p="$(command -v go 2>/dev/null)"
            [[ -z "$p" ]] && p="$(first_existing -x /usr/local/go/bin/go /usr/lib/go/bin/go)"
            [[ -n "$p" ]] && save_found LIN_GO "$(dirname "$(dirname "$p")")"
            ;;
        windows)
            p="$(first_existing -d "${ANDROID_HOME:-}" "${LOCALAPPDATA:-}/Android/Sdk")"; save_found ANDROID_SDK "$p"
            p="$(command -v go 2>/dev/null)"; save_found GO "$p"
            p="$(first_existing -d /c/msys64/ucrt64/bin)"; [[ -n "$p" ]] && save_found WIN_MSYS 'C:\msys64\ucrt64\bin'
            ;;
    esac
}

# Proves an SSH target before it is saved. Read-only: connects, then asks git about the folder.
probe_ssh_target() { # key host dir
    local key="$1" host="$2" dir="$3" out rc dir_rel="${3#\~/}"
    local opts=(-o BatchMode=yes -o ConnectTimeout=8)
    [[ -n "$key" ]] && opts+=(-i "$key")
    out="$(ssh -n "${opts[@]}" "$host" echo ok 2>&1)"; rc=$?
    if ((rc != 0)) || [[ "$(tr -d '\r\n ' <<<"$out")" != *ok ]]; then
        fail "Cannot connect to $host without a password prompt (exit $rc)."
        return 1
    fi
    out="$(ssh -n "${opts[@]}" "$host" "git -C \"$dir_rel\" rev-parse --is-inside-work-tree" 2>&1)"; rc=$?
    if ((rc != 0)) || [[ "$(tr -d '\r\n ' <<<"$out")" != true ]]; then
        fail "$dir on $host is not a git checkout."
        return 1
    fi
    ok "$host answers and $dir is a git checkout."
}

# Asks how one target is built and sets WIZ_MODE to local, ssh or off.
choose_build_way() { # os current
    local os="$1" cur="$2" modes=() texts=() i def=0 reply
    if [[ "$os" == any || "$HOST_OS" == "$os" ]]; then modes+=(local); texts+=("Build it on this computer"); fi
    modes+=(ssh); texts+=("Build it on another computer over SSH")
    modes+=(off); texts+=("Do not build it")
    for ((i = 0; i < ${#modes[@]}; i++)); do
        echo "    $((i + 1))  ${texts[i]}"
        [[ "${modes[i]}" == "${cur:-off}" ]] && def=$((i + 1))
    done
    ((def)) || def=${#modes[@]}
    ask "Choose" "$def"
    reply="$ASK_REPLY"
    if [[ "$reply" =~ ^[0-9]+$ ]] && ((reply >= 1 && reply <= ${#modes[@]})); then
        WIZ_MODE="${modes[reply - 1]}"
        return 0
    fi
    fail "Please enter a number from 1 to ${#modes[@]}."
    return 1
}

# The Windows build needs MSYS2's C compiler. Asked about only when it cannot be found.
wizard_windows_tools() { # mode host key
    local mode="$1" host="$2" key="$3" msys found=0 present
    WIZ_MSYS=""
    msys="$(saved_or WIN_MSYS 'C:\msys64\ucrt64\bin')"
    if [[ "$mode" == local ]]; then
        present="$msys"
        command -v cygpath >/dev/null 2>&1 && present="$(cygpath -u "$msys")"
        [[ -f "$present/gcc.exe" ]] && found=1
    else
        check_remote_value WIN_MSYS "$msys" || return 1
        [[ "$(remote_flag "$key" "$host" "Test-Path \"$msys/gcc.exe\"")" == 1 ]] && found=1
    fi
    ((found)) && return 0
    echo "  MSYS2 provides the C compiler that Windows builds need. It was not found at $msys."
    echo "  Install it from msys2.org (then run: pacman -S mingw-w64-ucrt-x86_64-gcc), or type its folder here."
    ask "MSYS2 compiler folder (Enter to skip)" ""
    if [[ -n "$ASK_REPLY" ]]; then
        check_remote_value WIN_MSYS "$ASK_REPLY" || return 1
        WIZ_MSYS="$ASK_REPLY"
    fi
}

# The Linux build needs Go. Asked about only when it cannot be found.
wizard_linux_tools() { # mode host key
    local mode="$1" host="$2" key="$3" found=0
    WIZ_GO=""
    [[ -n "$(config_get LIN_GO)" ]] && return 0
    if [[ "$mode" == local ]]; then
        command -v go >/dev/null 2>&1 && found=1
    else
        [[ "$(remote_flag "$key" "$host" "command -v go >/dev/null 2>&1 && echo True || echo False")" == 1 ]] && found=1
    fi
    ((found)) && return 0
    echo "  Go was not found on that computer. The Linux build needs it (download from go.dev/dl)."
    ask "Go folder (Enter to skip)" ""
    if [[ -n "$ASK_REPLY" ]]; then
        check_remote_value LIN_GO "$ASK_REPLY" || return 1
        WIZ_GO="$ASK_REPLY"
    fi
}

machine_wizard() { # WIN|LIN|MAC
    local t="$1" title os example mode host="" dir="" key=""
    case "$t" in
        WIN) title="Windows installer (.exe)"; os=windows; example="C:/Users/you/FileApex" ;;
        MAC) title="Mac apps (DMGs)"; os=mac; example="src/FileApex" ;;
        ANDROID) title="Android apps (GitHub APKs and the Play bundle)"; os=any; example="src/FileApex" ;;
        *) title="Linux packages (.deb, .rpm, AppImage)"; os=linux; example="FileApex" ;;
    esac
    echo ""
    echo "  ${BOLD}$title${RESET}"
    choose_build_way "$os" "$(config_get "${t}_MODE")" || return 1
    mode="$WIZ_MODE"
    if [[ "$mode" == off ]]; then
        config_set "${t}_MODE" off
        ok "Not built."
        return 0
    fi
    if [[ "$mode" == ssh ]]; then
        ask "Address of that computer, for example name@192.168.1.20" "$(config_get "${t}_HOST")"; host="$ASK_REPLY"
        ask "Folder on it where FileApex is cloned, for example $example" "$(config_get "${t}_DIR")"; dir="$ASK_REPLY"
        [[ "$t" == LIN && -z "$dir" ]] && dir="FileApex"
        ask "SSH key file (press Enter to use your default key)" "$(config_get "${t}_KEY")"; key="$ASK_REPLY"
        if [[ -z "$host" || -z "$dir" ]]; then fail "Both the address and the folder are needed."; return 1; fi
        check_remote_value "${t}_HOST" "$host" && check_remote_value "${t}_DIR" "$dir" || return 1
        [[ -z "$key" ]] || check_remote_value "${t}_KEY" "$key" || return 1
        probe_ssh_target "$(expand_home_text "$key")" "$host" "$dir" || { warn "Nothing saved."; return 1; }
    fi
    WIZ_MSYS=""; WIZ_GO=""
    case "$t" in
        WIN) wizard_windows_tools "$mode" "$host" "$(expand_home_text "$key")" || return 1 ;;
        LIN) wizard_linux_tools "$mode" "$host" "$(expand_home_text "$key")" || return 1 ;;
    esac
    confirm "Save these settings?" || { warn "Nothing saved."; return 0; }
    config_set "${t}_MODE" "$mode"
    if [[ "$mode" == ssh ]]; then
        config_set "${t}_HOST" "$host"; config_set "${t}_DIR" "$dir"; config_set "${t}_KEY" "$key"
    fi
    [[ -n "$WIZ_MSYS" ]] && config_set WIN_MSYS "$WIZ_MSYS"
    [[ -n "$WIZ_GO" ]] && config_set LIN_GO "$WIZ_GO"
    ok "Saved."
}

# Asked about only when the repository has more than one git remote to choose from.
choose_git_remote() {
    local remotes=() r i def=1 cur reply
    while IFS= read -r r; do [[ -n "$r" ]] && remotes+=("$r"); done < <(git -C "$PROJECT_DIR" remote 2>/dev/null)
    cur="$(git_remote_name)"
    if ((${#remotes[@]} > 1)); then
        echo ""
        echo "  ${BOLD}Which git remote do the other computers pull from?${RESET}"
        for ((i = 0; i < ${#remotes[@]}; i++)); do
            echo "    $((i + 1))  ${remotes[i]}"
            [[ "${remotes[i]}" == "$cur" ]] && def=$((i + 1))
        done
        ask "Choose" "$def"
        reply="$ASK_REPLY"
        if [[ "$reply" =~ ^[0-9]+$ ]] && ((reply >= 1 && reply <= ${#remotes[@]})); then
            config_set REMOTE "${remotes[reply - 1]}"
        else
            fail "Please enter a number from 1 to ${#remotes[@]}."
            return 1
        fi
    elif ((${#remotes[@]} == 1)); then
        config_set REMOTE "${remotes[0]}"
    elif [[ -z "$(config_get REMOTE)" ]]; then
        config_set REMOTE origin
    fi
}

machines_show() {
    if [[ -f "$CONFIG_FILE" ]]; then
        echo "  ${BOLD}$CONFIG_FILE${RESET}"
        sed 's/^/    /' "$CONFIG_FILE"
    else
        echo "  No settings file yet. Run: $(basename "$0") -initialize"
    fi
}

machines() {
    migrate_legacy_hosts
    if [[ "${1:-}" == "--show" ]]; then machines_show; return 0; fi
    if [[ ! -t 0 ]]; then fail "-machines asks questions; run it in a terminal."; return 1; fi
    echo "  Other computers can build the other kinds of files. Skip any you do not need: a build that is not set up is just skipped."
    machine_wizard WIN
    machine_wizard LIN
    [[ "$HOST_OS" != mac ]] && machine_wizard MAC
    machine_wizard ANDROID
    choose_git_remote
}

initialize() {
    if [[ ! -t 0 ]]; then fail "-initialize asks questions; run it in a terminal."; return 1; fi
    echo "  ${BOLD}FileApex first-time setup${RESET}"
    echo "  Settings go to $CONFIG_FILE"
    echo ""
    migrate_legacy_hosts
    doctor || warn "Items marked MISSING are not installed for you; the Fix lines say how."
    echo ""
    locate_components
    [[ -f "$CONFIG_FILE" ]] || { config_set REMOTE "$(git_remote_name)" || return 1; }
    machines
    echo ""
    ok "Setup saved in $CONFIG_FILE. Run -doctor any time to re-check."
}

# ---------- main ----------
[[ "${FILEAPEX_SOURCE_ONLY:-}" == 1 ]] && return 0


if [[ ! -x "$PROJECT_DIR/gradlew" ]]; then
    echo "Error: $PROJECT_DIR/gradlew not found. Set FILEAPEX_DIR to the project root."
    exit 1
fi
cd "$PROJECT_DIR" || exit 1

if [[ "${1:-}" == "-restore-signing" || "${1:-}" == "--restore-signing" ]]; then
    restore_signing "${2:-}"
    exit $?
fi

if [[ "${1:-}" == "-machines" || "${1:-}" == "--machines" ]]; then
    machines "${2:-}"
    exit $?
fi

# -unsigned applies to every Mac build in this run; it is read by the Gradle build.
filtered=()
for a in "$@"; do
    if [[ "$a" == "-unsigned" || "$a" == "--unsigned" ]]; then
        export FILEAPEX_MAC_UNSIGNED=1
    else
        filtered+=("$a")
    fi
done
set -- ${filtered[@]+"${filtered[@]}"}

if (($# > 0)); then
    CLI_MODE=1
    for arg in "$@"; do
        case "${arg#--}" in
            -h|h|-help|help)
                usage
                exit 0
                ;;
            -doctor|doctor)
                migrate_legacy_hosts
                doctor || exit $?
                ;;
            -initialize|initialize)
                initialize || exit $?
                ;;
            -setup-signing|setup-signing)
                setup_signing || exit $?
                ;;
            -mac|mac)
                execute_build "Mac DMGs (Silicon + Intel)" ":composeApp:packageBothDmg" || exit $?
                ;;
            -mac-app|mac-app|-app|app)
                execute_build "Mac app (Silicon)" ":composeApp:packageSiliconApp" || exit $?
                ;;
            -silicon|silicon|-mac-silicon|mac-silicon)
                execute_build "Mac DMG (Silicon)" ":composeApp:packageSiliconDmg" || exit $?
                ;;
            -intel|intel|-mac-intel|mac-intel)
                execute_build "Mac DMG (Intel)" ":composeApp:packageIntelDmg" || exit $?
                ;;
            -android|android)
                execute_build "Android (GitHub APK + Play AAB)" \
                    ":composeApp:shipGithubRelease" \
                    ":composeApp:bundlePlayRelease -PbundleBuild=true" || exit $?
                ;;
            -github|github|-apk|apk)
                execute_build "GitHub APK" ":composeApp:shipGithubRelease" || exit $?
                ;;
            -github-debug|github-debug)
                execute_build "GitHub debug APK" ":composeApp:assembleGithubDebug" || exit $?
                ;;
            -aab|aab|-play|play)
                execute_build "Play Store AAB" ":composeApp:bundlePlayRelease -PbundleBuild=true" || exit $?
                ;;
            -quick|quick)
                execute_build "Quick Test (GitHub APK + Silicon .app)" \
                    ":composeApp:shipGithubRelease :composeApp:packageSiliconApp" || exit $?
                ;;
            -full|full)
                execute_build "Full Build (Mac + Android)" \
                    "shipGithubRelease packageBothDmg verifyReleaseSigning verifyReleaseApkSigned :verifyGitExecutableScripts" \
                    ":composeApp:bundlePlayRelease -PbundleBuild=true" || exit $?
                ;;
            -complete|complete|-all|all)
                execute_complete_build || exit $?
                ;;
            -windows|windows|-win|win)
                execute_windows_build || exit $?
                ;;
            -linux|linux)
                execute_linux_build || exit $?
                ;;
            -xpi|xpi|-firefox|firefox)
                execute_build "Firefox Extension" ":composeApp:shipFirefoxExtension" || exit $?
                ;;
            *)
                fail "Unknown option: $arg"
                echo ""
                usage
                exit 1
                ;;
        esac
    done
    exit 0
fi

while true; do
    menu "What would you like to build?" \
        "🖥   Mac" \
        "🤖  Android" \
        "⚡  Quick Test" \
        "📦  Full Build (Mac/Android)" \
        "🌐  Complete Build (inc. Windows/Linux)" \
        "🦊  Firefox Extension" \
        "🪟  Windows or Linux only" \
        "⚙   Setup and machines" \
        "Quit"
    case "$MENU_CHOICE" in
        1)
            menu "Mac builds" \
                "App file (Silicon)" "DMG (Intel)" "DMG (Silicon)" "Both DMGs" "← Back"
            case "$MENU_CHOICE" in
                1) execute_build "Mac app (Silicon)" ":composeApp:packageSiliconApp" ;;
                2) execute_build "Mac DMG (Intel)" ":composeApp:packageIntelDmg" ;;
                3) execute_build "Mac DMG (Silicon)" ":composeApp:packageSiliconDmg" ;;
                4) execute_build "Mac DMGs (Silicon + Intel)" ":composeApp:packageBothDmg" ;;
            esac
            ;;
        2)
            menu "Android builds" \
                "GitHub APK" "Play Store bundle (AAB)" "Both (GitHub APK + Play AAB)" "GitHub debug APK (no keystore)" "← Back"
            case "$MENU_CHOICE" in
                1) execute_build "GitHub APK" ":composeApp:shipGithubRelease" ;;
                2) execute_build "Play Store AAB" ":composeApp:bundlePlayRelease -PbundleBuild=true" ;;
                3) execute_build "Android (GitHub APK + Play AAB)" \
                    ":composeApp:shipGithubRelease" \
                    ":composeApp:bundlePlayRelease -PbundleBuild=true" ;;
                4) execute_build "GitHub debug APK" ":composeApp:assembleGithubDebug" ;;
            esac
            ;;
        3)
            execute_build "Quick Test (GitHub APK + Silicon .app)" \
                ":composeApp:shipGithubRelease :composeApp:packageSiliconApp"
            ;;
        4)
            execute_build "Full Build (Mac + Android)" \
                "shipGithubRelease packageBothDmg verifyReleaseSigning verifyReleaseApkSigned :verifyGitExecutableScripts" \
                ":composeApp:bundlePlayRelease -PbundleBuild=true"
            ;;
        5)
            execute_complete_build
            ;;
        6)
            execute_build "Firefox Extension" ":composeApp:shipFirefoxExtension"
            ;;
        7)
            menu "Windows or Linux" "Windows (.exe)" "Linux (.deb, .rpm, AppImage)" "← Back"
            case "$MENU_CHOICE" in
                1) execute_windows_build ;;
                2) execute_linux_build ;;
            esac
            ;;
        8)
            menu "Setup and machines" "Check this machine (doctor)" "First-time setup (initialize)" \
                "Change Windows and Linux machines" "Show saved settings" "← Back"
            case "$MENU_CHOICE" in
                1) banner; migrate_legacy_hosts; doctor; read -r -n1 -s -p "  Press any key to go back " _; echo ;;
                2) banner; initialize; read -r -n1 -s -p "  Press any key to go back " _; echo ;;
                3) banner; machines; read -r -n1 -s -p "  Press any key to go back " _; echo ;;
                4) banner; machines_show; read -r -n1 -s -p "  Press any key to go back " _; echo ;;
            esac
            ;;
        9)
            clear; echo "Goodbye!"; exit 0
            ;;
    esac
done
