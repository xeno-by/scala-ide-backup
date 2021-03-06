package org.scalaide.ui.internal.preferences

import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.HashMap
import java.util.Properties
import net.miginfocom.layout._
import net.miginfocom.swt.MigLayout
import org.eclipse.core.resources.IProject
import org.eclipse.core.runtime.preferences.InstanceScope
import org.eclipse.jdt.core.IJavaProject
import org.eclipse.jdt.internal.ui.JavaPlugin
import org.eclipse.jdt.internal.ui.javaeditor.JavaSourceViewer
import org.eclipse.jdt.internal.ui.preferences.OverlayPreferenceStore
import org.eclipse.jdt.internal.ui.preferences.PreferencesMessages
import org.eclipse.jdt.ui.PreferenceConstants
import org.eclipse.jdt.ui.text.IJavaPartitions
import org.eclipse.jface.dialogs.MessageDialog
import org.eclipse.jface.resource.JFaceResources
import org.eclipse.jface.text._
import org.eclipse.jface.util._
import org.eclipse.swt.SWT
import org.eclipse.swt.events._
import org.eclipse.swt.widgets._
import org.eclipse.ui._
import org.eclipse.ui.dialogs.PreferencesUtil
import org.eclipse.ui.dialogs.PropertyPage
import org.eclipse.ui.editors.text.TextEditor
import org.scalaide.core.ScalaPlugin
import org.scalaide.core.internal.formatter.FormatterPreferences
import org.scalaide.core.internal.formatter.FormatterPreferences._
import org.scalaide.core.internal.lexical.ScalaDocumentPartitioner
import org.scalaide.util.internal.eclipse.SWTUtils._
import scalariform.formatter._
import scalariform.formatter.preferences._
import org.scalaide.logging.HasLogger
import org.eclipse.core.resources.ProjectScope

class FormatterPreferencePage extends PropertyPage with IWorkbenchPreferencePage with HasLogger {
  import FormatterPreferencePage._

  private var isWorkbenchPage = false

  private var allEnableDisableControls: Set[Control] = Set()

  override def init(workbench: IWorkbench) {
    isWorkbenchPage = true
  }

  lazy val overlayStore = {
    import OverlayPreferenceStore._
    val keys =
      for (preference <- AllPreferences.preferences)
        yield preference.preferenceType match {
        case BooleanPreference => new OverlayKey(BOOLEAN, preference.eclipseKey)
        case IntegerPreference(_, _) => new OverlayKey(INT, preference.eclipseKey)
      }
    val overlayStore = new OverlayPreferenceStore(getPreferenceStore, keys.toArray)
    overlayStore.load()
    overlayStore.start()
    overlayStore
  }

  abstract class PrefTab(tabName: String, previewText: String) {

    protected var previewDocument: IDocument = _

    def build(tabFolder: TabFolder) {
      val tabItem = new TabItem(tabFolder, SWT.NONE)
      tabItem.setText(tabName)
      val tabComposite = new Composite(tabFolder, SWT.NONE)
      tabItem.setControl(tabComposite)
      buildContents(tabComposite)
    }

    protected def buildContents(composite: Composite)

    private def formatPreviewText: String = ScalaFormatter.format(previewText, getPreferences(overlayStore))

    protected def addCheckBox(parent: Composite, text: String, preference: BooleanPreferenceDescriptor) {
      val checkBox = new Button(parent, SWT.CHECK | SWT.WRAP)
      checkBox.setText(text)
      checkBox.setToolTipText(preference.description + " (" + preference.key + ")")
      checkBox.setSelection(overlayStore(preference))
      checkBox.setLayoutData(new CC().spanX(2).growX.wrap)
      checkBox.addSelectionListener { e: SelectionEvent =>
        overlayStore(preference) = checkBox.getSelection
        previewDocument.set(formatPreviewText)
      }

      overlayStore.addPropertyChangeListener { e: PropertyChangeEvent =>
        if (e.getProperty == preference.eclipseKey)
          checkBox.setSelection(overlayStore(preference))
      }

      allEnableDisableControls += checkBox
    }

    protected def addNumericField(parent: Composite, text: String, preference: PreferenceDescriptor[Int]) {
      val IntegerPreference(min, max) = preference.preferenceType
      val label = new Label(parent, SWT.LEFT)
      label.setText(text)
      label.setToolTipText(preference.description + " (" + preference.key + ")")
      label.setLayoutData(new CC())
      val field = new Text(parent, SWT.SINGLE | SWT.BORDER)
      field.setText(overlayStore(preference).toString)
      field.setLayoutData(new CC().sizeGroupX("numfield").alignX("right").minWidth("40px").wrap)

      def validateNumber(s: String) =
        try Integer.parseInt(s) match {
          case n if n < min || n > max => None
          case n => Some(n)
        } catch {
          case _: NumberFormatException => None
        }

      def valueChanged() {
        validateNumber(field.getText) match {
          case Some(n) =>
            overlayStore(preference) = n
            previewDocument.set(formatPreviewText)
            setErrorMessage(null)
          case None =>
            setErrorMessage("Number must be an integer between " + min + " and " + max)
        }
      }

      field.onKeyReleased { valueChanged() }
      field.onFocusLost { valueChanged() }
      overlayStore.addPropertyChangeListener { e: PropertyChangeEvent =>
        if (e.getProperty == preference.eclipseKey)
          field.setText(overlayStore(preference).toString)
      }
      allEnableDisableControls ++= Set(label, field)
    }

    protected def addPreview(parent: Composite) {
      val previewLabel = new Label(parent, SWT.LEFT)
      allEnableDisableControls += previewLabel
      previewLabel.setText("Preview:")
      previewLabel.setLayoutData(new CC().spanX(2).wrap)
      val previewer = createPreviewer(parent)
      previewer.setLayoutData(new CC().spanX(2).grow)
    }

    protected def createPreviewer(parent: Composite): Control = {
      val previewer = ScalaPreviewerFactory.createPreviewer(parent, getPreferenceStore, formatPreviewText)
      previewDocument = previewer.getDocument
      val control = previewer.getControl
      allEnableDisableControls += control
      control
    }
  }

  object IndentPrefTab extends PrefTab("Indentation && Alignment", INDENT_PREVIEW_TEXT) {

    def buildContents(composite: Composite) {
      composite.setLayout(new MigLayout(new LC().fill, new AC, new AC().index(9).grow(1)))

      addNumericField(composite, "Spaces to indent:", IndentSpaces)
      addCheckBox(composite, "Indent using tabs", IndentWithTabs)
      addCheckBox(composite, "Align parameters", AlignParameters)
      addCheckBox(composite, "Double indent class declaration", DoubleIndentClassDeclaration)
      addCheckBox(composite, "Align single-line case statements", AlignSingleLineCaseStatements)
      addNumericField(composite, "Max arrow indent:", AlignSingleLineCaseStatements.MaxArrowIndent)
      addCheckBox(composite, "Indent package blocks", IndentPackageBlocks)
      addCheckBox(composite, "Indent local defs", IndentLocalDefs)

      addPreview(composite)
    }
  }

  object SpacesPrefTab extends PrefTab("Spaces", SPACES_PREVIEW_TEXT) {

    def buildContents(composite: Composite) {
      composite.setLayout(new MigLayout(new LC().fill, new AC, new AC().index(7).grow(1)))

      addCheckBox(composite, "Space before colons", SpaceBeforeColon)
      addCheckBox(composite, "Compact string concatenation", CompactStringConcatenation)
      addCheckBox(composite, "Space inside brackets", SpaceInsideBrackets)
      addCheckBox(composite, "Space inside parentheses", SpaceInsideParentheses)
      addCheckBox(composite, "Preserve space before arguments", PreserveSpaceBeforeArguments)
      addCheckBox(composite, "Spaces within pattern binders", SpacesWithinPatternBinders)

      addPreview(composite)
    }
  }

  object MiscPrefTab extends PrefTab("Miscellaneous", MISC_PREVIEW_TEXT) {

    def buildContents(composite: Composite) {
      composite.setLayout(new MigLayout(new LC().fill, new AC, new AC().index(5).grow(1)))

      addCheckBox(composite, "Format XML", FormatXml)
      addCheckBox(composite, "Rewrite arrow tokens", RewriteArrowSymbols)
      addCheckBox(composite, "Preserve dangling close parenthesis", PreserveDanglingCloseParenthesis)
      addCheckBox(composite, "Use Compact Control Readability style", CompactControlReadability)

      addPreview(composite)
    }

  }

  object ScaladocPrefTab extends PrefTab("Scaladoc", SCALADOC_PREVIEW_TEXT) {

    def buildContents(composite: Composite) {
      composite.setLayout(new MigLayout(new LC().fill, new AC, new AC().index(3).grow(1)))

      addCheckBox(composite, "Multiline Scaladoc comments start on first line", MultilineScaladocCommentsStartOnFirstLine)
      addCheckBox(composite, "Align asterisks beneath second asterisk", PlaceScaladocAsterisksBeneathSecondAsterisk)

      addPreview(composite)
    }

  }

  private def initUnderlyingPreferenceStore() {
    val pluginId = ScalaPlugin.plugin.pluginId
    val scalaPrefStore = ScalaPlugin.prefStore
    setPreferenceStore(getElement match {
      case project: IProject => new PropertyStore(new ProjectScope(project), pluginId)
      case project: IJavaProject => new PropertyStore(new ProjectScope(project.getProject), pluginId)
      case _ => scalaPrefStore
    })
  }

  def createContents(parent: Composite): Control = {

    initUnderlyingPreferenceStore() // done here to ensure that getElement will have been set

    val control = new Composite(parent, SWT.NONE)
    val rowConstraints = if (isWorkbenchPage)
      new AC().index(0).grow(0).index(1).grow
    else
      new AC().index(0).grow(0).index(1).grow(0).index(2).grow(0).index(3).grow
    control.setLayout(new MigLayout(new LC().insetsAll("0").fill, new AC(), rowConstraints))

    if (!isWorkbenchPage) {

      val projectSpecificButton = new Button(control, SWT.CHECK | SWT.WRAP)
      projectSpecificButton.setText("Enable project specific settings")
      projectSpecificButton.setSelection(getPreferenceStore.getBoolean(USE_PROJECT_SPECIFIC_SETTINGS_KEY))
      projectSpecificButton.addSelectionListener { e: SelectionEvent =>
        val enabled = projectSpecificButton.getSelection
        getPreferenceStore.setValue(USE_PROJECT_SPECIFIC_SETTINGS_KEY, enabled)
        allEnableDisableControls foreach { _.setEnabled(enabled) }
      }
      projectSpecificButton.setLayoutData(new CC)

      val link = new Link(control, SWT.NONE)
      link.setText("<a>" + PreferencesMessages.PropertyAndPreferencePage_useworkspacesettings_change + "</a>")
      link.addSelectionListener { () =>
        PreferencesUtil.createPreferenceDialogOn(getShell, PAGE_ID, Array(PAGE_ID), null).open()
      }
      link.setLayoutData(new CC().alignX("right").wrap)

      val horizontalLine = new Label(control, SWT.SEPARATOR | SWT.HORIZONTAL)
      horizontalLine.setLayoutData(new CC().spanX(2).grow.wrap)
    }

    { // Manual link + import / export buttons
      val buttonPanel = new Composite(control, SWT.NONE)

      buttonPanel.setLayout(
        new MigLayout(
          new LC().insetsAll("0"),
          new AC()
            .index(0).grow.align("left")
            .index(1).grow(0).align("right")
            .index(2).grow(0).align("right")))

      val link = new Link(buttonPanel, SWT.NONE)
      link.setText("<a>Scalariform manual</a>")
      link.addSelectionListener { e: SelectionEvent =>
        val url = new URL(SCALARIFORM_DOC_URL)
        PlatformUI.getWorkbench.getBrowserSupport.createBrowser(null).openURL(url)
      }
      link.setLayoutData(new CC)

      val importButton = new Button(buttonPanel, SWT.PUSH)
      importButton.setText("Import...")
      importButton.setLayoutData(new CC().sizeGroupX("button"))
      importButton.addSelectionListener { () => importPreferences() }

      val exportButton = new Button(buttonPanel, SWT.PUSH)
      exportButton.setText("Export...")
      exportButton.setLayoutData(new CC().sizeGroupX("button").wrap)
      exportButton.addSelectionListener { () => exportPreferences() }

      buttonPanel.setLayoutData(new CC().spanX(2).growX.wrap)
      allEnableDisableControls ++= Set(link, importButton, exportButton)
    }

    val tabFolder = new TabFolder(control, SWT.TOP)
    tabFolder.setLayoutData(new CC().spanX(2).grow)

    IndentPrefTab.build(tabFolder)
    SpacesPrefTab.build(tabFolder)
    ScaladocPrefTab.build(tabFolder)
    MiscPrefTab.build(tabFolder)

    allEnableDisableControls += tabFolder

    if (!isWorkbenchPage) {
      val enabled = getPreferenceStore.getBoolean(USE_PROJECT_SPECIFIC_SETTINGS_KEY)
      allEnableDisableControls foreach { _.setEnabled(enabled) }
    }
    control
  }

  override def performOk() = {
    super.performOk()
    overlayStore.propagate()
    InstanceScope.INSTANCE.getNode(ScalaPlugin.plugin.pluginId).flush()
    true
  }

  override def dispose() {
    overlayStore.stop()
    super.dispose()
  }

  override def performDefaults() {
    overlayStore.loadDefaults()
    super.performDefaults()
  }

  private def getPreferenceFileNameViaDialog(title: String, initialFileName: String = ""): Option[String] = {
    val dialog = new FileDialog(getShell, SWT.SAVE)
    dialog.setText(title)
    dialog.setFileName(initialFileName)
    val dialogSettings = ScalaPlugin.plugin.getDialogSettings
    Option(dialogSettings get IMPORT_EXPORT_DIALOG_PATH) foreach dialog.setFilterPath
    val fileName = dialog.open()
    if (fileName == null)
      None
    else {
      dialogSettings.put(IMPORT_EXPORT_DIALOG_PATH, dialog.getFilterPath)
      Some(fileName)
    }
  }

  private def exportPreferences() {
    for (fileName <- getPreferenceFileNameViaDialog("Export formatter preferences", DEFAULT_PREFERENCE_FILE_NAME)) {
      val preferences = FormatterPreferences.getPreferences(overlayStore)
      try
        PreferencesImporterExporter.savePreferences(fileName, preferences)
      catch {
        case e: IOException =>
          eclipseLog.error(e)
          MessageDialog.openError(getShell, "Error writing to " + fileName, e.getMessage)
      }
    }
  }

  private def importPreferences() {
    for (fileName <- getPreferenceFileNameViaDialog("Import formatter preferences")) {
      val preferences = try
        PreferencesImporterExporter.loadPreferences(fileName)
      catch {
        case e: IOException =>
          eclipseLog.error(e)
          MessageDialog.openError(getShell, "Error opening " + fileName, e.getMessage)
          return
      }
      overlayStore.importPreferences(preferences)
    }
  }

}

object FormatterPreferencePage {

  val DEFAULT_PREFERENCE_FILE_NAME = "formatterPreferences.properties"

  val PAGE_ID = "scala.tools.eclipse.formatter.FormatterPreferencePage"

  val IMPORT_EXPORT_DIALOG_PATH = "formatter.importExportDialogPath"

  val SCALARIFORM_DOC_URL = "http://mdr.github.com/scalariform/"

  val SPACES_PREVIEW_TEXT = """class ClassName[T](name: String) {

  println("hello"+name+"world")

  stack.pop() should equal (2)

  x match {
    case elem@Multi(values@_*) =>
  }

}
"""

  val INDENT_PREVIEW_TEXT = """package foo {
class Bar(param: Int)
extends Foo with Baz {
  def method(s: String,
n: Int) = {
    def localDef {
      // ..
    }
    s match {
      case "wibble" => 42
      case "foo" => 123
      case _ => 100
    }
  }
}
}"""

  val MISC_PREVIEW_TEXT = """val xml = <foo>
<bar/>
 <baz  attr= "value" />
</foo>
for (n <- 1 to 10)
 n match {
  case _ => 42
}
val book = Book(
  name = "Name",
  author = "Author",
  rating = 5
)
if (condition) {
  // do something
}
else if (condition2) {
  // do something else
}
else {
  // last ditch
}
"""

  val SCALADOC_PREVIEW_TEXT = """/**
 * Multiline Scaladoc
 * comment
 */
 class A
"""

}
