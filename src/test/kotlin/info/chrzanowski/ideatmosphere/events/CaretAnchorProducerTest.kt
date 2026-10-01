package info.chrzanowski.ideatmosphere.events

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.SwingUtilities

class CaretAnchorProducerTest : BasePlatformTestCase() {

    private lateinit var rootPane: JRootPane
    private val anchors = ArrayList<Triple<Float, Float, Boolean>>()

    override fun setUp() {
        super.setUp()
        rootPane = JRootPane()
        rootPane.setSize(1200, 800)
        rootPane.doLayout()
        anchors.clear()
    }

    private fun producer(selected: () -> Editor?): CaretAnchorProducer {
        val producer = CaretAnchorProducer(project, rootPane, { x, y, active -> anchors.add(Triple(x, y, active)) }, selected)
        Disposer.register(testRootDisposable, producer)
        return producer
    }

    /** Puts the editor into the frame at ([x], [y]) so its caret can be converted to root-pane coordinates. */
    private fun mount(editor: Editor, x: Int, y: Int) {
        val holder = JPanel(null)
        holder.setBounds(x, y, 800, 600)
        rootPane.layeredPane.add(holder)
        holder.add(editor.component)
        editor.component.setBounds(0, 0, 800, 600)
        editor.component.doLayout()
    }

    fun `test caret of the selected editor inside the frame is reported in root-pane coordinates`() {
        myFixture.configureByText("a.txt", "first line\nsecond <caret>line\n")
        val editor = myFixture.editor
        mount(editor, 100, 50)

        val producer = producer { editor }
        producer.start()

        assertTrue(producer.isActive)
        val caretXY = editor.visualPositionToXY(editor.caretModel.primaryCaret.visualPosition)
        val expected = SwingUtilities.convertPoint(editor.contentComponent, caretXY.x, caretXY.y + editor.lineHeight / 2, rootPane)
        assertEquals(expected.x.toFloat(), producer.anchorX)
        assertEquals(expected.y.toFloat(), producer.anchorY)
        assertEquals(listOf(Triple(expected.x.toFloat(), expected.y.toFloat(), true)), anchors)
        assertTrue("the frame offset of the editor is part of the anchor", producer.anchorX >= 100f && producer.anchorY >= 50f)

        // Moving the caret re-reports; the same position is not reported twice.
        editor.caretModel.moveToOffset(0)
        assertTrue(producer.isActive)
        assertEquals(2, anchors.size)
        assertTrue("the caret moved up to the first line", anchors.last().second < anchors.first().second)
        editor.caretModel.moveToOffset(0)
        assertEquals(2, anchors.size)
    }

    fun `test editors outside the frame or of another project and a missing editor clear the anchor`() {
        myFixture.configureByText("b.txt", "text<caret>")
        val editor = myFixture.editor
        var selected: Editor? = editor
        val producer = producer { selected }
        producer.start()
        assertFalse("an editor that is not inside this frame is ignored", producer.isActive)
        assertTrue(anchors.isEmpty())

        mount(editor, 0, 0)
        producer.refresh()
        assertTrue(producer.isActive)
        assertEquals(1, anchors.size)

        selected = null
        producer.refresh()
        assertFalse(producer.isActive)
        assertEquals(Triple(0f, 0f, false), anchors.last())
        assertEquals(2, anchors.size)
        producer.refresh()
        assertEquals("an inactive anchor is not repeated", 2, anchors.size)

        selected = editor
        producer.refresh()
        assertTrue(producer.isActive)
        // Caret events of an editor that is not the selected one are ignored.
        selected = null
        editor.caretModel.moveToOffset(0)
        assertTrue("the caret event of a non-selected editor does not clear the anchor", producer.isActive)
        producer.refresh()
        assertFalse(producer.isActive)
    }

    fun `test disposal reports an inactive anchor once and stops following`() {
        myFixture.configureByText("c.txt", "<caret>text")
        val editor = myFixture.editor
        mount(editor, 0, 0)
        val producer = producer { editor }
        producer.start()
        assertTrue(producer.isActive)
        val before = anchors.size

        Disposer.dispose(producer)
        assertFalse(producer.isActive)
        assertEquals(before + 1, anchors.size)
        assertEquals(false, anchors.last().third)

        editor.caretModel.moveToOffset(2)
        producer.refresh()
        assertEquals("nothing is reported after disposal", before + 1, anchors.size)
    }
}
