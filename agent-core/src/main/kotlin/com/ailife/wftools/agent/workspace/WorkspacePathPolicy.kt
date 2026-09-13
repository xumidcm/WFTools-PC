package com.ailife.wftools.agent.workspace

import java.nio.file.Files
import java.nio.file.Path

class WorkspacePathViolation(message: String) : IllegalArgumentException(message)

object WorkspacePathPolicy {
    fun sanitizeRelative(path: String): String {
        if (path.isEmpty() || path.indexOf('\u0000') >= 0) {
            throw WorkspacePathViolation("Path is empty or contains NUL")
        }
        val normalizedSeparators = path.replace('\\', '/')
        if (normalizedSeparators.startsWith('/') ||
            Regex("^[A-Za-z]:($|/)").containsMatchIn(normalizedSeparators)
        ) {
            throw WorkspacePathViolation("Absolute paths are not allowed")
        }

        val parts = normalizedSeparators.split('/')
        val clean = ArrayList<String>(parts.size)
        for (part in parts) {
            when (part) {
                "", "." -> Unit
                ".." -> throw WorkspacePathViolation("Path traversal is not allowed")
                else -> {
                    if (part.any { it.code < 0x20 }) {
                        throw WorkspacePathViolation("Control characters are not allowed")
                    }
                    clean += part
                }
            }
        }
        if (clean.isEmpty()) throw WorkspacePathViolation("Path resolves to workspace root")
        return clean.joinToString("/")
    }

    fun resolveInside(root: Path, relativePath: String): Path {
        val clean = sanitizeRelative(relativePath)
        val absoluteRoot = root.toAbsolutePath().normalize()
        val candidate = absoluteRoot.resolve(clean).normalize()
        if (!candidate.startsWith(absoluteRoot)) {
            throw WorkspacePathViolation("Resolved path escapes workspace")
        }

        // Existing symlinks are resolved before a write so a managed path cannot
        // redirect to Android app data or another workspace.
        var existing: Path? = candidate
        while (existing != null && !Files.exists(existing)) existing = existing.parent
        if (existing != null) {
            val realRoot = if (Files.exists(absoluteRoot)) absoluteRoot.toRealPath() else absoluteRoot
            val realExisting = existing.toRealPath()
            if (!realExisting.startsWith(realRoot)) {
                throw WorkspacePathViolation("Symlink escapes workspace")
            }
        }
        return candidate
    }
}


