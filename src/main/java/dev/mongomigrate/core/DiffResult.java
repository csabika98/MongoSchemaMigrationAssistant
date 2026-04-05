package dev.mongomigrate.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DiffResult {

    private final List<DiffEntry> entries;

    public DiffResult() {
        this.entries = new ArrayList<>();
    }

    public void addEntry(DiffEntry entry) {
        entries.add(entry);
    }

    public List<DiffEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public boolean hasDifferences() {
        return !entries.isEmpty();
    }

    public int totalChanges() {
        return entries.size();
    }

    public long countByType(DiffEntry.ChangeType type) {
        return entries.stream().filter(e -> e.getChangeType() == type).count();
    }

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
