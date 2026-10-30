package dev.sort.duckdb.grammar

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.JToolBar
import javax.swing.table.AbstractTableModel
import java.util.concurrent.CompletableFuture

/** Independent grammar edits never dirty the native connection configuration. */
internal class DuckdbGrammarEditor(private val project: Project, val source: LocalDataSource) : JPanel(BorderLayout()) {
    private val runtime = project.service<DuckdbGrammarRuntime>()
    private val settings = project.service<DuckdbGrammarSettings>()
    private var pending = DuckdbGrammarProfile()
    private var saved = DuckdbGrammarProfile()
    private var applyError: String? = null
    private var snapshot: DuckdbGrammarSnapshot? = null
    private var request: CompletableFuture<DuckdbGrammarSnapshot>? = null
    private var displayedStamp = -1L
    private var displayedLogin: Pair<String?, String?>? = null
    private var displayedName: String? = null
    private var triedInitialDiscovery = false
    private val context = text("Data source: ${source.name}\nProject: ${project.name}")
    val status = text("")
    private val effective = text("")
    private val application = text("")
    val completionStatus = text("")
    val discover = JButton("Discover grammars").apply { addActionListener { discover() } }
    val applyChanges = JButton("Apply grammar changes").apply { addActionListener { applyGrammarChanges() } }
    val resetChanges = JButton("Reset changes").apply { addActionListener { resetChanges() } }
    private val rows get() = (snapshot ?: DuckdbGrammarSnapshot(unavailable = "Not discovered")).entries(pending)
    val model = object : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = listOf("Enabled", "Grammar", "Availability")[column]
        override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == 0) Boolean::class.javaObjectType else String::class.java
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 0
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val entry = rows[rowIndex]
            return when (columnIndex) {
                0 -> entry.enabled
                1 -> entry.name
                else -> if (snapshot?.supported != true) "Not confirmed" else if (entry.available) "Available" else "Unavailable"
            }
        }
        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 0 || value !is Boolean) return
            val entry = rows[rowIndex]
            change(pending.select(entry.name, value))
        }
    }
    val table = JBTable(model).apply {
        setShowGrid(false)
        columnModel.getColumn(0).apply { minWidth = JBUI.scale(60); maxWidth = JBUI.scale(75) }
        columnModel.getColumn(1).preferredWidth = JBUI.scale(275)
        columnModel.getColumn(2).preferredWidth = JBUI.scale(140)
        preferredScrollableViewportSize = Dimension(JBUI.scale(500), JBUI.scale(180))
    }
    private val add = toolButton("Add grammar", "+") {
        addGrammar(Messages.showInputDialog(this, "Registered grammar name:", "Add grammar", null))
    }
    private val remove = toolButton("Remove from enabled profile", "−") { removeSelected() }
    private val up = toolButton("Move grammar up", "↑") { moveSelected(-1) }
    private val down = toolButton("Move grammar down", "↓") { moveSelected(1) }
    private val timer = Timer(500) {
        if (request == null && (displayedStamp != runtime.stamp || displayedLogin != (source.url to source.username) || displayedName != source.name)) showSnapshot(runtime.runtime(source)?.snapshot)
        completionStatus.text = project.service<dev.sort.duckdb.completion.DuckdbNativeKeywordService>().describe(source)
    }

    init {
        border = JBUI.Borders.empty(10)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(context)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(text("New registered grammars start enabled. Unchecked choices stay disabled. Order affects parsing."))
            add(Box.createVerticalStrut(JBUI.scale(8)))
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout()).apply {
            add(JToolBar().apply {
                isFloatable = false
                isOpaque = false
                border = JBUI.Borders.emptyBottom(4)
                add(add); add(remove); add(up); add(down)
            }, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(JPanel(BorderLayout()).apply {
                alignmentX = LEFT_ALIGNMENT
                add(discover, BorderLayout.WEST)
                add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                    add(applyChanges); add(resetChanges)
                }, BorderLayout.EAST)
            })
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(status)
            add(effective)
            add(application)
            add(completionStatus)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(text("Apply grammar changes saves separately and updates idle sessions without reconnecting. Closing discards unapplied edits. Connection-dialog Cancel does not undo an explicit grammar Apply. No modules are installed or loaded."))
        }, BorderLayout.SOUTH)
        resetChanges()
        showSnapshot(runtime.runtime(source)?.snapshot)
        table.selectionModel.addListSelectionListener { updateButtons() }
        updateButtons()
    }

    fun profile(): DuckdbGrammarProfile {
        table.cellEditor?.stopCellEditing()
        return pending.copy(enabled = pending.enabled.toList(), disabled = pending.disabled.toList())
    }

    fun reset(profile: DuckdbGrammarProfile) {
        table.cellEditor?.cancelCellEditing()
        pending = profile.copy(enabled = profile.enabled.toList(), disabled = profile.disabled.toList())
        model.fireTableDataChanged()
        updateButtons()
    }

    fun resetChanges() {
        saved = settings.profile(source)
        applyError = null
        reset(saved)
    }

    fun applyGrammarChanges() {
        applyError = runtime.applyProfile(source, profile())
        if (applyError == null) resetChanges()
        application.text = applyError ?: runtime.applicationStatus(source) ?: "Saved. Active session settings were preserved or updated without reconnecting."
        updateButtons()
    }

    fun addGrammar(name: String?) {
        val value = name?.trim()?.takeIf(String::isNotEmpty) ?: return
        if (value !in pending.enabled) change(pending.select(value, true))
    }

    fun removeSelected() {
        val index = table.selectedRow
        if (index !in pending.enabled.indices) return
        change(pending.select(pending.enabled[index], false))
    }

    fun moveSelected(delta: Int) {
        val from = table.selectedRow
        val to = from + delta
        if (from !in pending.enabled.indices || to !in pending.enabled.indices) return
        change(pending.copy(enabled = pending.enabled.toMutableList().apply { java.util.Collections.swap(this, from, to) }))
        table.setRowSelectionInterval(to, to)
    }

    private fun change(profile: DuckdbGrammarProfile) {
        pending = profile
        model.fireTableDataChanged()
        updateButtons()
    }

    fun discover() {
        val identity = Triple(source.uniqueId, source.url, source.username)
        request?.cancel(false)
        status.text = "Discovering registered grammars…"
        discover.isEnabled = false
        val future = runtime.discover(source)
        request = future
        future.whenComplete { value, error ->
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (request !== future || project.isDisposed) return@invokeLater
                request = null
                discover.isEnabled = true
                if (identity != Triple(source.uniqueId, source.url, source.username)) {
                    showSnapshot(null)
                    status.text = "Data source changed during discovery. Discover again for the edited connection."
                    return@invokeLater
                }
                showSnapshot(value ?: DuckdbGrammarSnapshot(unavailable = error?.message ?: "Discovery failed"))
            }
        }
    }

    private fun showSnapshot(value: DuckdbGrammarSnapshot?) {
        displayedStamp = runtime.stamp
        displayedLogin = source.url to source.username
        displayedName = source.name
        context.text = "Data source: ${source.name}\nProject: ${project.name}"
        snapshot = value
        val clean = pending == saved
        saved = settings.profile(source)
        pending = if (clean) saved else if (value?.supported == true) pending.discovered(value.available) else pending
        status.text = when {
            value == null -> "Not discovered. Discover uses an existing console of this data source; it never connects or prompts for a password."
            !value.supported -> value.unavailable
            value.available.isEmpty() -> "No registered grammars. Load an extension explicitly in this source's console, then Discover."
            else -> "${value.available.size} registered grammar${if (value.available.size == 1) "" else "s"}. Discovery used this data source's last active console."
        }
        effective.text = if (value?.supported == true) "Console activation: ${value.effective.joinToString().ifEmpty { "base grammar" }}. This is separate from the enabled order above." else ""
        application.text = applyError ?: runtime.applicationStatus(source).orEmpty()
        completionStatus.text = project.service<dev.sort.duckdb.completion.DuckdbNativeKeywordService>().describe(source)
        model.fireTableDataChanged()
        updateButtons()
        revalidate()
    }

    override fun addNotify() {
        super.addNotify()
        timer.start()
        if (!triedInitialDiscovery) {
            triedInitialDiscovery = true
            if (snapshot == null && runtime.canDiscover(source)) discover()
        }
    }

    override fun removeNotify() {
        timer.stop()
        request?.cancel(false)
        request = null
        super.removeNotify()
    }

    private fun text(value: String) = object : JTextArea(value) {
        override fun getPreferredSize(): Dimension {
            val available = parent?.width?.takeIf { it > 0 } ?: JBUI.scale(560)
            setSize(available, Short.MAX_VALUE.toInt())
            return super.getPreferredSize().apply { width = available }
        }
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        font = UIManager.getFont("Label.font").deriveFont(java.awt.Font.PLAIN)
        foreground = UIManager.getColor("Label.foreground")
        border = null
        alignmentX = LEFT_ALIGNMENT
    }

    private fun toolButton(label: String, symbol: String, action: () -> Unit) = JButton(symbol).apply {
        toolTipText = label
        accessibleContext.accessibleName = label
        isFocusable = false
        margin = JBUI.emptyInsets()
        preferredSize = JBUI.size(26)
        minimumSize = preferredSize
        maximumSize = preferredSize
        addActionListener { action() }
    }

    private fun updateButtons() {
        val row = table.selectedRow
        remove.isEnabled = row in pending.enabled.indices
        up.isEnabled = row in 1 until pending.enabled.size
        down.isEnabled = row in 0 until pending.enabled.lastIndex
        applyChanges.isEnabled = pending != saved
        resetChanges.isEnabled = pending != saved
    }
}
