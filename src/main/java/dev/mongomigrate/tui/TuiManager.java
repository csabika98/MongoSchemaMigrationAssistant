package dev.mongomigrate.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.dialogs.*;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import dev.mongomigrate.ai.ClaudeService;
import dev.mongomigrate.core.DiffResult;
import dev.mongomigrate.core.SchemaDiffer;
import dev.mongomigrate.model.MigrationContext;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages the Lanterna terminal UI and screen flow.
 */
public class TuiManager {

    private Terminal terminal;
    private Screen screen;
    private MultiWindowTextGUI gui;
    private final MigrationContext context;
    private final SchemaDiffer differ;
    private final ClaudeService claude;

    public TuiManager() {
        this.context = new MigrationContext();
        this.differ = new SchemaDiffer();
        this.claude = new ClaudeService();
    }

    public void start() throws IOException {
        terminal = new DefaultTerminalFactory().createTerminal();
        screen = new TerminalScreen(terminal);
        screen.startScreen();

        gui = new MultiWindowTextGUI(screen, new DefaultWindowManager(), new EmptySpace(TextColor.ANSI.BLACK));

        showMainMenu();

        screen.stopScreen();
    }

    private void showMainMenu() {
        BasicWindow window = new BasicWindow("MongoDB Migration Assistant v0.1.0");
        window.setHints(java.util.Arrays.asList(Window.Hint.CENTERED));

        Panel mainPanel = new Panel();
        mainPanel.setLayoutManager(new LinearLayout(Direction.VERTICAL));
        mainPanel.setPreferredSize(new TerminalSize(60, 20));

        // Header
        mainPanel.addComponent(new EmptySpace(new TerminalSize(0, 1)));
        mainPanel.addComponent(new Label("  ╔══════════════════════════════════════╗"));
        mainPanel.addComponent(new Label("  ║   MongoDB Migration Assistant       ║"));
        mainPanel.addComponent(new Label("  ║   AI-Powered Schema Diff & Migrate  ║"));
        mainPanel.addComponent(new Label("  ╚══════════════════════════════════════╝"));
        mainPanel.addComponent(new EmptySpace(new TerminalSize(0, 1)));

        // API status
        String apiStatus = claude.isAvailable()
                ? "  Claude API: ✓ Connected"
                : "  Claude API: ✗ Not configured (set ANTHROPIC_API_KEY)";
        mainPanel.addComponent(new Label(apiStatus));
        mainPanel.addComponent(new EmptySpace(new TerminalSize(0, 1)));
        mainPanel.addComponent(new Separator(Direction.HORIZONTAL).setPreferredSize(new TerminalSize(56, 1)));
        mainPanel.addComponent(new EmptySpace(new TerminalSize(0, 1)));

        // Menu buttons
        Button diffButton = new Button("  1. Schema Diff & Generate Migration Script  ", () -> {
            window.close();
            showSchemaInputScreen();
        });

        Button settingsButton = new Button("  2. Settings                                  ", () -> {
            window.close();
            showSettingsScreen();
        });

        Button aboutButton = new Button("  3. About                                     ", () -> {
            MessageDialog.showMessageDialog(gui, "About",
                    "MongoDB Migration Assistant v0.1.0\n\n" +
                    "AI-powered tool for generating MongoDB\n" +
                    "migration scripts from schema diffs.\n\n" +
                    "Built with Java + Lanterna + Claude API");
        });

        Button quitButton = new Button("  4. Quit                                      ", () -> {
            window.close();
        });

        mainPanel.addComponent(diffButton);
        mainPanel.addComponent(new EmptySpace(new TerminalSize(0, 0)));
        mainPanel.addComponent(settingsButton);
        mainPanel.addComponent(new EmptySpace(new TerminalSize(0, 0)));
        mainPanel.addComponent(aboutButton);
        mainPanel.addComponent(new EmptySpace(new TerminalSize(0, 0)));
        mainPanel.addComponent(quitButton);

        window.setComponent(mainPanel);
        gui.addWindowAndWait(window);
    }

    private void showSchemaInputScreen() {
        BasicWindow window = new BasicWindow("Schema Input");
        window.setHints(java.util.Arrays.asList(Window.Hint.CENTERED));

        Panel panel = new Panel();
        panel.setLayoutManager(new LinearLayout(Direction.VERTICAL));
        panel.setPreferredSize(new TerminalSize(65, 22));

        panel.addComponent(new Label("Enter paths to source and target schema JSON files:"));
        panel.addComponent(new EmptySpace(new TerminalSize(0, 1)));

        // Source file input
        panel.addComponent(new Label("Source schema (current):"));
        TextBox sourceInput = new TextBox(new TerminalSize(60, 1));
        sourceInput.setText(context.getSourceFilePath() != null ? context.getSourceFilePath() : "");
        panel.addComponent(sourceInput);

        panel.addComponent(new EmptySpace(new TerminalSize(0, 0)));

        // Target file input
        panel.addComponent(new Label("Target schema (desired):"));
        TextBox targetInput = new TextBox(new TerminalSize(60, 1));
        targetInput.setText(context.getTargetFilePath() != null ? context.getTargetFilePath() : "");
        panel.addComponent(targetInput);

        panel.addComponent(new EmptySpace(new TerminalSize(0, 0)));

        // Collection name
        panel.addComponent(new Label("Collection name:"));
        TextBox collectionInput = new TextBox(new TerminalSize(60, 1));
        collectionInput.setText(context.getCollectionName());
        panel.addComponent(collectionInput);

        panel.addComponent(new EmptySpace(new TerminalSize(0, 1)));
        panel.addComponent(new Separator(Direction.HORIZONTAL).setPreferredSize(new TerminalSize(60, 1)));
        panel.addComponent(new EmptySpace(new TerminalSize(0, 0)));

        // Or paste JSON directly
        panel.addComponent(new Label("— OR paste JSON directly (leave file paths empty) —"));

        panel.addComponent(new EmptySpace(new TerminalSize(0, 1)));

        // Action buttons
        Panel buttonPanel = new Panel(new LinearLayout(Direction.HORIZONTAL));

        buttonPanel.addComponent(new Button("Run Diff", () -> {
            String sourcePath = sourceInput.getText().trim();
            String targetPath = targetInput.getText().trim();
            String collection = collectionInput.getText().trim();

            if (!collection.isEmpty()) {
                context.setCollectionName(collection);
            }

            try {
                if (!sourcePath.isEmpty() && !targetPath.isEmpty()) {
                    // File-based input
                    context.setSourceFilePath(sourcePath);
                    context.setTargetFilePath(targetPath);
                    context.setSourceJson(Files.readString(Path.of(sourcePath)));
                    context.setTargetJson(Files.readString(Path.of(targetPath)));
                } else {
                    // No files — show paste dialog
                    window.close();
                    showJsonPasteScreen();
                    return;
                }

                DiffResult result = differ.diff(
                        context.getSourceJson(), context.getTargetJson(),
                        new File(sourcePath).getName(), new File(targetPath).getName()
                );
                context.setDiffResult(result);

                window.close();
                showDiffResultScreen();

            } catch (IOException e) {
                MessageDialog.showMessageDialog(gui, "Error",
                        "Failed to read files:\n" + e.getMessage());
            }
        }));

        buttonPanel.addComponent(new Button("Paste JSON", () -> {
            window.close();
            showJsonPasteScreen();
        }));

        buttonPanel.addComponent(new Button("Use Sample", () -> {
            loadSampleData();
            try {
                DiffResult result = differ.diff(
                        context.getSourceJson(), context.getTargetJson(),
                        "sample_v1.json", "sample_v2.json"
                );
                context.setDiffResult(result);
                window.close();
                showDiffResultScreen();
            } catch (IOException e) {
                MessageDialog.showMessageDialog(gui, "Error", e.getMessage());
            }
        }));

        buttonPanel.addComponent(new Button("Back", () -> {
            window.close();
            showMainMenu();
        }));

        panel.addComponent(buttonPanel);
        window.setComponent(panel);
        gui.addWindowAndWait(window);
    }

    private void showJsonPasteScreen() {
        BasicWindow window = new BasicWindow("Paste JSON Schemas");
        window.setHints(java.util.Arrays.asList(Window.Hint.CENTERED));

        Panel panel = new Panel();
        panel.setLayoutManager(new LinearLayout(Direction.VERTICAL));

        panel.addComponent(new Label("Paste SOURCE schema JSON:"));
        TextBox sourceBox = new TextBox(new TerminalSize(70, 8), TextBox.Style.MULTI_LINE);
        panel.addComponent(sourceBox);

        panel.addComponent(new EmptySpace(new TerminalSize(0, 1)));

        panel.addComponent(new Label("Paste TARGET schema JSON:"));
        TextBox targetBox = new TextBox(new TerminalSize(70, 8), TextBox.Style.MULTI_LINE);
        panel.addComponent(targetBox);

        panel.addComponent(new EmptySpace(new TerminalSize(0, 1)));

        Panel buttonPanel = new Panel(new LinearLayout(Direction.HORIZONTAL));
        buttonPanel.addComponent(new Button("Run Diff", () -> {
            try {
                context.setSourceJson(sourceBox.getText());
                context.setTargetJson(targetBox.getText());

                DiffResult result = differ.diff(
                        context.getSourceJson(), context.getTargetJson(),
                        "pasted_source", "pasted_target"
                );
                context.setDiffResult(result);
                window.close();
                showDiffResultScreen();
            } catch (Exception e) {
                MessageDialog.showMessageDialog(gui, "Error",
                        "Invalid JSON:\n" + e.getMessage());
            }
        }));

        buttonPanel.addComponent(new Button("Back", () -> {
            window.close();
            showSchemaInputScreen();
        }));

        panel.addComponent(buttonPanel);
        window.setComponent(panel);
        gui.addWindowAndWait(window);
    }

    private void showDiffResultScreen() {
        DiffResult diff = context.getDiffResult();
        BasicWindow window = new BasicWindow("Schema Diff Results");
        window.setHints(java.util.Arrays.asList(Window.Hint.CENTERED));

        Panel panel = new Panel();
        panel.setLayoutManager(new LinearLayout(Direction.VERTICAL));
        panel.setPreferredSize(new TerminalSize(75, 25));

        if (!diff.hasDifferences()) {
            panel.addComponent(new Label("✓ No differences found — schemas are identical."));
            panel.addComponent(new EmptySpace(new TerminalSize(0, 1)));
            panel.addComponent(new Button("Back", () -> {
                window.close();
                showMainMenu();
            }));
        } else {
            // Summary header
            panel.addComponent(new Label(String.format(
                    "Found %d change(s)  |  +%d  -%d  ~%d  T%d  R%d",
                    diff.totalChanges(),
                    diff.countByType(dev.mongomigrate.core.DiffEntry.ChangeType.ADDED),
                    diff.countByType(dev.mongomigrate.core.DiffEntry.ChangeType.REMOVED),
                    diff.countByType(dev.mongomigrate.core.DiffEntry.ChangeType.RENAMED),
                    diff.countByType(dev.mongomigrate.core.DiffEntry.ChangeType.TYPE_CHANGED),
                    diff.countByType(dev.mongomigrate.core.DiffEntry.ChangeType.RESTRUCTURED)
            )));
            panel.addComponent(new Separator(Direction.HORIZONTAL).setPreferredSize(new TerminalSize(72, 1)));

            // Diff entries in a scrollable text box
            TextBox diffView = new TextBox(new TerminalSize(72, 14), TextBox.Style.MULTI_LINE);
            diffView.setReadOnly(true);
            diffView.setText(diff.toFormattedSummary());
            panel.addComponent(diffView);

            panel.addComponent(new EmptySpace(new TerminalSize(0, 1)));

            // Action buttons
            Panel buttonPanel = new Panel(new LinearLayout(Direction.HORIZONTAL));

            buttonPanel.addComponent(new Button("Generate Script", () -> {
                window.close();
                showGeneratingScreen();
            }));

            buttonPanel.addComponent(new Button("Back", () -> {
                window.close();
                showSchemaInputScreen();
            }));

            buttonPanel.addComponent(new Button("Main Menu", () -> {
                window.close();
                showMainMenu();
            }));

            panel.addComponent(buttonPanel);
        }

        window.setComponent(panel);
        gui.addWindowAndWait(window);
    }

    private void showGeneratingScreen() {
        // Show a "generating..." message, then produce the script
        BasicWindow loadingWindow = new BasicWindow("Generating...");
        loadingWindow.setHints(java.util.Arrays.asList(Window.Hint.CENTERED));
        Panel loadingPanel = new Panel();
        loadingPanel.addComponent(new Label(claude.isAvailable()
                ? "Calling Claude API to generate migration script..."
                : "Generating migration script (template mode)..."));
        loadingPanel.addComponent(new Label("Please wait..."));
        loadingWindow.setComponent(loadingPanel);

        // Run generation in background
        Thread genThread = new Thread(() -> {
            String script = claude.generateMigrationScript(
                    context.getDiffResult(),
                    context.getCollectionName(),
                    context.getSourceJson(),
                    context.getTargetJson()
            );
            context.setGeneratedScript(script);

            gui.getGUIThread().invokeLater(() -> {
                loadingWindow.close();
                showScriptResultScreen();
            });
        });

        genThread.setDaemon(true);
        genThread.start();

        gui.addWindowAndWait(loadingWindow);
    }

    private void showScriptResultScreen() {
        BasicWindow window = new BasicWindow("Generated Migration Script");
        window.setHints(java.util.Arrays.asList(Window.Hint.CENTERED));

        Panel panel = new Panel();
        panel.setLayoutManager(new LinearLayout(Direction.VERTICAL));
        panel.setPreferredSize(new TerminalSize(80, 28));

        panel.addComponent(new Label(String.format("Collection: %s  |  Mode: %s",
                context.getCollectionName(),
                claude.isAvailable() ? "AI-generated" : "Template")));
        panel.addComponent(new Separator(Direction.HORIZONTAL).setPreferredSize(new TerminalSize(78, 1)));

        // Script view
        TextBox scriptView = new TextBox(new TerminalSize(78, 20), TextBox.Style.MULTI_LINE);
        scriptView.setReadOnly(true);
        scriptView.setText(context.getGeneratedScript());
        panel.addComponent(scriptView);

        panel.addComponent(new EmptySpace(new TerminalSize(0, 1)));

        // Action buttons
        Panel buttonPanel = new Panel(new LinearLayout(Direction.HORIZONTAL));

        buttonPanel.addComponent(new Button("Save to File", () -> {
            String filename = context.getCollectionName() + "_migration.js";
            try {
                Files.writeString(Path.of(filename), context.getGeneratedScript());
                MessageDialog.showMessageDialog(gui, "Saved",
                        "Script saved to: " + Path.of(filename).toAbsolutePath());
            } catch (IOException e) {
                MessageDialog.showMessageDialog(gui, "Error",
                        "Failed to save: " + e.getMessage());
            }
        }));

        buttonPanel.addComponent(new Button("Regenerate", () -> {
            window.close();
            showGeneratingScreen();
        }));

        buttonPanel.addComponent(new Button("Back to Diff", () -> {
            window.close();
            showDiffResultScreen();
        }));

        buttonPanel.addComponent(new Button("Main Menu", () -> {
            window.close();
            showMainMenu();
        }));

        panel.addComponent(buttonPanel);
        window.setComponent(panel);
        gui.addWindowAndWait(window);
    }

    private void showSettingsScreen() {
        BasicWindow window = new BasicWindow("Settings");
        window.setHints(java.util.Arrays.asList(Window.Hint.CENTERED));

        Panel panel = new Panel();
        panel.setLayoutManager(new LinearLayout(Direction.VERTICAL));
        panel.setPreferredSize(new TerminalSize(55, 12));

        panel.addComponent(new Label("Default collection name:"));
        TextBox collInput = new TextBox(new TerminalSize(50, 1));
        collInput.setText(context.getCollectionName());
        panel.addComponent(collInput);

        panel.addComponent(new EmptySpace(new TerminalSize(0, 1)));

        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        panel.addComponent(new Label("API Key: " +
                (apiKey != null ? apiKey.substring(0, Math.min(8, apiKey.length())) + "..." : "NOT SET")));
        panel.addComponent(new Label("Set via: export ANTHROPIC_API_KEY=sk-ant-..."));

        panel.addComponent(new EmptySpace(new TerminalSize(0, 1)));

        Panel buttonPanel = new Panel(new LinearLayout(Direction.HORIZONTAL));
        buttonPanel.addComponent(new Button("Save", () -> {
            context.setCollectionName(collInput.getText().trim());
            window.close();
            showMainMenu();
        }));
        buttonPanel.addComponent(new Button("Cancel", () -> {
            window.close();
            showMainMenu();
        }));

        panel.addComponent(buttonPanel);
        window.setComponent(panel);
        gui.addWindowAndWait(window);
    }

    /**
     * Loads sample source/target schemas for quick demo.
     */
    private void loadSampleData() {
        context.setCollectionName("insurance_policies");
        context.setSourceJson("""
                {
                  "_id": "507f1f77bcf86cd799439011",
                  "policyNumber": "POL-2024-001",
                  "holderName": "John Doe",
                  "premium": "1250.50",
                  "startDate": "2024-01-15T00:00:00Z",
                  "endDate": "2025-01-15T00:00:00Z",
                  "status": "active",
                  "coverage": {
                    "type": "comprehensive",
                    "maxAmount": "500000",
                    "deductible": "1000"
                  },
                  "agentId": "agent_042",
                  "notes": "VIP client, handle with care",
                  "createdAt": "2024-01-10T09:30:00Z"
                }
                """);
        context.setTargetJson("""
                {
                  "_id": "507f1f77bcf86cd799439011",
                  "policyNumber": "POL-2024-001",
                  "policyHolder": {
                    "name": "John Doe",
                    "email": "",
                    "phone": ""
                  },
                  "premium": 1250.50,
                  "effectiveDate": "2024-01-15T00:00:00Z",
                  "expirationDate": "2025-01-15T00:00:00Z",
                  "status": "active",
                  "coverage": {
                    "type": "comprehensive",
                    "maxAmount": 500000,
                    "deductible": 1000,
                    "riders": []
                  },
                  "assignedAgent": "agent_042",
                  "tags": [],
                  "audit": {
                    "createdAt": "2024-01-10T09:30:00Z",
                    "updatedAt": "",
                    "version": 1
                  }
                }
                """);
    }
}
