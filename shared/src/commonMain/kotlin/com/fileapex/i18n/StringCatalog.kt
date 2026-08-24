package com.fileapex.i18n

internal data class PluralForms(
    val zero: String? = null,
    val one: String? = null,
    val two: String? = null,
    val few: String? = null,
    val many: String? = null,
    val other: String
)

internal class StringCatalog(
    private val strings: Map<String, String>,
    private val plurals: Map<String, PluralForms>
) {
    fun string(key: String): String? = strings[key]

    fun snapshotStrings(): Map<String, String> = strings

    fun snapshotPlurals(): Map<String, PluralForms> = plurals

    fun plural(key: String, count: Int, locale: AppLocale): String? {
        val forms = plurals[key] ?: return null
        val quantity = pluralQuantity(count, locale)
        return when (quantity) {
            "zero" -> forms.zero
            "one" -> forms.one
            "two" -> forms.two
            "few" -> forms.few
            "many" -> forms.many
            else -> null
        } ?: forms.other
    }
}

internal fun pluralQuantity(count: Int, locale: AppLocale): String {
    return when (locale) {
        AppLocale.ZH_HANS -> "other"
        AppLocale.ES, AppLocale.EN -> if (count == 1) "one" else "other"
    }
}

internal fun formatTemplate(template: String, args: Array<out Any>): String {
    if (args.isEmpty()) return template
    var out = template
    args.forEachIndexed { index, arg ->
        val value = arg.toString()
        out = out.replace("%${index + 1}\$s", value)
        out = out.replace("%${index + 1}\$d", value)
    }
    if (args.size == 1) {
        out = out.replace("%s", args[0].toString()).replace("%d", args[0].toString())
    }
    return out
}

internal fun parseStringXml(xml: String): StringCatalog {
    val strings = linkedMapOf<String, String>()
    val stringRe = Regex("""<string\s+name="([^"]+)">([\s\S]*?)</string>""")
    stringRe.findAll(xml).forEach { match ->
        strings[match.groupValues[1]] = unescapeXml(match.groupValues[2].trim())
    }
    val plurals = linkedMapOf<String, PluralForms>()
    val pluralRe = Regex("""<plurals\s+name="([^"]+)">([\s\S]*?)</plurals>""")
    val itemRe = Regex("""<item\s+quantity="([^"]+)">([\s\S]*?)</item>""")
    pluralRe.findAll(xml).forEach { match ->
        val items = mutableMapOf<String, String>()
        itemRe.findAll(match.groupValues[2]).forEach { item ->
            items[item.groupValues[1]] = unescapeXml(item.groupValues[2].trim())
        }
        plurals[match.groupValues[1]] = PluralForms(
            zero = items["zero"],
            one = items["one"],
            two = items["two"],
            few = items["few"],
            many = items["many"],
            other = items["other"] ?: items.values.firstOrNull().orEmpty()
        )
    }
    return StringCatalog(strings, plurals)
}

private fun unescapeXml(raw: String): String =
    raw.replace("\\n", "\n")
        .replace("\\'", "'")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
