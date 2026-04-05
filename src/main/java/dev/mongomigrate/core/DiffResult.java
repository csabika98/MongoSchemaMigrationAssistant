package dev.mongomigrate.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Holds the complete result of a schema diff operation.
 */
public class DiffResult {

    private final List<DiffEntry> entries;
    private final String sourceLabel;
    private final String targetLabel;

    public DiffResult(String sourceLabel, String targetLabel) {
        this.entries = new ArrayList<>();
        this.sourceLabel = sourceLabel;
        this.targetLabel = targetLabel;
    }

    public void addEntry(DiffEntry entry) {
        entries.add(entry);
    }

    public List<DiffEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public String getSourceLabel() { return sourceLabel; }
    public String getTargetLabel() { return targetLabel; }

    public boolean hasDifferences() {
        return !entries.isEmpty();
    }

    public int totalChanges() {
        return entries.size();
    }

    public long countByType(DiffEntry.ChangeType type) {
        return entries.stream().filter(e -> e.getChangeType() == type).count();
    }

    /**
     * Returns a formatted summary string suitable for TUI display.
     */
    public String toFormattedSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Schema Diff: %s -> %s%n", sourceLabel, targetLabel));
        sb.append(String.format("Total changes: %d%n", totalChanges()));
        sb.append(String.format("  Added:         %d%n", countByType(DiffEntry.ChangeType.ADDED)));
        sb.append(String.format("  Removed:       %d%n", countByType(DiffEntry.ChangeType.REMOVED)));
        sb.append(String.format("  Renamed:       %d%n", countByType(DiffEntry.ChangeType.RENAMED)));
        sb.append(String.format("  Type changed:  %d%n", countByType(DiffEntry.ChangeType.TYPE_CHANGED)));
        sb.append(String.format("  Restructured:  %d%n", countByType(DiffEntry.ChangeType.RESTRUCTURED)));
        sb.append("\n");

        for (DiffEntry entry : entries) {
            sb.append("  ").append(entry.toSummary()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Returns the diff as a structured text block for AI prompt consumption.
     */
    public String toPromptContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("SCHEMA DIFFERENCES:\n");
        for (DiffEntry entry : entries) {
            sb.append(String.format("- %s | %s | source_type=%s | target_type=%s | %s\n",
                    entry.getChangeType(),
                    entry.getFieldPath(),
                    entry.getSourceType() != null ? entry.getSourceType() : "N/A",
                    entry.getTargetType() != null ? entry.getTargetType() : "N/A",
                    entry.getDescription() != null ? entry.getDescription() : ""));
        }
        return sb.toString();
    }
}
