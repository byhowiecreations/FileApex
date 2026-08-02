# AI Instructions for FileApex Project

**App Purpose:** FileApex is a local-first P2P file explorer for Android and Desktop. Prioritize usability, performance, and data integrity.

1.  **Code Quality:** All code edits must adhere to **Kotlin development best practices**. Avoid shortcuts or quick fixes that could lead to performance issues like system slow-downs, **Out-of-Memory (OOM)** errors or race conditions.
2.  **Centralized Logic:** **Avoid redundant services and logic throughout the entire project.** The goal is to prevent duplicate code and ensure a **single source of truth** for each specific function. Key actions like data synchronization, complex calculations, or API calls should be handled by a single, **domain-specific** central source, such as a dedicated repository or manager class. Other components should then consume the results from that source. Do not consolidate all of these into a single, monolithic class.
3.  **Compilation & Shipping:** After all code edits, run the full ship — not compile-only checks. Use `source signing.local.env && unset JAVA_HOME && ./gradlew copyAllBuilds -x copyCurrentBuilds` so debug APK, release APK, Mac `.app`, and DMG land in `current/` (see also §13). Use `assembleDebug` only as part of that pipeline, never as the final step.
4.  **Finalization:** Once all compile errors are resolved, if I did not tell you the build version we are working on at the beginning, you must ask me for the new version number before running `assembleRelease`. The version number should be the first edit you do when you are given that at the beginning of the project.
5.  **Project Scope:** Do not access or modify files in other project folders.
6.  **Reporting:** Any reports generated must be saved in the `docs` folder located in the project's root directory. Do not give me long outputs on screen, they go in the file. I do not need pre-fix reports at all, only after everything is confirmed fixed. Screen summaries should be short, exact and to the point.
7.  **Suppress:** Do not just suppress deprecated warning messages to take the shortcut, fix them properly. I don't want any warnings left unresolved.
8.  **Restrictive Commands:** Do not use commands with restrictive output (such as 2>&1 | tail). It just creates situations that often need you to run the same command several times with different variations of it to get the results you need. You will NEVER run `adb logcat -c`. That is absolutely forbidden!
9.  **Screenshots:** If I ever mention an image or screenshot for bugs and issues you can locate the file in the root of project inside BUGS folder.
10. **Strict UTC Storage:** All database timestamps MUST be stored as Long (Epoch Milliseconds) or Instant. LocalDateTime should never be used in a database entity.
11. **Dynamic Localization:** Timezone offsets (-4 vs -5) MUST NEVER be hardcoded. Always use ZoneId.of("America/New_York") or ZoneId.systemDefault() to ensure Daylight Saving Time transitions are handled by the system TZDB.
12. **Hierarchy Isolation:** ViewModels are prohibited from performing time-math. They must consume pre-localized strings or ZonedDateTime objects from the TimeUtils or Repository layers.
13. **/Applications:** Ship builds (debug, release, dmg, and app mac file) to `current/` only.
14. **Signing Packages (Android):**
    * All `.jks` keystore files for signing Android packages are located dynamically at: `~/AndroidStudioProjects/signed_files/{project_name}/`
    * Do not hardcode credentials. Retrieve the required signing configuration directly from the system environment variables:
      * Keystore Password: `$KEYSTORE_PASSWORD`
      * Key Password: `$KEY_PASSWORD`
      * Key Alias: `$KEY_ALIAS`
    * Use these system exports directly when configuring or troubleshooting Gradle signing scripts (`signingConfigs`).

