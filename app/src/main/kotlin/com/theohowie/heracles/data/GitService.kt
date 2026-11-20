package com.theohowie.heracles.data

import com.theohowie.heracles.model.DiffStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.RepositoryBuilder
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import java.io.ByteArrayOutputStream
import java.io.File

class GitService {

    data class FileChange(
        val path: String,
        val status: String, // "M" | "A" | "D" | "?"
        val staged: Boolean,
    )

    data class CommitSummary(
        val hash: String,
        val shortHash: String,
        val message: String,
        val author: String,
        val timeMs: Long,
    )

    data class RepoStatus(
        val isRepo: Boolean,
        val branch: String = "",
        val ahead: Int = 0,
        val behind: Int = 0,
        val changes: List<FileChange> = emptyList(),
        val remotes: List<String> = emptyList(),
        val error: String? = null,
    )

    private fun openGit(workspacePath: String): Git? {
        val root = File(workspacePath)
        val dotGit = File(root, ".git")
        if (!dotGit.exists()) return null
        val repo = RepositoryBuilder().setGitDir(dotGit).setWorkTree(root).readEnvironment().build()
        return Git(repo)
    }

    suspend fun status(workspacePath: String): RepoStatus = withContext(Dispatchers.IO) {
        val git = openGit(workspacePath) ?: return@withContext RepoStatus(isRepo = false)
        git.use { g ->
            runCatching {
                val st = g.status().call()
                val branch = g.repository.branch ?: "HEAD"
                val tracking = org.eclipse.jgit.lib.BranchTrackingStatus.of(g.repository, branch)
                val changes = mutableListOf<FileChange>()
                st.added.forEach { changes += FileChange(it, "A", true) }
                st.changed.forEach { changes += FileChange(it, "M", true) }
                st.removed.forEach { changes += FileChange(it, "D", true) }
                st.modified.forEach { changes += FileChange(it, "M", false) }
                st.missing.forEach { changes += FileChange(it, "D", false) }
                st.untracked.forEach { changes += FileChange(it, "?", false) }
                val remotes = g.remoteList().call().map { "${it.name}: ${it.urIs.firstOrNull()}" }
                RepoStatus(
                    isRepo = true,
                    branch = branch,
                    ahead = tracking?.aheadCount ?: 0,
                    behind = tracking?.behindCount ?: 0,
                    changes = changes.sortedBy { it.path },
                    remotes = remotes,
                )
            }.getOrElse {
                RepoStatus(isRepo = true, error = it.message ?: "فشل قراءة حالة Git")
            }
        }
    }

    suspend fun initRepo(workspacePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Git.init().setDirectory(File(workspacePath)).call().close()
        }
    }

    suspend fun cloneRepo(url: String, targetDir: String, token: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(File(targetDir))
                if (!token.isNullOrBlank()) {
                    cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider("x-access-token", token))
                }
                cmd.call().close()
            }
        }

    suspend fun branches(workspacePath: String): List<String> = withContext(Dispatchers.IO) {
        val git = openGit(workspacePath) ?: return@withContext emptyList()
        git.use { g ->
            runCatching {
                g.branchList().call().map { it.name.removePrefix("refs/heads/") }
            }.getOrDefault(emptyList())
        }
    }

    suspend fun checkout(workspacePath: String, branch: String, create: Boolean = false): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val git = openGit(workspacePath) ?: error("مساحة العمل الحالية ليست مستودع Git")
                git.use { g ->
                    g.checkout().setName(branch).setCreateBranch(create).call()
                }
                Unit
            }
        }

    suspend fun log(workspacePath: String, maxCount: Int = 20): List<CommitSummary> =
        withContext(Dispatchers.IO) {
            val git = openGit(workspacePath) ?: return@withContext emptyList()
            git.use { g ->
                runCatching {
                    g.log().setMaxCount(maxCount).call().map { c ->
                        CommitSummary(
                            hash = c.name,
                            shortHash = c.name.take(7),
                            message = c.shortMessage,
                            author = c.authorIdent.name,
                            timeMs = c.commitTime.toLong() * 1000L,
                        )
                    }
                }.getOrDefault(emptyList())
            }
        }

    suspend fun diff(workspacePath: String, filePath: String? = null): String =
        withContext(Dispatchers.IO) {
            val git = openGit(workspacePath) ?: return@withContext ""
            git.use { g ->
                runCatching {
                    val baos = ByteArrayOutputStream()
                    DiffFormatter(baos).use { fmt ->
                        fmt.setRepository(g.repository)
                        if (filePath != null) {
                            fmt.pathFilter = org.eclipse.jgit.treewalk.filter.PathFilter.create(filePath)
                        }
                        val headId = g.repository.resolve("HEAD^{tree}")
                        if (headId != null) {
                            val reader = g.repository.newObjectReader()
                            val oldTree = CanonicalTreeParser().apply { reset(reader, headId) }
                            val entries = g.diff().setOldTree(oldTree).call()
                            entries.forEach { fmt.format(it) }
                        } else {
                            g.diff().call().forEach { fmt.format(it) }
                        }
                    }
                    baos.toString("UTF-8")
                }.getOrDefault("")
            }
        }

    suspend fun stageAll(workspacePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val git = openGit(workspacePath) ?: error("مساحة العمل الحالية ليست مستودع Git")
            git.use { g ->
                g.add().addFilepattern(".").call()
                g.add().setUpdate(true).addFilepattern(".").call()
            }
            Unit
        }
    }

    suspend fun commit(workspacePath: String, message: String, authorName: String = "Heracles"): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val git = openGit(workspacePath) ?: error("مساحة العمل الحالية ليست مستودع Git")
                git.use { g ->
                    val rev = g.commit()
                        .setMessage(message)
                        .setAuthor(authorName, "heracles@local")
                        .call()
                    rev.name.take(7)
                }
            }
        }

    suspend fun push(workspacePath: String, token: String?): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val git = openGit(workspacePath) ?: error("مساحة العمل الحالية ليست مستودع Git")
            git.use { g ->
                val cmd = g.push()
                if (!token.isNullOrBlank()) {
                    cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider("x-access-token", token))
                }
                val res = cmd.call()
                res.joinToString("\n") { it.messages.ifBlank { "pushed" } }.ifBlank { "اكتمل الدفع" }
            }
        }
    }

    suspend fun pull(workspacePath: String, token: String?): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val git = openGit(workspacePath) ?: error("مساحة العمل الحالية ليست مستودع Git")
            git.use { g ->
                val cmd = g.pull()
                if (!token.isNullOrBlank()) {
                    cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider("x-access-token", token))
                }
                val res = cmd.call()
                if (res.isSuccessful) "اكتمل السحب" else "تعارض في السحب أو دمج غير مكتمل"
            }
        }
    }

    suspend fun showCommit(workspacePath: String, hash: String): String = withContext(Dispatchers.IO) {
        val git = openGit(workspacePath) ?: return@withContext ""
        git.use { g ->
            runCatching {
                val objId = g.repository.resolve(hash) ?: return@runCatching "تعذر العثور على الإيداع $hash"
                RevWalk(g.repository).use { walk ->
                    val commit = walk.parseCommit(objId)
                    "${commit.name.take(7)} by ${commit.authorIdent.name}\n${commit.fullMessage}"
                }
            }.getOrDefault("")
        }
    }

    companion object {
        fun computeDiffStats(before: String, after: String): DiffStat {
            val a = before.lines().toSet()
            val b = after.lines().toSet()
            return DiffStat(
                added = b.count { it !in a },
                removed = a.count { it !in b },
            )
        }
    }
}
