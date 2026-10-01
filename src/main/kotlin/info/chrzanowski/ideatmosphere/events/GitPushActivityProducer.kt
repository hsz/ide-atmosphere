package info.chrzanowski.ideatmosphere.events

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import git4idea.push.GitPushListener
import git4idea.push.GitPushRepoResult
import git4idea.repo.GitRepository
import info.chrzanowski.ideatmosphere.AtmosphereManager

/**
 * Publishes SUCCESS when a Git push lands and FAILURE when the remote rejects it or the push errors out; pushes that
 * changed nothing stay silent. Registered as a project listener on [GitPushListener.TOPIC] through the optional
 * `Git4Idea` descriptor, so the plugin loads without the Git integration.
 */
class GitPushActivityProducer(private val project: Project) : GitPushListener {

    override fun onCompleted(repository: GitRepository, pushResult: GitPushRepoResult) {
        onCompleted(outcomeOf(pushResult.type))
    }

    /** `null` when the push has no outcome to react to. */
    internal fun onCompleted(success: Boolean?) {
        if (success == null) return
        ApplicationManager.getApplication().invokeLater {
            AtmosphereManager.getInstance().publishOutcome(project, success)
        }
    }

    companion object {
        /** Maps a per-repository push result to an outcome; `null` for "nothing happened". */
        fun outcomeOf(type: GitPushRepoResult.Type): Boolean? = when (type) {
            GitPushRepoResult.Type.SUCCESS, GitPushRepoResult.Type.NEW_BRANCH, GitPushRepoResult.Type.FORCED -> true
            GitPushRepoResult.Type.REJECTED_NO_FF, GitPushRepoResult.Type.REJECTED_STALE_INFO,
            GitPushRepoResult.Type.REJECTED_OTHER, GitPushRepoResult.Type.ERROR -> false
            GitPushRepoResult.Type.UP_TO_DATE, GitPushRepoResult.Type.NOT_PUSHED -> null
        }
    }
}
