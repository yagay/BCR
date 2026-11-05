/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Recover metadata from BCR's currently configured filename template when
 * sidecar JSON writing is disabled. Do not invent data from arbitrary names.
 */
package com.chiller3.bcr.manager

import com.chiller3.bcr.template.Template

internal class RecordingTemplateFallback(templates: List<Template>) {
    private data class Part(
        val literal: String? = null,
        val variable: String? = null,
        val argument: String? = null,
    )
    private data class Pattern(
        val regex: Regex,
        val variableNames: List<String>,
        val score: Int,
    )

    private val patterns: List<Pattern> = templates.flatMap { template ->
        expand(template.ast).mapNotNull { parts ->
            // Capturing two adjacent variables is ambiguous: prefer leaving
            // the value unknown over inventing a caller or phone number.
            if (parts.zipWithNext().any { (a, b) ->
                    a.variable != null && b.variable != null
                }) return@mapNotNull null
            val names = parts.mapNotNull { it.variable }
            val fragments = mutableListOf<String>()
            for (part in parts) {
                val fragment = part.literal?.let(Regex::escape)
                    ?: capturePattern(part.variable.orEmpty(), part.argument)
                    ?: return@mapNotNull null
                fragments.add(fragment)
            }
            val source = fragments.joinToString("")
            val compiled = runCatching { Regex("^$source$", RegexOption.IGNORE_CASE) }.getOrNull()
                ?: return@mapNotNull null
            Pattern(compiled, names, names.size * 100 + parts.sumOf { it.literal?.length ?: 0 })
        }
    }.sortedByDescending { it.score }

    /** relativePath is the output path without the file extension. */
    fun extract(relativePath: String): Map<String, String> {
        for (pattern in patterns) {
            val match = pattern.regex.matchEntire(relativePath) ?: continue
            return pattern.variableNames.mapIndexedNotNull { index, name ->
                val value = match.groupValues[index + 1].trim()
                if (value.isNotBlank()) name to value else null
            }.toMap()
        }
        return emptyMap()
    }

    private fun expand(template: Template.TemplateString): List<List<Part>> {
        var variants: List<List<Part>> = listOf(emptyList())
        for (clause in template.clauses) {
            val choices: List<List<Part>> = when (clause) {
                is Template.StringLiteral -> listOf(listOf(Part(literal = clause.value)))
                is Template.VariableRef -> listOf(listOf(Part(variable = clause.name, argument = clause.arg)))
                is Template.Fallback -> clause.choices.flatMap(::expand)
            }
            variants = variants.flatMap { base ->
                choices.map { choice -> base + choice }
            }.take(128)
        }
        return variants
    }

    /**
     * Type-aware captures prevent the date variable from consuming directory
     * separators or later fields. Unsupported datetime patterns are not
     * inverted, because guessing a contact's identity would be unsafe.
     */
    private fun capturePattern(name: String, argument: String?): String? = when (name) {
        "date" -> dateCapture(argument)
        "direction" -> "(in|out|conference)"
        "sim_slot" -> "([0-9]+)"
        "phone_number" -> "([+0-9() .,\\-]+?)"
        "contact_name", "caller_name", "call_log_name" -> "(.+?)"
        else -> null
    }

    private fun dateCapture(pattern: String?): String? {
        if (pattern == null) {
            return "(\\d{8}_\\d{6}(?:\\.\\d{1,9})?(?:[+-]\\d{4,6})?)"
        }
        if (pattern == "unix_s") return "(\\d{10,12})"
        if (pattern == "unix_ms") return "(\\d{13,16})"
        // Parse numeric DateTimeFormatter tokens, preserving literal separators.
        val result = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val ch = pattern[i]
            if (ch == '\'') {
                val closing = pattern.indexOf('\'', i + 1)
                if (closing < 0) return null
                result.append(Regex.escape(pattern.substring(i + 1, closing)))
                i = closing + 1
                continue
            }
            if (!ch.isLetter()) {
                result.append(Regex.escape(ch.toString()))
                i++
                continue
            }
            var end = i + 1
            while (end < pattern.length && pattern[end] == ch) end++
            val count = end - i
            val size = when (ch) {
                'y', 'u' -> if (count == 2) 2 else 4
                'M', 'd', 'H', 'h', 'm', 's' -> if (count <= 2) count else return null
                'S' -> count
                else -> return null
            }
            result.append("\\d{").append(size).append("}")
            i = end
        }
        return "(" + result + ")"
    }
}
