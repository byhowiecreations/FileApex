package com.fileapex.notice

import com.fileapex.update.FileApexSemVer

internal object NoticeVersionRange {
    /** `from` and `to` are inclusive, `below` is exclusive; a missing bound is open. An unparseable running version matches nothing. */
    fun contains(version: FileApexSemVer?, from: FileApexSemVer?, to: FileApexSemVer?, below: FileApexSemVer?): Boolean {
        if (version == null) return false
        if (from != null && version < from) return false
        if (to != null && version > to) return false
        if (below != null && version >= below) return false
        return true
    }
}
