package dev.mongomigrate.core;

/**
 * Represents a single difference found between two MongoDB document schemas.
 */
public class DiffEntry {

    public enum ChangeType {
        ADDED,      // Field exists in target but not source
        REMOVED,    // Field exists in source but not target
        RENAMED,    // Field name changed (detected by similar structure)
        TYPE_CHANGED, // Field type changed (e.g., String -> Date, String -> Decimal128)
        RESTRUCTURED  // Nested structure changed (e.g., flat -> nested, array changes)
    }

    private final String fieldPath;
    private final ChangeType changeType;
    private final String sourceType;
    private final String targetType;
    private final String sourceFieldName; // for RENAMED entries
    private final String targetFieldName; // for RENAMED entries
    private final String description;

    private DiffEntry(Builder builder) {
        this.fieldPath = builder.fieldPath;
        this.changeType = builder.changeType;
        this.sourceType = builder.sourceType;
        this.targetType = builder.targetType;
        this.sourceFieldName = builder.sourceFieldName;
        this.targetFieldName = builder.targetFieldName;
        this.description = builder.description;
    }

    public String getFieldPath() { return fieldPath; }
    public ChangeType getChangeType() { return changeType; }
    public String getSourceType() { return sourceType; }
    public String getTargetType() { return targetType; }
    public String getSourceFieldName() { return sourceFieldName; }
    public String getTargetFieldName() { return targetFieldName; }
    public String getDescription() { return description; }

    /**
     * Returns a human-readable summary of this diff entry.
     */
    public String toSummary() {
        return switch (changeType) {
            case ADDED -> String.format("[+] %s (%s)", fieldPath, targetType);
            case REMOVED -> String.format("[-] %s (%s)", fieldPath, sourceType);
            case RENAMED -> String.format("[~] %s -> %s", sourceFieldName, targetFieldName);
            case TYPE_CHANGED -> String.format("[T] %s: %s -> %s", fieldPath, sourceType, targetType);
            case RESTRUCTURED -> String.format("[R] %s: %s", fieldPath, description);
        };
    }

    /**
     * Returns the color hint for TUI rendering.
     */
    public String getColorHint() {
        return switch (changeType) {
            case ADDED -> "GREEN";
            case REMOVED -> "RED";
            case RENAMED -> "YELLOW";
            case TYPE_CHANGED -> "CYAN";
            case RESTRUCTURED -> "MAGENTA";
        };
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String fieldPath;
        private ChangeType changeType;
        private String sourceType;
        private String targetType;
        private String sourceFieldName;
        private String targetFieldName;
        private String description;

        public Builder fieldPath(String fieldPath) { this.fieldPath = fieldPath; return this; }
        public Builder changeType(ChangeType changeType) { this.changeType = changeType; return this; }
        public Builder sourceType(String sourceType) { this.sourceType = sourceType; return this; }
        public Builder targetType(String targetType) { this.targetType = targetType; return this; }
        public Builder sourceFieldName(String name) { this.sourceFieldName = name; return this; }
        public Builder targetFieldName(String name) { this.targetFieldName = name; return this; }
        public Builder description(String description) { this.description = description; return this; }

        public DiffEntry build() {
            if (fieldPath == null || changeType == null) {
                throw new IllegalStateException("fieldPath and changeType are required");
            }
            return new DiffEntry(this);
        }
    }
}
