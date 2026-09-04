package fr.curie.miclearning.apposeplugin.sampcs;

import fr.curie.miclearning.apposeplugin.DialogHelpBar;
import fr.curie.miclearning.apposeplugin.RoiPromptExtractor;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.gui.Roi;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellEditor;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

import static ij.plugin.frame.RoiManager.getRoiManager;

/**
 * Non-modal Swing dialog for {@link Sam3ImagePcs_Plugin}: lets the user define an arbitrary
 * number of concepts, plus model path, detection parameters and output options.
 */
public class Sam3ImageDialog extends JDialog {

    private static final String PREF_LAST_MODEL_KEY = "miclearning.lastmodeldir.sam3";
    private static final int ROI_POLL_INTERVAL_MS = 500;
    private static final String VISUAL_PROMPT_RESET_OWNER = "visualPromptReset";
    private static final String PREF_UI_SCALE_KEY = "miclearning.sam3.imagedialog.uiscale";
    private static final double DEFAULT_UI_SCALE = 1;
    private static final double MIN_UI_SCALE = 0.25;
    private static final double MAX_UI_SCALE = 4.0;

    private final ImagePlus imp;
    private final int nFrames;
    private final int axis; // 3 = Z axis, 4 = Time axis (matches ImagePlus.getDimensions() indexing)
    private final RoiPromptExtractor roiPromptExtractor = new RoiPromptExtractor();

    private final Sam3ConceptTableModel tableModel = new Sam3ConceptTableModel();
    private final JTable table = new JTable(tableModel);
    private final Sam3GroupIdCellEditor positiveGroupEditor = new Sam3GroupIdCellEditor(false);
    private final Sam3GroupIdCellEditor negativeGroupEditor = new Sam3GroupIdCellEditor(true);

    private final Sam3ModelParameters modelParams = new Sam3ModelParameters();
    private final double uiScale = Prefs.get(PREF_UI_SCALE_KEY, DEFAULT_UI_SCALE);

    private JTextField modelPathField;
    private JTextField confidenceField;
    private JCheckBox addBoxRoisCB;
    private JCheckBox addShapeRoisCB;
    private JCheckBox instanceMaskCB;
    private JCheckBox semanticMaskCB;
    private JCheckBox instanceMaskPerClassCB;
    private JCheckBox processStackCB; // only shown/relevant when nFrames > 1
    private JButton okButton;

    private DialogHelpBar helpBar;
    private Timer roiPollTimer;
    private String lastRoiSignature = "";

    private final CountDownLatch closedLatch = new CountDownLatch(1);

    private boolean approved = false; // true if user pressed OK and no error

    // populated only once the user presses OK
    private String modelPath;
    private Sam3ModelParameters detectionParams;
    private DetectionUtils.OutputOptions outputOptions;
    private DetectionUtils.DetectionMode stackMode;
    private List<Sam3Concept> concepts;

    public Sam3ImageDialog(ImagePlus imp, int nFrames, int axis) {
        super(IJ.getInstance(), "SAM3 Promptable Concept Segmentation", false);
        this.imp = imp;
        this.nFrames = nFrames;
        this.axis = axis;

        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (roiPollTimer != null) roiPollTimer.stop();
                closedLatch.countDown();
            }
        });

        buildUi();
        installClickAwayCommit();
        if (uiScale != 1.0) {
            rescaleFonts(getContentPane());
            rescaleFonts(table.getTableHeader());
            helpBar.rescaleFont(uiScale); // the help bar resets its own font from a cached field on every render - update that field directly
        }
        pack();
        setLocationRelativeTo(null);
    }

    /**
     * Recursively derives every font in {@code comp}'s subtree by {@link #uiScale}, and scales
     * checkbox icons too
     * Applied once at construction time - the dialog must be closed and reopened for a new scale to
     * take effect.
     */
    private void rescaleFonts(Component comp) {
        Font font = comp.getFont();
        if (font != null) comp.setFont(font.deriveFont((float) (font.getSize2D() * uiScale)));
        if (comp instanceof JCheckBox && ((JCheckBox) comp).getIcon() == null) {
            Icon checkIcon = UIManager.getIcon("CheckBox.icon");
            if (checkIcon != null) ((JCheckBox) comp).setIcon(scaledIcon(checkIcon, uiScale));
        }
        if (comp instanceof Container) {
            for (Component child : ((Container) comp).getComponents()) rescaleFonts(child);
        }
    }

    /** Wraps {@code icon}, painting it scaled by {@code scale} (Swing icons have a fixed pixel size). */
    private static Icon scaledIcon(Icon icon, double scale) {
        int w = (int) Math.round(icon.getIconWidth() * scale);
        int h = (int) Math.round(icon.getIconHeight() * scale);
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.translate(x, y);
                g2.scale((double) w / icon.getIconWidth(), (double) h / icon.getIconHeight());
                icon.paintIcon(c, g2, 0, 0);
                g2.dispose();
            }
            @Override public int getIconWidth() {return w;}
            @Override public int getIconHeight() {return h;}
        };
    }

    /** A section-title label matching this dialog's unified heading style: bold, one size bigger than body text. */
    private JLabel createSectionTitle(String text) {
        JLabel label = new JLabel(text);
        Font base = label.getFont();
        label.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 1f));
        label.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        return label;
    }

    /** Scales a pixel dimension (row height, column width, ...) by {@link #uiScale}. */
    private int scaled(int pixels) {
        return (int) Math.round(pixels * uiScale);
    }

    /**
     * Any click (even on non focusable area) commit changes made on cells
     */
    private void installClickAwayCommit() {
        AWTEventListener listener = event -> {
            if (!(event instanceof MouseEvent) || event.getID() != MouseEvent.MOUSE_PRESSED) return;

            if (!table.isEditing()) return;
            if (MenuSelectionManager.defaultManager().getSelectedPath().length > 0) return; // a popup (e.g. the combo box dropdown) is open

            Component clicked = ((MouseEvent) event).getComponent(); //specific component the mouse press actually landed on
            Component editorComp = table.getEditorComponent(); // the actual Component currently sitting on top of the cell in edit mode
            if (clicked == null || editorComp == null) return;
            if (clicked == editorComp || SwingUtilities.isDescendingFrom(clicked, editorComp)) return; // don't edit if the click is made inside the edited cell

            TableCellEditor editor = table.getCellEditor();
            if (editor != null) editor.stopCellEditing();

        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
            }
        });
    }

    /**
     * Shows the dialog and blocks the calling thread until the user closes it (OK, Cancel, or the
     * window's own close button)
     *
     * @return {@code true} if the user pressed OK with a valid configuration, {@code false} otherwise
     */
    public boolean showDialogAndWait() {
    SwingUtilities.invokeLater(() -> {
            refreshGroupOptions();
            startRoiPolling();
            setVisible(true);
        });
        try {
            closedLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            approved = false;
        }
        return approved;
    }

    public String getModelPath() {return modelPath;}
    public Sam3ModelParameters getDetectionParams() {return detectionParams;}
    public DetectionUtils.OutputOptions getOutputOptions() {return outputOptions;}
    public DetectionUtils.DetectionMode getStackMode() {return stackMode;}
    public List<Sam3Concept> getConcepts() {return concepts;}

    // ==== UI construction ====

    private void buildUi() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(content);

        helpBar = new DialogHelpBar(this, DialogHelpBar.loadSavedMode());

        content.add(helpBar.getPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildModelPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildConceptsPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildParametersPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildOutputPanel());
        content.add(Box.createVerticalStrut(8));
        if (nFrames > 1) content.add(buildStackModePanel());
        content.add(buildButtonsPanel());

        // start with one empty concept row
        tableModel.addConcept();
    }

    private JPanel buildModelPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEtchedBorder());
        panel.add(createSectionTitle("Model"), BorderLayout.NORTH);

        JPanel fieldsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0;
        JLabel modelLabel = new JLabel("Model path:");
        fieldsPanel.add(modelLabel, c);

        modelPathField = new JTextField(Sam3Dialogs.resolveDefaultModelPath(PREF_LAST_MODEL_KEY), 40);
        c.gridx = 1; c.weightx = 1;
        fieldsPanel.add(modelPathField, c);

        String modelPathHint = "Path to the SAM3 model checkpoint (.pt file)";
        helpBar.attachHelp(modelLabel, modelPathHint);
        helpBar.attachHelp(modelPathField, modelPathHint);

        JButton browseButton = new JButton("Browse...");
        browseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            File current = new File(modelPathField.getText());
            if (current.exists()) chooser.setSelectedFile(current);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                modelPathField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        c.gridx = 2; c.weightx = 0;
        fieldsPanel.add(browseButton, c);

        JButton instructionsButton = new JButton("Instructions to download SAM3 model");
        instructionsButton.addActionListener(e -> Sam3Dialogs.addDownloadInstruction());
        c.anchor = GridBagConstraints.CENTER; c.fill = GridBagConstraints.NONE;
        c.gridx = 0; c.gridy = 1; c.gridwidth = 3; c.weightx = 0;
        fieldsPanel.add(instructionsButton, c);

        panel.add(fieldsPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildConceptsPanel() {
        // create table
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEtchedBorder());

        JLabel titleLabel = createSectionTitle("Concepts");
        helpBar.attachHelp(titleLabel, "A concept = one object class to find. Define it with a text prompt, "
                + "a visual prompt (ROI in the ROI Manager), or both.");
        panel.add(titleLabel, BorderLayout.NORTH);

        // replace the default editors so starting to edit a cell selects its whole content
        table.setDefaultEditor(String.class, createSelectAllTextEditor());
        table.setDefaultEditor(Integer.class, createSelectAllIntegerEditor());

        // set each column renderer (and optional editor)
        // Name column : greyed (but still editable) while no ROI output is selected
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_NAME).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component comp = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                if (!isSelected) comp.setForeground(outputWithNameSelected() ? t.getForeground() : Sam3GroupIdCellEditor.NOT_USED_COLOR);
                return comp;
            }
        });

        // Positive / negative visual: editable group combo, "not used" == null
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_POSITIVE_VISUAL).setCellEditor(positiveGroupEditor);
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_POSITIVE_VISUAL).setCellRenderer(new Sam3GroupIdCellEditor.Renderer(positiveGroupEditor));
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_NEGATIVE_VISUAL).setCellEditor(negativeGroupEditor);
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_NEGATIVE_VISUAL).setCellRenderer(new Sam3GroupIdCellEditor.Renderer(negativeGroupEditor));

        // Output group: greyed (but still editable) while no ROI output is selected
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_OUTPUT_GROUP).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component comp = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                if (!isSelected) comp.setForeground(outputWithRoiGroupSelected() ? t.getForeground() : Sam3GroupIdCellEditor.NOT_USED_COLOR);
                return comp;
            }
        });

        // set each column preferred width (scaled so bigger fonts still get a comfortable row/column size)
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_NAME).setPreferredWidth(scaled(100));
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_TEXT_PROMPT).setPreferredWidth(scaled(150));
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_POSITIVE_VISUAL).setPreferredWidth(scaled(160));
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_NEGATIVE_VISUAL).setPreferredWidth(scaled(160));
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_OUTPUT_GROUP).setPreferredWidth(scaled(90));
        table.setRowHeight(scaled(22));
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        // hover hints per column header, defined with mouse position
        JTableHeader header = table.getTableHeader();
        helpBar.attachPositionalHelp(header, point -> {
            int viewCol = header.columnAtPoint(point);
            if (viewCol < 0) return null;
            int modelCol = table.convertColumnIndexToModel(viewCol);
            String roiGroupNotUsed = outputWithRoiGroupSelected() ? "" : "\nUnused for instance masks.";
            String nameNotUsed = outputWithNameSelected() ? "" : "\nUnused for instance and semantic masks.";
            String text;
            switch (modelCol) {
                case Sam3ConceptTableModel.COL_NAME:
                    text = "Name used for this concept's outputs (ROI names, logs). Must be unique."
                    + nameNotUsed;
                    break;
                case Sam3ConceptTableModel.COL_TEXT_PROMPT:
                    text = "Text description of the object (e.g. 'green cell'). One prompt applies to every processed frame.";
                    break;
                case Sam3ConceptTableModel.COL_POSITIVE_VISUAL:
                    text = "ROI group used as a positive visual prompt. Needs one usable ROI (box or point) in the ROI Manager "
                            + "on each processed frame." +
                            "\nEach frame will be processed independently.";
                    break;
                case Sam3ConceptTableModel.COL_NEGATIVE_VISUAL:
                    text = "ROI group used as a negative visual prompt (optional). Needs one usable ROI (box or point) "
                            + "on each processed frame. \nMust differ from the Positive visual group.";
                    break;
                case Sam3ConceptTableModel.COL_OUTPUT_GROUP:
                    text = "Id tagging this concept's outputs: the ROI group for added ROIs, and the pixel value for the semantic mask."
                            + roiGroupNotUsed;
                    break;
                default:
                    return null;
            }
            return new DialogHelpBar.Hint(text, DialogHelpBar.DEFAULT_HELP_COLOR);
        });
        

        // clicking a disabled positive/negative visual cell explains why (no usable ROI yet)
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int viewCol = table.columnAtPoint(e.getPoint());
                int row = table.rowAtPoint(e.getPoint());
                if (viewCol < 0 || row < 0) return;
                int modelCol = table.convertColumnIndexToModel(viewCol);
                boolean isVisualColumn = modelCol == Sam3ConceptTableModel.COL_POSITIVE_VISUAL
                        || modelCol == Sam3ConceptTableModel.COL_NEGATIVE_VISUAL;
                if (isVisualColumn && !tableModel.isCellEditable(row, modelCol)) {
                    helpBar.showInfo("visualPromptDisabled",
                            "No usable ROI (box or point) in the ROI Manager for the frame(s) being processed. "
                                    + "Add one to enable visual prompts.",
                            DialogHelpBar.DEFAULT_INSTRUCTION_COLOR);
                }
            }
        });

        // create scroll pane for the table
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(scaled(700), scaled(160)));
        panel.add(scrollPane, BorderLayout.CENTER);

        // buttons to add and remove concepts
        JButton addButton = new JButton("Add concept");
        addButton.addActionListener(e -> {
            tableModel.addConcept();
            helpBar.clearWarning("promptListEmpty");
            okButton.setEnabled(true);
        });
        helpBar.attachHelp(addButton, "Adds a new concept row.");

        JButton removeButton = new JButton("Remove selected");
        removeButton.addActionListener(e -> {
            if (table.isEditing()) table.getCellEditor().cancelCellEditing();
            int row = table.getSelectedRow();
            if (row >= 0) tableModel.removeConcept(row);
            // if no concept (empty table) : unable okButton + display warning
            if (tableModel.getConcepts().isEmpty()) {
                helpBar.warn("promptListEmpty", "Please enter at least one prompt");
                okButton.setEnabled(false);
            }
        });
        helpBar.attachHelp(removeButton, "Removes the currently selected concept row.");

        JPanel buttonsRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonsRow.add(addButton);
        buttonsRow.add(removeButton);
        panel.add(buttonsRow, BorderLayout.SOUTH);

        return panel;
    }

    /** Text-column editor that selects all existing text as soon as editing starts. */
    private DefaultCellEditor createSelectAllTextEditor() {
        JTextField textField = new JTextField();
        return new DefaultCellEditor(textField) {
            @Override
            public Component getTableCellEditorComponent(JTable t, Object value, boolean isSelected, int row, int column) {
                Component c = super.getTableCellEditorComponent(t, value, isSelected, row, column);
                textField.selectAll();
                return c;
            }
        };
    }

    /**
     * Like {@link #createSelectAllTextEditor()}, but for Integer columns (the "Output group" column):
     * parses the text back to an Integer, returning {@code null} on invalid input.
     */
    private DefaultCellEditor createSelectAllIntegerEditor() {
        JTextField textField = new JTextField();
        textField.setHorizontalAlignment(JTextField.RIGHT);
        return new DefaultCellEditor(textField) {
            @Override
            public Component getTableCellEditorComponent(JTable t, Object value, boolean isSelected, int row, int column) {
                Component c = super.getTableCellEditorComponent(t, value, isSelected, row, column);
                textField.selectAll();
                return c;
            }

            @Override
            public Object getCellEditorValue() {
                try {
                    return Integer.valueOf(textField.getText().trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        };
    }

    private JPanel buildParametersPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEtchedBorder());
        panel.add(createSectionTitle("Detection and segmentation settings"), BorderLayout.NORTH);

        JPanel fieldsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);

        JLabel jLabel = new JLabel("Confidence threshold:");
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0;
        fieldsPanel.add(jLabel, c);
        confidenceField = new JTextField(String.valueOf(Sam3ModelParameters.DEFAULT_CONFIDENCE), 6);
        c.gridx = 1;
        fieldsPanel.add(confidenceField, c);
        String confidenceHint = "Minimum detection probability (0-1) for an object to be added.";
        helpBar.attachHelp(jLabel, confidenceHint);
        helpBar.attachHelp(confidenceField, confidenceHint);

        JButton advancedButton = new JButton("Advanced parameters...");
        advancedButton.addActionListener(e -> Sam3Dialogs.openImageAdvancedParametersDialog(modelParams, getWidth()));
        helpBar.attachHelp(advancedButton, "Mask score threshold, segmentation mask side length, and coordinate encoding ");
        c.anchor = GridBagConstraints.EAST;
        c.gridx = 2; c.weightx = 1.0;
        fieldsPanel.add(advancedButton, c);

        panel.add(fieldsPanel, BorderLayout.CENTER);
        return panel;
    }

    private void addLabeledField(JPanel panel, GridBagConstraints c, int row, String label, JTextField field, String hint) {
        c.gridx = 0; c.gridy = row;
        JLabel jLabel = new JLabel(label);
        panel.add(jLabel, c);
        c.gridx = 1;
        panel.add(field, c);
        helpBar.attachHelp(jLabel, hint);
        helpBar.attachHelp(field, hint);
    }

    private JPanel buildOutputPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEtchedBorder());
        panel.add(createSectionTitle("Outputs"), BorderLayout.NORTH);

        JPanel checkboxesPanel = new JPanel();
        checkboxesPanel.setLayout(new BoxLayout(checkboxesPanel, BoxLayout.Y_AXIS));

        addBoxRoisCB = new JCheckBox("Add bounding boxes to ROI Manager", false);
        addShapeRoisCB = new JCheckBox("Add shape ROIs to ROI Manager", true);
        instanceMaskCB = new JCheckBox("Create instance masks (unique value per instance)", false);
        semanticMaskCB = new JCheckBox("Create semantic masks (unique value per class)", false);
        instanceMaskPerClassCB = new JCheckBox("Create instance mask per class (one slice per class; a per-frame hyperstack for stacks)", false);

        // the "Output group" column is only meaningful for ROI outputs - repaint it when that changes
        addBoxRoisCB.addItemListener(e -> table.repaint());
        addShapeRoisCB.addItemListener(e -> table.repaint());

        helpBar.attachHelp(addBoxRoisCB, "Adds one rectangle ROI per detection, named 'box_' + the concept's output name, "
                + "placed in the ROI group set by that concept's \"Output group\".");
        helpBar.attachHelp(addShapeRoisCB, "Adds one shape ROI per detection, named 'mask_' + the concept's output name, "
                + "placed in the ROI group set by that concept's \"Output group\".");
        helpBar.attachHelp(instanceMaskCB, "One image where each detected instance gets its own unique pixel value ");
        helpBar.attachHelp(semanticMaskCB, "One image where each pixel takes the value of its concept's \"Output group\" "
                + "(0 = background).");
        helpBar.attachHelp(instanceMaskPerClassCB, "Like instance masks, but one slice per concept ; "
                + "a per-frame hyperstack for stacks.");

        checkboxesPanel.add(addBoxRoisCB);
        checkboxesPanel.add(addShapeRoisCB);
        checkboxesPanel.add(instanceMaskCB);
        checkboxesPanel.add(semanticMaskCB);
        checkboxesPanel.add(instanceMaskPerClassCB);

        panel.add(checkboxesPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildStackModePanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        processStackCB = new JCheckBox("Process entire stack (" + nFrames + " frames)", true);
        helpBar.attachHelp(processStackCB, "If unchecked, only the current frame is processed." +
                "\nif checked, each frame of the stack will be processed independently.");
        processStackCB.addItemListener(e -> refreshGroupOptions());
        panel.add(processStackCB);
        return panel;
    }

    private JPanel buildButtonsPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton settingsButton = new JButton("Settings...");
        settingsButton.addActionListener(e -> openSettingsDialog());

        okButton = new JButton("OK");
        okButton.addActionListener(e -> onOk());

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> onCancel());

        panel.add(settingsButton);
        panel.add(okButton);
        panel.add(cancelButton);
        return panel;
    }

    /** Combined settings dialog: help-bar level and interface scale. */
    private void openSettingsDialog() {
        String[] helpLabels = DialogHelpBar.helpLevelLabels();
        int currentHelpIndex = DialogHelpBar.helpLevelIndex(helpBar.getHelpBarMode());

        GenericDialog gd = new GenericDialog("Settings");
        gd.addMessage("Choose how much guidance the dialog's help bar should show:");
        gd.addChoice("Help level:", helpLabels, helpLabels[currentHelpIndex]);

        gd.addMessage("");
        gd.addNumericField("Interface_scale (%):", uiScale * 100, 0);
        gd.addMessage("Close and reopen this plugin for a new interface scale to take effect.");

        gd.showDialog();
        if (gd.wasCanceled()) return;

        helpBar.applyHelpLevelChoice(gd.getNextChoiceIndex());

        double scalePercent = gd.getNextNumber();
        if (Double.isNaN(scalePercent) || scalePercent < MIN_UI_SCALE * 100 || scalePercent > MAX_UI_SCALE * 100) {
            IJ.log("Interface scale must be between " + (int) (MIN_UI_SCALE * 100) + "% and "
                    + (int) (MAX_UI_SCALE * 100) + "%. Keeping the previous value.");
        } else {
            Prefs.set(PREF_UI_SCALE_KEY, scalePercent / 100.0);
            Prefs.savePreferences();
        }
    }

    // ==== live RoiManager refresh ====

    private void startRoiPolling() {
        lastRoiSignature = roiPromptExtractor.signature(getRoiManager().getRoisAsArray(), axis);
        roiPollTimer = new Timer(ROI_POLL_INTERVAL_MS, e -> {
        if (imp.getWindow() == null) { // source image was closed - nothing left to configure
                onImageClosed();
                return;
            }
            String signature = roiPromptExtractor.signature(getRoiManager().getRoisAsArray(), axis);
            if (!signature.equals(lastRoiSignature)) {
                lastRoiSignature = signature;
                refreshGroupOptions();
            }
        });
        roiPollTimer.start();
    }

    private void refreshGroupOptions() {
        List<Sam3GroupOption> options = computeGroupOptions();
        positiveGroupEditor.setAvailableGroups(options);
        negativeGroupEditor.setAvailableGroups(options);

        // for Roi positive & negative prompts columns : update available ROI groups
        Set<Integer> availableGroupIds = new HashSet<>();
        for (Sam3GroupOption option : options) availableGroupIds.add(option.getGroupId());
        if (tableModel.reconcileVisualGroups(availableGroupIds)) {
            helpBar.showInfo(VISUAL_PROMPT_RESET_OWNER, "A previously selected ROI group is no longer available - "
                    + "visual prompt turned off for the affected concept(s).", DialogHelpBar.DEFAULT_NOTIFICATION_COLOR);
        }

        // for Roi positive & negative prompts columns : switch to unabled if no ROI at all
        tableModel.setRoiPromptsAvailable(!options.isEmpty());
        table.repaint();
    }

    /** Frames actually processed by the current dialog state: the whole stack, or just the current slice. */
    private List<Integer> currentFrameRange() {
        if (nFrames <= 1 || processStackCB == null || !processStackCB.isSelected()) {
            return Collections.singletonList(imp.getCurrentSlice());
        }
        List<Integer> frames = new ArrayList<>(nFrames);
        for (int f = 1; f <= nFrames; f++) frames.add(f);
        return frames;
    }

    /** One option per roi group with a usable ROI on at least one of the current frames. */
    private List<Sam3GroupOption> computeGroupOptions() {
        List<Integer> frames = currentFrameRange();
        Map<Integer, Integer> coveredFramesByGroup = roiPromptExtractor.countGroupCoverage(getRoiManager().getRoisAsArray(), axis, frames);

        List<Sam3GroupOption> options = new ArrayList<>();
        int totalFrames = frames.size();
        for (Map.Entry<Integer, Integer> entry : coveredFramesByGroup.entrySet()) {
            options.add(new Sam3GroupOption(entry.getKey(), entry.getValue(), totalFrames));
        }
        return options;
    }

    /** True when at least an output where a concept's output roi group matters is selected (all outputs except instance masks). */
    private boolean outputWithRoiGroupSelected() {
        return (addBoxRoisCB != null && addBoxRoisCB.isSelected())
                || (addShapeRoisCB != null && addShapeRoisCB.isSelected()
                || (semanticMaskCB != null && semanticMaskCB.isSelected()));
    }

    /** True when at least an output where a concept's output name matters is selected (roi outputs and instance mask per stack). */
    private boolean outputWithNameSelected() {
        return (addBoxRoisCB != null && addBoxRoisCB.isSelected())
                || (addShapeRoisCB != null && addShapeRoisCB.isSelected()
                || (instanceMaskPerClassCB != null && instanceMaskPerClassCB.isSelected()));
    }

    // ==== OK / Cancel ====

    private void onCancel() {
        approved = false;
        dispose();
    }

    /** Called when the source image is closed while the dialog is still open: cancel like {@link #onCancel()}. */
    private void onImageClosed() {
        approved = false;
        IJ.showStatus("SAM3: source image was closed - dialog cancelled");
        dispose();
    }

    private void onOk() {
        if (imp.getWindow() == null) {
            onImageClosed();
            return;
        }
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return;

        String path = resolveAndValidateModelPath();
        if (path == null) return; // does not close window, because "dispose()" is not called. Usr can try again

        // check concept validity
        List<Sam3Concept> currentConcepts = tableModel.getConcepts();
        List<String> errors = validateConcepts(currentConcepts);

        if (!errors.isEmpty()) {
            JOptionPane.showMessageDialog(this, String.join("\n", errors), "Invalid concept(s)", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // if no output is selected, print warning
        outputOptions = collectOutputOptions();
        if (outputOptions.noOutputSelected()) {
            int choice = JOptionPane.showOptionDialog(this,
                    "No output is selected (no ROI, no mask). The plugin will run but produce nothing except logs.\n"
                            + "Do you want to continue anyway?",
                    "No output selected",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    new Object[]{"Continue", "Return"},
                    "Return");
            if  (choice != 0) return; // 0 = the user clicked on "Continue"
        }

        modelPath = path;
        concepts = new ArrayList<>(currentConcepts);
        detectionParams = collectDetectionParams();
        stackMode = (nFrames > 1 && processStackCB.isSelected())
                ? DetectionUtils.DetectionMode.MULTI_IMAGE : DetectionUtils.DetectionMode.SINGLE_IMAGE;

        logCoverageWarnings(currentConcepts);

        approved = true;
        dispose();
    }

    private List<String> validateConcepts(List<Sam3Concept> currentConcepts) {
        List<String> errors = new ArrayList<>();
        if (currentConcepts.isEmpty()) {
            errors.add("At least one concept must be defined.");
            return errors;
        }
        Set<String> seenNames = new HashSet<>();
        for (Sam3Concept concept : currentConcepts) {
            if (!concept.hasAnyPositivePrompt()) {
                errors.add("Concept " + concept.getName() + ": needs a text prompt and/or a positive visual prompt.");
            }
            if (!concept.isNegativeGroupValid()) {
                errors.add("Concept " + concept.getName() + ": negative visual can't use the same group as the positive visual.");
            }
            if (!seenNames.add(concept.getName())) {
                errors.add("Duplicate concept name: " + concept.getName());
            }
        }
        return errors;
    }

    private String resolveAndValidateModelPath() {
        String path = modelPathField.getText().trim();
        if (Files.exists(Paths.get(path))) {
            Prefs.set(PREF_LAST_MODEL_KEY, path);
            Prefs.savePreferences();
            return path;
        }
        IJ.error("Selection Error", "The selected path is not valid:\n" + path);
        return null;
    }

    private Sam3ModelParameters collectDetectionParams() {
        double confidence = parseDouble(confidenceField.getText(), modelParams.getConfidenceThreshold());
        if (confidence < 0 || confidence > 1) {
            IJ.log("Confidence threshold must be between 0 and 1. Using default value: " + modelParams.getConfidenceThreshold());
            confidence = modelParams.getConfidenceThreshold();
        }
        modelParams.setConfidenceThreshold(confidence);

        return modelParams;
    }

    private double parseDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private DetectionUtils.OutputOptions collectOutputOptions() {
        DetectionUtils.OutputOptions options = new DetectionUtils.OutputOptions();
        options.deletePreviousRoi = false;
        options.addToRoiManagerBB = addBoxRoisCB.isSelected();
        options.addToRoiManagerShapes = addShapeRoisCB.isSelected();
        options.createInstanceMask = instanceMaskCB.isSelected();
        options.createSemanticMask = semanticMaskCB.isSelected();
        options.createInstanceMaskPerClass = instanceMaskPerClassCB.isSelected();
        return options;
    }

    /** Logs, per concept, which of the currently processed frames are missing a usable ROI for an enabled visual prompt. */
    private void logCoverageWarnings(List<Sam3Concept> currentConcepts) {
        Roi[] allRois = getRoiManager().getRoisAsArray();
        List<Integer> frames = currentFrameRange();

        for (Sam3Concept concept : currentConcepts) {
            if (concept.isPositiveVisualUsed()) {
                warnIfPartialCoverage(allRois, frames, concept.getPositiveVisualGroup(), concept, "positive");
            }
            if (concept.isNegativeVisualUsed()) {
                warnIfPartialCoverage(allRois, frames, concept.getNegativeVisualGroup(), concept, "negative");
            }
        }
    }

    private void warnIfPartialCoverage(Roi[] allRois, List<Integer> frames, int groupId, Sam3Concept concept, String kind) {
        List<Integer> missingFrames = roiPromptExtractor.findFramesMissingGroup(allRois, axis, groupId, frames);
        if (!missingFrames.isEmpty()) {
            IJ.log("WARNING: concept " + concept.getName() + " - " + kind + " group " + groupId
                    + " has no ROI on frame(s): " + missingFrames);
        }
    }
}