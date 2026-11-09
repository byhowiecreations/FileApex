package com.fileapex.update

/**
 * Shell functions the Mac update script uses to keep the signing identity. A valid signature is not enough: any
 * self-signed app passes `codesign --verify`. The incoming app must have the installed app's designated requirement,
 * unless the installed app is ad-hoc (its requirement is a build hash, so there is no identity to keep).
 */
internal fun macUpdateIdentityGuardShell(): String = """
installed_identity() { codesign -dr - "§1" 2>&1 | sed -n 's/^#* *designated => /designated => /p' | head -1; }
identity_allowed() {
  local installed="§1" incoming="§2"
  case "§installed" in
    ""|*"designated => cdhash "*) return 0 ;;
  esac
  [[ -n "§incoming" && "§installed" == "§incoming" ]]
}
""".trimIndent().replace('§', '$')
