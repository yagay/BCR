/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Recover metadata from BCR's currently configured filename template when
 * sidecar JSON writing is disabled. Do not invent data from arbitrary names.
 */
package com.chiller3.bcr.manager

import com.chiller3.bcr.template.Template

internal class RecordingTemplateFallback(templates: List<Template>) {
    private data class Part(val literal: String? = null, val variable: String? = null)
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
            val source = parts.joinToString("") { p ->
                p.literal?.let(Regex::escape) ?: "(.+?)"
            }
            val compiled = runCatching { Regex("^$source$") }.getOrNull()
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
                is Template.VariableRef -> listOf(listOf(Part(variable = clause.name)))
                is Template.Fallback -> clause.choices.flatMap(::expand)
            }
            variants = variants.flatMap { base ->
                choices.map { choice -> base + choice }
            }.take(128)
        }
        return variants
    }
}
