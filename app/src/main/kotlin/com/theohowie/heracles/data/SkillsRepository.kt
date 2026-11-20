package com.theohowie.heracles.data

import com.theohowie.heracles.bridge.SkillsBridge

class SkillsRepository(private val bridge: SkillsBridge) {

    data class SkillEntry(
        val id: String,
        val name: String,
        val description: String,
        val instructions: String,
        val path: String?,
        val isBuiltIn: Boolean,
        val enabled: Boolean,
    )

    data class LoadResult(
        val skills: List<SkillEntry>,
        val errors: List<String>,
    )

    private val builtInSkills = listOf(
        SkillEntry(
            id = "builtin-android-build",
            name = "android-gradle-diagnose",
            description = "إرشادات فحص بناء Android Gradle وتعارضات الاعتماديات وتصريحات الأذونات.",
            instructions = "عند فحص أخطاء Gradle، حدد الاعتماديات المفقودة وتوافق إصدار SDK وتصريحات AndroidManifest أولاً.",
            path = null,
            isBuiltIn = true,
            enabled = true,
        ),
        SkillEntry(
            id = "builtin-code-review",
            name = "safe-edit-workflow",
            description = "قراءة الملف عبر read_file قبل التعديل، وتفضيل التعديلات الموضعية عبر edit / apply_patch بدلاً من إعادة كتابة الملف كاملاً.",
            instructions = "اقرأ الملف المستهدف قبل تعديل الشيفرة وتحقق من السياق المطابق؛ اكتفِ بتعديلات دقيقة للملفات الكبيرة ثم نفّذ التحقق.",
            path = null,
            isBuiltIn = true,
            enabled = true,
        ),
    )

    fun loadAll(workspacePath: String): LoadResult {
        val raw = bridge.list(workspacePath)
        val enabledMap = raw.enabled
        val builtIns = builtInSkills.map { b ->
            b.copy(enabled = enabledMap[b.name] ?: b.enabled)
        }
        val custom = raw.entries.map { item ->
            val parsed = parseFrontMatter(item.raw)
            val skillName = parsed.name.ifBlank {
                item.path.substringBeforeLast("/SKILL.md").substringAfterLast('/')
            }
            SkillEntry(
                id = item.path,
                name = skillName,
                description = parsed.description.ifBlank { "مهارة مخصصة لمساحة العمل" },
                instructions = parsed.body,
                path = item.path,
                isBuiltIn = false,
                enabled = enabledMap[skillName] ?: true,
            )
        }
        return LoadResult(skills = builtIns + custom, errors = raw.errors)
    }

    fun setEnabled(name: String, enabled: Boolean) {
        bridge.setEnabled(name, enabled)
    }

    fun install(workspacePath: String, source: String): SkillsBridge.InstallResult =
        bridge.install(workspacePath, source)

    fun remove(workspacePath: String, skill: SkillEntry): Boolean {
        if (skill.isBuiltIn) return false
        return bridge.remove(workspacePath, skill.name, skill.path)
    }

    fun buildPromptSection(workspacePath: String): String {
        val active = loadAll(workspacePath).skills.filter { it.enabled }
        if (active.isEmpty()) return ""
        return buildString {
            appendLine("## Active Skills")
            active.forEach { s ->
                appendLine("### ${s.name}: ${s.description}")
                if (s.instructions.isNotBlank()) appendLine(s.instructions.take(1500))
            }
        }
    }

    private data class ParsedSkill(val name: String, val description: String, val body: String)

    private fun parseFrontMatter(raw: String): ParsedSkill {
        val trimmed = raw.trimStart()
        if (!trimmed.startsWith("---")) return ParsedSkill("", "", raw.trim())
        val endIdx = trimmed.indexOf("\n---", startIndex = 3)
        if (endIdx < 0) return ParsedSkill("", "", raw.trim())
        val front = trimmed.substring(3, endIdx)
        val body = trimmed.substring(endIdx + 4).trim()
        var name = ""
        var desc = ""
        front.lineSequence().forEach { line ->
            val clean = line.trim()
            when {
                clean.startsWith("name:") -> name = clean.substringAfter(':').trim().trim('"', '\'')
                clean.startsWith("description:") -> desc = clean.substringAfter(':').trim().trim('"', '\'')
            }
        }
        return ParsedSkill(name, desc, body)
    }
}
