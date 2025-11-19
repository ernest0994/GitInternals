package gitinternals

import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path
import kotlin.io.path.readBytes
import java.util.zip.*

fun main() {
    println("Enter .git directory location:")
    val directory = readln()

    println("Enter command:")
    val command = readln()

    when (command) {
        "list-branches" -> {
            val branchNamesPath = Path("$directory${File.separator}refs${File.separator}heads")
            val currentBranchPath = Path("$directory${File.separator}HEAD")

            val headContent = currentBranchPath.toFile().readText().trim()
            val currentBranch = headContent.removePrefix("ref: refs/heads/")

            val branchFiles = branchNamesPath.toFile().listFiles()

            branchFiles?.sortedBy { it.name }?.forEach { file ->
                if (file.isFile) {
                    if (file.name == currentBranch) {
                        println("* ${file.name}")
                    } else {
                        println("  ${file.name}")
                    }
                }
            }
        }

        "cat-file" -> {
            println("Enter git object hash:")
            val hash = readln()

            val path = Path(
                "$directory${File.separatorChar}objects${File.separatorChar}${
                    hash.take(2)
                }${File.separatorChar}${hash.substring(2)}"
            )

            val file = path.readBytes()

            InflaterInputStream(ByteArrayInputStream(file)).use {
                val inflatedContentBytes = it.readAllBytes()
                val inflatedContent = String(inflatedContentBytes)

                val headerArray = inflatedContent.split('\u0000')[0].split(' ')
                val content = inflatedContent.split('\u0000')[1]

                val fileType = headerArray[0].uppercase()

                var isComment = false

                println("*$fileType*")

                when (fileType) {
                    "COMMIT" -> {
                        val lines = content.lines()

                        lines.any { line -> line.startsWith("tree") }
                            .let {
                                lines.isNotEmpty()
                                    .let {
                                        lines[0]
                                            .removePrefix("tree ")
                                            .also { line -> println("tree: $line") }
                                    }
                            }

                        lines.filter { line -> line.startsWith("parent") }.let { filterLines ->
                            if (filterLines.isNotEmpty()) {
                                filterLines.joinToString(" | ") { line -> line.removePrefix("parent ") }
                                    .also { parents -> println("parents: $parents") }
                            }
                        }

                        for (line in lines) {

                            if (line.startsWith("tree") || line.startsWith("parent") || (line.isEmpty() && !isComment)) continue

                            if (line.startsWith("author")) {
                                val parts = line.split(" ")
                                val timestamp = parts[parts.size - 2].toLong() // "1585491500"
                                val offsetStr = parts.last() // "+0300"
                                val email = line.substringAfter("<").substringBefore(">") // "mr.smith@matrix"

                                // Convert offset to ZoneOffset (e.g., "+0300" -> "+03:00")
                                val offset = ZoneOffset.of(offsetStr.substring(0, 3) + ":" + offsetStr.substring(3))

                                // Convert timestamp to formatted date
                                val instant = Instant.ofEpochSecond(timestamp)
                                val formattedDate =
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
                                        .format(instant.atOffset(offset))

                                println("author: ${parts[1]} $email original timestamp: $formattedDate")

                                continue
                            }

                            if (line.startsWith("committer")) {
                                val parts = line.split(" ")
                                val timestamp = parts[parts.size - 2].toLong()
                                val offsetStr = parts.last() // "+0300"
                                val email = line.substringAfter("<").substringBefore(">")

                                val offset = ZoneOffset.of(offsetStr.substring(0, 3) + ":" + offsetStr.substring(3))
                                val instant = Instant.ofEpochSecond(timestamp)
                                val formattedDate =
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
                                        .format(instant.atOffset(offset))

                                println("committer: ${parts[1]} $email commit timestamp: $formattedDate")

                                continue
                            }

                            if (isComment) {
                                println(line)
                            } else {
                                println("commit message:")
                                println(line)
                                isComment = true
                            }
                        }
                    }

                    "BLOB" -> {
                        println(content)
                    }

                    "TREE" -> {
                        val nullIndex = inflatedContentBytes.indexOfFirst { it == 0.toByte() }
                        val header = String(inflatedContentBytes.sliceArray(0 until nullIndex))
                        val contentBytes =
                            inflatedContentBytes.sliceArray(nullIndex + 1 until inflatedContentBytes.size)

                        var i = 0
                        val treeList = mutableListOf<Tree>()

                        while (i < contentBytes.size) {
                            // Read permission (ASCII characters until space)
                            val permissionStart = i
                            while (contentBytes[i] != ' '.code.toByte()) i++
                            val permission = String(contentBytes.sliceArray(permissionStart until i))
                            i++ // skip space

                            // Read filename (ASCII characters until null)
                            val filenameStart = i
                            while (contentBytes[i] != 0.toByte()) i++
                            val filename = String(contentBytes.sliceArray(filenameStart until i))
                            i++ // skip null

                            // Read 20-byte SHA-1 hash
                            val sha1Bytes = contentBytes.sliceArray(i until i + 20)
                            val sha1Hex = sha1Bytes.joinToString("") {
                                it.toUByte().toString(16).padStart(2, '0')
                            }
                            i += 20

                            treeList.add(Tree(permission, filename, sha1Hex))
                        }

                        treeList.forEach { tree -> println("${tree.permissionNumber} ${tree.hexBytes.lowercase()} ${tree.fileName}") }
                    }

                    else -> {
                        println(content)
                    }
                }
            }
        }

        "log" -> {
            println("Enter branch name:")
            val branchName = readln()

            Path("$directory${File.separator}refs${File.separator}heads${File.separator}$branchName").let { path ->
                path.readBytes().let { bytes ->
                    val commitHash = removeNewlines(String(bytes))
                    printCommitLogsWithParents(commitHash, directory)

                    commits.forEachIndexed { index, commit ->
                        if (index != 0) println()
                        val mergedTag = if (commit.mergedTag) " (merged)" else ""
                        println("Commit: ${commit.hash}$mergedTag")
                        println("${commit.author} ${commit.email} commit timestamp: ${commit.date}")
                        println(commit.comment)
                    }
                }
            }
        }

        "commit-tree" -> {
            println("Enter commit-hash:")
            val commitHash = readln()

            // Get the tree hash from the commit
            val treeHash = getTreeHashFromCommit(commitHash, directory)

            // Collect and print all paths from the tree
            val allPaths = collectAllPaths(treeHash, directory, "")
            allPaths.sorted().forEach { println(it) }
        }
        else -> println("Unknown command")
    }
}


class Tree(val permissionNumber: String, val fileName: String, val hexBytes: String)
data class Commit(val hash: String, val author: String, val email: String, val date: String, val comment: String, val mergedTag: Boolean = false)

val commits = mutableListOf<Commit>()
val visitedCommitHashes = mutableSetOf<String>()
fun removeNewlines(text: String): String {
    return text.replace("\r", "").replace("\n", "")
}

fun printCommitLogsWithParents(commitHash: String, directory: String, isDirectMergedParent: Boolean = false) {

    if (!visitedCommitHashes.add(commitHash)) return

    val path = Path(
        "$directory${File.separatorChar}objects${File.separatorChar}${
            commitHash.take(2)
        }${File.separatorChar}${commitHash.substring(2)}"
    )

    val file = path.readBytes()

    InflaterInputStream(ByteArrayInputStream(file)).use {
        val inflatedContentBytes = it.readAllBytes()
        val inflatedContent = String(inflatedContentBytes)

        val headerArray = inflatedContent.split('\u0000')[0].split(' ')
        val content = inflatedContent.split('\u0000')[1]

        val fileType = headerArray[0].uppercase()

        val lines = content.lines()

        val parentCommits = lines
            .filter { line -> line.startsWith("parent") }
            .map { line -> line.removePrefix("parent ") }

        val (author, email, date) = lines
            .first { line -> line.startsWith("committer") }
            .let { line ->
                val emailStart = line.indexOf('<')
                val emailEnd = line.indexOf('>')

                val email = line.substring(emailStart + 1, emailEnd)

                val prefix = "committer "
                val author = line.substring(prefix.length, emailStart).trim()

                val afterEmail = line.substring(emailEnd + 1).trim().split(" ")
                val timestamp = afterEmail[0].toLong()
                val offsetStr = afterEmail[1] // e.g. "-0500"

                val offset = ZoneOffset.of(offsetStr.substring(0, 3) + ":" + offsetStr.substring(3))
                val instant = Instant.ofEpochSecond(timestamp)
                val formattedDate =
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
                        .format(instant.atOffset(offset))

                Triple(author, email, formattedDate)
            }

        val comment: String = run {
            val messageStartIndex = lines.indexOfFirst { it.isEmpty() }
            if (messageStartIndex >= 0 && messageStartIndex < lines.size - 1) {
                lines
                    .drop(messageStartIndex + 1)   // everything after the first blank line
                    .dropLastWhile { it.isEmpty() }   // remove trailing empty lines
                    .joinToString("\n")
            } else {
                ""
            }
        }

        val currentCommit = Commit(commitHash, author, email, date, comment, isDirectMergedParent)

        commits.add(currentCommit)

        if (!isDirectMergedParent && parentCommits.isNotEmpty()) {
            // For second and later parents, print them directly FIRST (before recursing into first parent)
            for (i in 1 until parentCommits.size) {
                printCommitLogsWithParents(parentCommits[i], directory, isDirectMergedParent = true)
            }

            // Then follow the first parent (main line)
            printCommitLogsWithParents(parentCommits[0], directory, isDirectMergedParent = false)
        }
    }
}

/**
 * Extracts the tree hash from a commit object
 */
fun getTreeHashFromCommit(commitHash: String, directory: String): String {
    val path = Path(
        "$directory${File.separatorChar}objects${File.separatorChar}${
            commitHash.take(2)
        }${File.separatorChar}${commitHash.substring(2)}"
    )

    val file = path.readBytes()

    return InflaterInputStream(ByteArrayInputStream(file)).use {
        val inflatedContentBytes = it.readAllBytes()
        val inflatedContent = String(inflatedContentBytes)

        // Split by null byte to get content
        val content = inflatedContent.split('\u0000')[1]

        // Extract tree hash from first line
        content.lines()
            .first { line -> line.startsWith("tree") }
            .removePrefix("tree ")
            .trim()
    }
}

/**
 * Recursively collects all file paths in the tree
 */
fun collectAllPaths(treeHash: String, directory: String, prefix: String): List<String> {
    val trees = parseTreeObject(treeHash, directory)
    val paths = mutableListOf<String>()

    trees.forEach { tree ->
        val fullPath = if (prefix.isEmpty()) tree.fileName else "$prefix/${tree.fileName}"

        // If this entry is a tree (subdirectory), recursively collect its contents
        if (tree.permissionNumber.startsWith("40000")) {
            paths.addAll(collectAllPaths(tree.hexBytes, directory, fullPath))
        } else {
            // It's a file (blob), add it to the list
            paths.add(fullPath)
        }
    }

    return paths
}

/**
 * Parses a tree object and returns list of Tree entries
 */
fun parseTreeObject(treeHash: String, directory: String): List<Tree> {
    val path = Path(
        "$directory${File.separatorChar}objects${File.separatorChar}${
            treeHash.take(2)
        }${File.separatorChar}${treeHash.substring(2)}"
    )

    val file = path.readBytes()

    return InflaterInputStream(ByteArrayInputStream(file)).use {
        val inflatedContentBytes = it.readAllBytes()

        val nullIndex = inflatedContentBytes.indexOfFirst { it == 0.toByte() }
        val contentBytes = inflatedContentBytes.sliceArray(nullIndex + 1 until inflatedContentBytes.size)

        val treeList = mutableListOf<Tree>()
        var i = 0

        while (i < contentBytes.size) {
            // Read permission (ASCII characters until space)
            val permissionStart = i
            while (contentBytes[i] != ' '.code.toByte()) i++
            val permission = String(contentBytes.sliceArray(permissionStart until i))
            i++ // skip space

            // Read filename (ASCII characters until null)
            val filenameStart = i
            while (contentBytes[i] != 0.toByte()) i++
            val filename = String(contentBytes.sliceArray(filenameStart until i))
            i++ // skip null

            // Read 20-byte SHA-1 hash
            val sha1Bytes = contentBytes.sliceArray(i until i + 20)
            val sha1Hex = sha1Bytes.joinToString("") {
                it.toUByte().toString(16).padStart(2, '0')
            }
            i += 20

            treeList.add(Tree(permission, filename, sha1Hex))
        }

        treeList
    }
}