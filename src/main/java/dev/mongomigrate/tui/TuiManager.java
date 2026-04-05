package dev.mongomigrate.tui;

import dev.mongomigrate.ai.ClaudeService;
import dev.mongomigrate.core.DiffEntry;
import dev.mongomigrate.core.DiffResult;
import dev.mongomigrate.core.SchemaDiffer;
import dev.mongomigrate.model.MigrationContext;
import org.jetbrains.annotations.NotNull;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.builtins.Completers.FileNameCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public class TuiManager {

    private Terminal terminal;
    private LineReader reader;
    private LineReader fileReader;
    private PrintWriter out;
    private final MigrationContext context;
    private final SchemaDiffer differ;
    private final ClaudeService claude;
    private volatile boolean running = true;

    public TuiManager() {
        this.context = new MigrationContext();
        this.differ = new SchemaDiffer();
        this.claude = new ClaudeService();
    }

    public void start() throws IOException {
        terminal = TerminalBuilder.builder()
                .system(true)
                .build();
        out = terminal.writer();

        reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new StringsCompleter("1", "2", "3", "4", "diff", "settings", "about", "quit"))
                .build();
        reader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);

        fileReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new FileNameCompleter())
                .build();
        fileReader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);

        printBanner();

        while (running) {
            showMainMenu();
        }

        out.println();
        printColored("Goodbye!", AttributedStyle.CYAN);
        terminal.close();
    }

    private void showMainMenu() {
        out.println();
        printSeparator();
        out.println("  1) diff       Schema Diff & Generate Migration Script");
        out.println("  2) settings   Settings");
        out.println("  3) about      About");
        out.println("  4) quit       Exit");
        out.println();

        try {
            String choice = reader.readLine(prompt("> ")).trim().toLowerCase();
            switch (choice) {
                case "1", "diff" -> runDiffWorkflow();
                case "2", "settings" -> showSettings();
                case "3", "about" -> showAbout();
                case "4", "quit", "q", "exit" -> running = false;
                default -> printColored("Unknown option. Enter 1-4.", AttributedStyle.RED);
            }
        } catch (UserInterruptException | EndOfFileException e) {
            running = false;
        }
    }


    private void runDiffWorkflow() {
        out.println();
        printHeader("Schema Diff");
        out.println("  How would you like to provide schemas?");
        out.println();
        out.println("  1) files    Enter file paths");
        out.println("  2) paste    Paste JSON directly");
        out.println("  3) sample   Use built-in sample data");
        out.println("  4) back     Return to main menu");
        out.println();

        try {
            String choice = reader.readLine(prompt("> ")).trim().toLowerCase();
            switch (choice) {
                case "1", "files" -> diffFromFiles();
                case "2", "paste" -> diffFromPaste();
                case "3", "sample" -> diffFromSample();
                case "4", "back" -> { return; }
                default -> {
                    printColored("Unknown option.", AttributedStyle.RED);
                    return;
                }
            }
        } catch (UserInterruptException | EndOfFileException e) {
            return;
        }

        if (context.getDiffResult() != null) {
            showDiffResults();
        }
    }

    private void diffFromFiles() {
        try {
            out.println();
            String sourcePath = fileReader.readLine(prompt("Source schema file: ")).trim();
            String targetPath = fileReader.readLine(prompt("Target schema file: ")).trim();

            if (sourcePath.isEmpty() || targetPath.isEmpty()) {
                printColored("Both file paths are required.", AttributedStyle.RED);
                return;
            }

            String defaultColl = context.getCollectionName();
            String collection = reader.readLine(prompt("Collection name [" + defaultColl + "]: ")).trim();
            if (!collection.isEmpty()) {
                context.setCollectionName(collection);
            }

            context.setSourceJson(Files.readString(Path.of(sourcePath)));
            context.setTargetJson(Files.readString(Path.of(targetPath)));

            DiffResult result = differ.diff(context.getSourceJson(), context.getTargetJson());
            context.setDiffResult(result);

        } catch (UserInterruptException | EndOfFileException e) {
            // user cancelled input
        } catch (IOException e) {
            printColored("Failed to read files: " + e.getMessage(), AttributedStyle.RED);
        }
    }

    private void diffFromPaste() {
        try {
            String sourceJson = readMultiLineJson("Paste SOURCE schema JSON (end with empty line):");
            if (sourceJson == null || sourceJson.isEmpty()) {
                printColored("No source JSON provided.", AttributedStyle.RED);
                return;
            }

            String targetJson = readMultiLineJson("Paste TARGET schema JSON (end with empty line):");
            if (targetJson == null || targetJson.isEmpty()) {
                printColored("No target JSON provided.", AttributedStyle.RED);
                return;
            }

            String defaultColl = context.getCollectionName();
            String collection = reader.readLine(prompt("Collection name [" + defaultColl + "]: ")).trim();
            if (!collection.isEmpty()) {
                context.setCollectionName(collection);
            }

            context.setSourceJson(sourceJson);
            context.setTargetJson(targetJson);

            DiffResult result = differ.diff(sourceJson, targetJson);
            context.setDiffResult(result);

        } catch (UserInterruptException | EndOfFileException e) {
            // user cancelled input
        } catch (IOException e) {
            printColored("Invalid JSON: " + e.getMessage(), AttributedStyle.RED);
        }
    }

    private void diffFromSample() {
        loadSampleData();
        try {
            DiffResult result = differ.diff(context.getSourceJson(), context.getTargetJson());
            context.setDiffResult(result);
            printColored("Loaded sample insurance policy schemas.", AttributedStyle.GREEN);
        } catch (IOException e) {
            printColored("Error loading sample: " + e.getMessage(), AttributedStyle.RED);
        }
    }


    private void showDiffResults() {
        DiffResult diff = context.getDiffResult();
        out.println();
        printHeader("Diff Results");

        if (!diff.hasDifferences()) {
            printColored("No differences found — schemas are identical.", AttributedStyle.GREEN);
            return;
        }

        out.println(new AttributedStringBuilder()
                .style(AttributedStyle.BOLD)
                .append(String.format("  Found %d change(s)  |  +%d  -%d  ~%d  T%d  R%d",
                        diff.totalChanges(),
                        diff.countByType(DiffEntry.ChangeType.ADDED),
                        diff.countByType(DiffEntry.ChangeType.REMOVED),
                        diff.countByType(DiffEntry.ChangeType.RENAMED),
                        diff.countByType(DiffEntry.ChangeType.TYPE_CHANGED),
                        diff.countByType(DiffEntry.ChangeType.RESTRUCTURED)))
                .toAnsi(terminal));
        out.println();

        for (DiffEntry entry : diff.getEntries()) {
            int color = colorForChangeType(entry.getChangeType());
            out.println(new AttributedStringBuilder()
                    .style(AttributedStyle.DEFAULT.foreground(color))
                    .append("  ")
                    .append(entry.toSummary())
                    .toAnsi(terminal));
        }
        out.println();

        scriptActionLoop();
    }

    private void scriptActionLoop() {
        while (true) {
            out.println("  1) generate   Generate migration script");
            out.println("  2) back       Return to main menu");
            out.println();

            try {
                String choice = reader.readLine(prompt("> ")).trim().toLowerCase();
                switch (choice) {
                    case "1", "generate" -> {
                        generateAndShowScript();
                        return;
                    }
                    case "2", "back" -> { return; }
                    default -> printColored("Unknown option.", AttributedStyle.RED);
                }
            } catch (UserInterruptException | EndOfFileException e) {
                return;
            }
        }
    }


    private void generateAndShowScript() {
        String script = generateWithSpinner();
        context.setGeneratedScript(script);
        showScriptResult();
    }

    private String generateWithSpinner() {
        String mode = claude.isAvailable() ? "Claude API" : "template";
        AtomicBoolean done = new AtomicBoolean(false);
        String[] result = new String[1];

        Thread spinner = getThread(done, mode);

        result[0] = claude.generateMigrationScript(
                context.getDiffResult(), context.getCollectionName(),
                context.getSourceJson(), context.getTargetJson()
        );

        done.set(true);
        try { spinner.join(1000); } catch (InterruptedException ignored) {}

        printColored("Done!", AttributedStyle.GREEN);
        out.println();
        return result[0];
    }

    @NotNull
    private Thread getThread(AtomicBoolean done, String mode) {
        Thread spinner = new Thread(() -> {
            String[] frames = {"\u28cb", "\u2819", "\u2839", "\u2838", "\u283c", "\u2834", "\u2826", "\u2827", "\u2807", "\u280f"};
            int i = 0;
            while (!done.get()) {
                out.print("\r  " + frames[i % frames.length] + " Generating migration script (" + mode + ")...");
                out.flush();
                i++;
                LockSupport.parkNanos(100_000_000L);
            }
            out.print("\r" + " ".repeat(70) + "\r");
            out.flush();
        });
        spinner.setDaemon(true);
        spinner.start();
        return spinner;
    }

    private void showScriptResult() {
        printHeader("Generated Migration Script");
        out.println(new AttributedStringBuilder()
                .style(AttributedStyle.BOLD)
                .append("  Collection: ")
                .append(context.getCollectionName())
                .append("  |  Mode: ")
                .append(claude.isAvailable() ? "AI-generated" : "Template")
                .toAnsi(terminal));
        printSeparator();

        printPaged(context.getGeneratedScript());

        scriptResultActionLoop();
    }

    private void scriptResultActionLoop() {
        while (true) {
            out.println();
            out.println("  1) save         Save script to file");
            out.println("  2) regenerate   Generate again");
            out.println("  3) menu         Return to main menu");
            out.println();

            try {
                String choice = reader.readLine(prompt("> ")).trim().toLowerCase();
                switch (choice) {
                    case "1", "save" -> saveScript();
                    case "2", "regenerate" -> {
                        generateAndShowScript();
                        return;
                    }
                    case "3", "menu" -> { return; }
                    default -> printColored("Unknown option.", AttributedStyle.RED);
                }
            } catch (UserInterruptException | EndOfFileException e) {
                return;
            }
        }
    }

    private void saveScript() {
        try {
            String defaultName = context.getCollectionName() + "_migration.js";
            String filename = fileReader.readLine(prompt("Save as [" + defaultName + "]: ")).trim();
            if (filename.isEmpty()) filename = defaultName;

            Path filePath = Path.of(filename);
            Files.writeString(filePath, context.getGeneratedScript());
            printColored("Saved to: " + filePath.toAbsolutePath(), AttributedStyle.GREEN);
        } catch (UserInterruptException | EndOfFileException e) {
            // cancelled
        } catch (IOException e) {
            printColored("Failed to save: " + e.getMessage(), AttributedStyle.RED);
        }
    }

    private void showSettings() {
        out.println();
        printHeader("Settings");

        try {
            String current = context.getCollectionName();
            String newName = reader.readLine(prompt("Collection name [" + current + "]: ")).trim();
            if (!newName.isEmpty()) {
                context.setCollectionName(newName);
                printColored("Collection name updated to: " + newName, AttributedStyle.GREEN);
            }
        } catch (UserInterruptException | EndOfFileException e) {
            // cancelled
        }

        out.println();
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            out.println("  API Key: " + apiKey.substring(0, Math.min(8, apiKey.length())) + "...");
        } else {
            printColored("  API Key: NOT SET", AttributedStyle.YELLOW);
            out.println("  Set via: export ANTHROPIC_API_KEY=sk-ant-...");
        }
        out.println();
    }

    private void showAbout() {
        out.println();
        printHeader("About");
        out.println("  MongoDB Migration Assistant v0.1.0");
        out.println();
        out.println("  AI-powered tool for generating MongoDB");
        out.println("  migration scripts from schema diffs.");
        out.println();
        out.println("  Built with Java + jline 3 + Claude API");
        out.println();
    }

    private void printBanner() {
        out.println();
        out.println(new AttributedStringBuilder()
                .style(AttributedStyle.BOLD.foreground(AttributedStyle.CYAN))
                .append("  ╔══════════════════════════════════════╗")
                .toAnsi(terminal));
        out.println(new AttributedStringBuilder()
                .style(AttributedStyle.BOLD.foreground(AttributedStyle.CYAN))
                .append("  ║   MongoDB Migration Assistant       ║")
                .toAnsi(terminal));
        out.println(new AttributedStringBuilder()
                .style(AttributedStyle.BOLD.foreground(AttributedStyle.CYAN))
                .append("  ║   AI-Powered Schema Diff & Migrate  ║")
                .toAnsi(terminal));
        out.println(new AttributedStringBuilder()
                .style(AttributedStyle.BOLD.foreground(AttributedStyle.CYAN))
                .append("  ╚══════════════════════════════════════╝")
                .toAnsi(terminal));
        out.println();

        if (claude.isAvailable()) {
            printColored("  Claude API: ✓ Connected", AttributedStyle.GREEN);
        } else {
            printColored("  Claude API: ✗ Not configured (set ANTHROPIC_API_KEY)", AttributedStyle.YELLOW);
        }
    }

    private void printHeader(String title) {
        out.println(new AttributedStringBuilder()
                .style(AttributedStyle.BOLD)
                .append("── ")
                .append(title)
                .append(" ")
                .style(AttributedStyle.DEFAULT)
                .append("─".repeat(Math.max(0, getWidth() - title.length() - 4)))
                .toAnsi(terminal));
    }

    private void printSeparator() {
        out.println("  " + "─".repeat(Math.max(0, getWidth() - 4)));
    }

    private void printColored(String msg, int fg) {
        out.println(new AttributedStringBuilder()
                .style(AttributedStyle.DEFAULT.foreground(fg))
                .append(msg)
                .toAnsi(terminal));
    }

    private String prompt(String text) {
        return new AttributedStringBuilder()
                .style(AttributedStyle.BOLD.foreground(AttributedStyle.CYAN))
                .append(text)
                .toAnsi(terminal);
    }

    private int getWidth() {
        int w = terminal.getWidth();
        return w > 0 ? w : 80;
    }

    private String readMultiLineJson(String label) {
        out.println();
        out.println("  " + label);
        out.println();
        StringBuilder sb = new StringBuilder();
        while (true) {
            try {
                String line = reader.readLine(prompt("| "));
                if (line.isEmpty()) {
                    String json = sb.toString().trim();
                    if (json.isEmpty()) continue;
                    break;
                }
                sb.append(line).append("\n");
            } catch (UserInterruptException e) {
                return null;
            } catch (EndOfFileException e) {
                break;
            }
        }
        return sb.toString().trim();
    }

    private void printPaged(String text) {
        String[] lines = text.split("\n");
        int pageSize = Math.max(terminal.getHeight() - 3, 20);
        for (int i = 0; i < lines.length; i++) {
            out.println(lines[i]);
            if ((i + 1) % pageSize == 0 && i + 1 < lines.length) {
                try {
                    String input = reader.readLine(prompt("-- MORE (Enter=next, q=stop) -- "));
                    if ("q".equalsIgnoreCase(input.trim())) break;
                } catch (UserInterruptException | EndOfFileException e) {
                    break;
                }
            }
        }
    }

    private int colorForChangeType(DiffEntry.ChangeType type) {
        return switch (type) {
            case ADDED -> AttributedStyle.GREEN;
            case REMOVED -> AttributedStyle.RED;
            case RENAMED -> AttributedStyle.YELLOW;
            case TYPE_CHANGED -> AttributedStyle.CYAN;
            case RESTRUCTURED -> AttributedStyle.MAGENTA;
        };
    }

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
