package info.chrzanowski.ideatmosphere.events

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresEdt
import javax.swing.JRootPane
import javax.swing.SwingUtilities

/**
 * Follows the text caret of the project's selected editor and reports it as a soft anchor in root-pane coordinates
 * (see `AtmosphereEffect.setAnchor`), so particles can gather near where the user types.
 *
 * Only observes: caret moves and scrolls come from the `EditorFactory` event multicaster, editor switches from
 * `FileEditorManagerListener`, and nothing is consumed or altered. The anchor is reported *inactive* whenever there is
 * no selected text editor, the editor belongs to another project or window (its component is not a descendant of the
 * frame's root pane — detached editor windows are left alone), or the caret has scrolled out of the visible area.
 *
 * [onAnchor] is called on the EDT whenever the reported anchor changes; disposing the producer reports it inactive once
 * more and stops listening.
 */
class CaretAnchorProducer(
    private val project: Project,
    private val rootPane: JRootPane,
    private val onAnchor: (x: Float, y: Float, active: Boolean) -> Unit,
    /** The editor whose caret is followed; replaceable so tests can supply one without the file editor manager. */
    private val selectedEditor: () -> Editor? = { FileEditorManager.getInstance(project).selectedTextEditor },
) : Disposable {

    /** Root-pane coordinates of the last reported anchor (meaningful while [isActive]). */
    var anchorX: Float = 0f
        private set
    var anchorY: Float = 0f
        private set

    /** `true` while a caret anchor is being reported. */
    var isActive: Boolean = false
        private set

    private var disposed = false

    /** Registers the passive listeners and reports the current caret; pair with [dispose]. */
    @RequiresEdt
    fun start() {
        if (disposed) return
        val factory = EditorFactory.getInstance()
        val multicaster = factory.eventMulticaster
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = onEditorChanged(event.editor)
            override fun caretAdded(event: CaretEvent) = onEditorChanged(event.editor)
            override fun caretRemoved(event: CaretEvent) = onEditorChanged(event.editor)
        }, this)
        multicaster.addVisibleAreaListener(VisibleAreaListener { event -> onEditorChanged(event.editor) }, this)
        factory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorReleased(event: EditorFactoryEvent) {
                if (event.editor.project == project) refresh()
            }
        }, this)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = refresh()
        })
        refresh()
    }

    /** Re-reads the selected editor and reports its caret (or an inactive anchor). */
    @RequiresEdt
    fun refresh() {
        if (!disposed) update(selectedEditor())
    }

    private fun onEditorChanged(editor: Editor) {
        if (disposed || editor.project != project) return
        // Carets of unselected editors (other splits, hidden tabs) are ignored; the selected one is re-measured.
        if (editor === selectedEditor()) update(editor)
    }

    /** Measures the caret of [editor] and publishes it; `null` or an editor outside this frame clears the anchor. */
    @RequiresEdt
    internal fun update(editor: Editor?) {
        if (editor == null || editor.isDisposed || editor.project != project) return publish(0f, 0f, false)
        val component = editor.contentComponent
        if (!SwingUtilities.isDescendingFrom(component, rootPane)) return publish(0f, 0f, false)
        val caret = editor.caretModel.primaryCaret
        val xy = editor.visualPositionToXY(caret.visualPosition)
        // Scrolled out of view: nothing to gather around. (An editor that is not laid out yet has an empty area.)
        val visible = editor.scrollingModel.visibleArea
        if (!visible.isEmpty && !visible.contains(xy)) return publish(0f, 0f, false)
        val point = SwingUtilities.convertPoint(component, xy.x, xy.y + editor.lineHeight / 2, rootPane)
        publish(point.x.toFloat(), point.y.toFloat(), true)
    }

    private fun publish(x: Float, y: Float, active: Boolean) {
        if (active == isActive && (!active || (x == anchorX && y == anchorY))) return
        anchorX = x
        anchorY = y
        isActive = active
        onAnchor(x, y, active)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        publish(0f, 0f, false)
    }
}
