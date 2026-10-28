#!/bin/bash

# FileApex build helper.
# Gradle ships finished, signed artifacts into <project>/current with their final names.
# After every build this script rescues any finished file Gradle left behind, discards
# everything else (unsigned APKs, Play APKs, staging folders), verifies the shipped .app
# signature, then deletes composeApp/build.

PROJECT_DIR="${FILEAPEX_DIR:-$HOME/AndroidStudioProjects/FileApex}"
BUILD_DIR="$PROJECT_DIR/composeApp/build"
CURRENT_DIR="$PROJECT_DIR/current"

# Addresses of the Windows PC and the Ubuntu VM (WIN_HOST, WIN_DIR, LIN_HOST, LIN_KEY) live in a gitignored file.
HOSTS_FILE="$PROJECT_DIR/scripts/hosts.local.env"
# shellcheck disable=SC1090
[[ -f "$HOSTS_FILE" ]] && source "$HOSTS_FILE"

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
    local cols
    cols="$(stty size </dev/tty 2>/dev/null | awk '{print $2}')"
    if [[ "$cols" =~ ^[0-9]+$ ]] && ((cols > 0)); then
        echo "$cols"
        return
    fi
    tput cols </dev/tty 2>/dev/null || echo 80
}

# Horizontal rule that fits the terminal.
rule() {
    local w=$(( $(term_cols) - 4 )) line
    ((w > 100)) && w=100
    ((w < 10)) && w=10
    printf -v line '%*s' "$w" ''
    echo "  ${DIM}${line// /─}${RESET}"
}

banner() {
    clear
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
run_with_live_view() {
    local label="$1" subtitle="$2"; shift 2
    local status
    banner
    echo "  ${BOLD}Building:${RESET} ${MAGENTA}${label}${RESET}  ${DIM}(${subtitle})${RESET}"
    rule

    if [[ ! -f "$PROJECT_DIR/signing.local.env" ]]; then
        fail "signing.local.env not found in $PROJECT_DIR"
        after_build "" 1
        return 1
    fi

    local log; log="$(mktemp -t fileapex-build)"
    echo "  ${DIM}Press ${RESET}${BOLD}V${RESET}${DIM} at any time to show/hide the raw build output.${RESET}"
    echo ""
    SECONDS=0
    (
        set -e
        set -a
        # shellcheck disable=SC1091
        source "$PROJECT_DIR/signing.local.env"
        set +a
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

execute_build() {
    local label="$1"; shift
    case "$*" in *bundlePlayRelease*) preflight_play || { after_build "" 1; return 1; } ;; esac
    local subtitle="./gradlew $*"
    run_with_live_view "$label" "$subtitle" run_gradle_steps "$@"
}

require_hosts() {
    [[ -n "${WIN_HOST:-}" && -n "${LIN_HOST:-}" ]] && return 0
    fail "Build host addresses are missing: create scripts/hosts.local.env (WIN_HOST, WIN_DIR, LIN_HOST, LIN_KEY)."
    return 1
}

# Remote Windows build over SSH ($WIN_HOST).
run_remote_windows() {
    require_hosts || return 1
    local version="$1" branch="$2" remote_tmp
    remote_tmp="$(mktemp -d -t fileapex-win)"
    trap 'rm -rf "$remote_tmp"' RETURN

    cat > "$remote_tmp/build_full.ps1" <<'EOF'
param([string]$Branch)
$ErrorActionPreference = 'Stop'
$env:Path = "C:\msys64\ucrt64\bin;" + $env:Path
Set-Location C:\Users\cliff\AndroidStudioProjects\FileApex
git fetch origin
git checkout -B $Branch "origin/$Branch"
git log --oneline -1
.\gradlew.bat copyWindowsBuilds --console=plain
exit $LASTEXITCODE
EOF

    mkdir -p "$CURRENT_DIR"
    echo "[Windows] Connecting to $WIN_HOST ($branch)..."
    scp -q "$remote_tmp/build_full.ps1" "$WIN_HOST:build_full.ps1"
    ssh -n "$WIN_HOST" "powershell -NoProfile -ExecutionPolicy Bypass -File build_full.ps1 -Branch $branch"
    scp -q "$WIN_HOST:$WIN_DIR/current/FileApex-v$version.exe" "$CURRENT_DIR/"
    ssh -n "$WIN_HOST" "(Get-FileHash $WIN_DIR/current/FileApex-v$version.exe -Algorithm SHA256).Hash.ToLower()" \
        > "$remote_tmp/windows.sha" 2>/dev/null
    local local_sha remote_sha
    local_sha="$(shasum -a 256 "$CURRENT_DIR/FileApex-v$version.exe" | cut -d' ' -f1)"
    remote_sha="$(tr -d '\r\n ' < "$remote_tmp/windows.sha")"
    [[ "$local_sha" == "$remote_sha" ]] || { echo "[Windows] SHA-256 mismatch for FileApex-v$version.exe"; return 1; }
    echo "[Windows] Finished and verified FileApex-v$version.exe"
}

# Remote Linux build over SSH ($LIN_HOST), or local Linux build when already on Linux.
run_remote_linux() {
    local version="$1" branch="$2"
    if [[ "$(uname -s)" == "Linux" ]]; then
        bash "$PROJECT_DIR/scripts/build_linux.sh"
        return $?
    fi
    require_hosts || return 1
    local remote_tmp
    remote_tmp="$(mktemp -d -t fileapex-lin)"
    trap 'rm -rf "$remote_tmp"' RETURN

    cat > "$remote_tmp/build_full_linux.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
BRANCH="$1"
export PATH="$HOME/toolchain/go/bin:/usr/local/bin:$PATH"
export FILEAPEX_JDK="$HOME/toolchain/temurin21"
cd "$HOME/FileApex"
git fetch origin
git checkout -B "$BRANCH" "origin/$BRANCH"
git log --oneline -1
rm -rf current
bash scripts/build_linux.sh
EOF

    mkdir -p "$CURRENT_DIR"
    echo "[Linux] Connecting to $LIN_HOST ($branch)..."
    scp -q -i "$LIN_KEY" "$remote_tmp/build_full_linux.sh" "$LIN_HOST:build_full_linux.sh"
    ssh -n -i "$LIN_KEY" "$LIN_HOST" "bash build_full_linux.sh $branch"
    scp -q -i "$LIN_KEY" "$LIN_HOST:FileApex/current/FileApex-v$version-*" "$CURRENT_DIR/"
    local f local_sha remote_sha
    for f in "$CURRENT_DIR/FileApex-v$version-amd64.deb" \
             "$CURRENT_DIR/FileApex-v$version-x86_64.rpm" \
             "$CURRENT_DIR/FileApex-v$version-x86_64.AppImage"; do
        local_sha="$(shasum -a 256 "$f" | cut -d' ' -f1)"
        remote_sha="$(ssh -n -i "$LIN_KEY" "$LIN_HOST" "sha256sum FileApex/current/$(basename "$f")" | cut -d' ' -f1)"
        [[ "$local_sha" == "$remote_sha" ]] || { echo "[Linux] SHA-256 mismatch for $(basename "$f")"; return 1; }
    done
    echo "[Linux] Finished and verified .deb, .rpm, and .AppImage"
}

# Verify clean working tree and pushed HEAD on origin before remote SSH builds.
verify_git_pushed_for_remote() {
    local branch="$1"
    if [[ -n "$(git status --porcelain --untracked-files=no 2>/dev/null)" ]]; then
        fail "Uncommitted tracked changes in repository."
        warn "Commit and push to origin/$branch first so remote hosts build the same commit."
        after_build "" 1
        return 1
    fi
    step "Checking origin/$branch on Gitea..."
    if ! git fetch -q origin "$branch" 2>/dev/null || [[ "$(git rev-parse HEAD)" != "$(git rev-parse "origin/$branch" 2>/dev/null)" ]]; then
        fail "HEAD is not pushed to origin/$branch."
        warn "Push the branch to origin first so remote hosts build the same commit."
        after_build "" 1
        return 1
    fi
    return 0
}

# Complete build across Mac, Android, Windows (SSH), and Linux (SSH).
run_complete_all_platforms() {
    local version="$1" branch="$2" remote_tmp
    remote_tmp="$(mktemp -d -t fileapex-remote)"
    trap 'rm -rf "$remote_tmp"' RETURN

    run_remote_windows "$version" "$branch" > "$remote_tmp/windows.log" 2>&1 &
    local win_pid=$!

    run_remote_linux "$version" "$branch" > "$remote_tmp/linux.log" 2>&1 &
    local lin_pid=$!

    local mac_status=0 win_status=0 lin_status=0
    run_gradle_steps \
        "shipGithubRelease packageBothDmg verifyReleaseSigning verifyReleaseApkSigned :verifyGitExecutableScripts" \
        ":composeApp:bundlePlayRelease -PbundleBuild=true" || mac_status=$?

    echo "Waiting for Windows SSH build ($WIN_HOST) to finish..."
    wait "$win_pid" || win_status=$?
    if [[ -f "$remote_tmp/windows.log" ]]; then
        echo ""
        echo "===== Windows build log ($WIN_HOST) ====="
        cat "$remote_tmp/windows.log"
    fi

    echo "Waiting for Linux SSH build ($LIN_HOST) to finish..."
    wait "$lin_pid" || lin_status=$?
    if [[ -f "$remote_tmp/linux.log" ]]; then
        echo ""
        echo "===== Linux build log ($LIN_HOST) ====="
        cat "$remote_tmp/linux.log"
    fi

    if ((mac_status != 0 || win_status != 0 || lin_status != 0)); then
        echo "Complete build status: mac=$mac_status windows=$win_status linux=$lin_status"
        return 1
    fi
}

execute_windows_build() {
    local version branch
    version="$(get_version)"
    branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
    banner
    echo "  ${BOLD}Building:${RESET} ${MAGENTA}Windows (.exe via SSH)${RESET}"
    rule
    verify_git_pushed_for_remote "$branch" || return 1
    run_with_live_view "Windows ($version)" "SSH $WIN_HOST" run_remote_windows "$version" "$branch"
}

execute_linux_build() {
    local version branch
    version="$(get_version)"
    branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
    banner
    echo "  ${BOLD}Building:${RESET} ${MAGENTA}Linux (.deb, .rpm, AppImage)${RESET}"
    rule
    if [[ "$(uname -s)" != "Linux" ]]; then
        verify_git_pushed_for_remote "$branch" || return 1
    fi
    run_with_live_view "Linux ($version)" "SSH $LIN_HOST" run_remote_linux "$version" "$branch"
}

execute_complete_build() {
    local version branch
    version="$(get_version)"
    branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
    banner
    echo "  ${BOLD}Building:${RESET} ${MAGENTA}Complete Build (Mac + Android + Windows + Linux)${RESET}"
    rule
    verify_git_pushed_for_remote "$branch" || return 1
    preflight_play || { after_build "" 1; return 1; }
    run_with_live_view \
        "Complete Build ($version)" \
        "Mac + Android + SSH Windows + SSH Linux" \
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
  -aab, -play       Build Play Store bundle (.aab)
  -quick            Build Quick Test (GitHub APK + Mac Silicon .app)
  -full             Build Full Mac + Android release (DMGs + GitHub APKs + Play AAB)
  -complete, -all   Build Complete release across Mac, Android, Windows (SSH), and Linux (SSH)
  -windows, -win    Build Windows .exe via SSH
  -linux            Build Linux .deb, .rpm, and .AppImage via SSH
  -xpi, -firefox    Build Firefox extension (.xpi)
  -h, --help        Show this help
EOF
}

# ---------- main ----------

if [[ ! -x "$PROJECT_DIR/gradlew" ]]; then
    echo "Error: $PROJECT_DIR/gradlew not found. Set FILEAPEX_DIR to the project root."
    exit 1
fi
cd "$PROJECT_DIR" || exit 1

if (($# > 0)); then
    CLI_MODE=1
    for arg in "$@"; do
        case "${arg#--}" in
            -h|h|-help|help)
                usage
                exit 0
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
                "GitHub APK" "Play Store bundle (AAB)" "Both (GitHub APK + Play AAB)" "← Back"
            case "$MENU_CHOICE" in
                1) execute_build "GitHub APK" ":composeApp:shipGithubRelease" ;;
                2) execute_build "Play Store AAB" ":composeApp:bundlePlayRelease -PbundleBuild=true" ;;
                3) execute_build "Android (GitHub APK + Play AAB)" \
                    ":composeApp:shipGithubRelease" \
                    ":composeApp:bundlePlayRelease -PbundleBuild=true" ;;
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
            clear; echo "Goodbye!"; exit 0
            ;;
    esac
done
