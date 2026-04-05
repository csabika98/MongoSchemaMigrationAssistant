package dev.mongomigrate.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.TextBlock;
import dev.mongomigrate.core.DiffEntry;
import dev.mongomigrate.core.DiffResult;

/**
 * Integrates with the Anthropic Claude API to generate MongoDB migration scripts
 * based on schema diff results.
 */
public class ClaudeService {

    private static final String SYSTEM_PROMPT = """
            You are a MongoDB migration script expert. You generate production-ready mongosh
            migration scripts based on schema differences between document versions.
            
            RULES:
            - Use updateMany() with aggregation pipeline syntax for complex transformations
            - Use $set for adding new fields with sensible defaults
            - Use $unset for removing fields
            - Use $rename for renamed fields
            - For type conversions (String -> Date, String -> Decimal128, String -> Int), use
              aggregation pipeline updates with $convert or $toDate / $toDecimal / $toInt
            - Always wrap in a transaction-like pattern with try/catch
            - Add a comment header with migration metadata
            - Include a count check before and after migration
            - Generate a ROLLBACK script as a separate section
            - Handle null/missing values gracefully in all operations
            - For large collections, include batch processing with a configurable batch size
            - Add print statements for progress tracking
            
            OUTPUT FORMAT:
            Return ONLY the migration script as valid JavaScript/mongosh code.
            Start with a comment block describing the migration.
            Include both FORWARD and ROLLBACK sections clearly separated.
            """;

    private final AnthropicClient client;
    private boolean available;

    public ClaudeService() {
        AnthropicClient tempClient = null;
        boolean isAvailable = false;

        try {
            String apiKey = System.getenv("ANTHROPIC_API_KEY");
            if (apiKey != null && !apiKey.isBlank()) {
                tempClient = AnthropicOkHttpClient.builder()
                        .apiKey(apiKey)
                        .build();
                isAvailable = true;
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not initialize Claude client: " + e.getMessage());
        }

        this.client = tempClient;
        this.available = isAvailable;
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Generates a migration script based on schema diff results.
     *
     * @param diffResult     The computed schema differences
     * @param collectionName The target MongoDB collection name
     * @param sourceJson     The source schema JSON (for context)
     * @param targetJson     The target schema JSON (for context)
     * @return Generated mongosh migration script
     */
    public String generateMigrationScript(DiffResult diffResult, String collectionName,
                                          String sourceJson, String targetJson) {
        if (!available) {
            return generateFallbackScript(diffResult, collectionName);
        }

        String userPrompt = buildPrompt(diffResult, collectionName, sourceJson, targetJson);

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.CLAUDE_SONNET_4_20250514)
                    .maxTokens(4096L)
                    .system(SYSTEM_PROMPT)
                    .addUserMessage(userPrompt)
                    .build();

            Message response = client.messages().create(params);

            // Extract text from response content blocks
            StringBuilder scriptBuilder = new StringBuilder();
            for (ContentBlock block : response.content()) {
                if (block instanceof TextBlock textBlock) {
                    scriptBuilder.append(textBlock.text());
                }
            }

            return scriptBuilder.toString();

        } catch (Exception e) {
            return "// ERROR: Failed to generate script via Claude API\n"
                    + "// Reason: " + e.getMessage() + "\n\n"
                    + generateFallbackScript(diffResult, collectionName);
        }
    }

    private String buildPrompt(DiffResult diffResult, String collectionName,
                               String sourceJson, String targetJson) {
        return String.format("""
                Generate a MongoDB migration script for collection: %s
                
                SOURCE SCHEMA (current):
                ```json
                %s
                ```
                
                TARGET SCHEMA (desired):
                ```json
                %s
                ```
                
                DETECTED DIFFERENCES:
                %s
                
                Generate the complete mongosh migration script with forward migration and rollback.
                """,
                collectionName,
                truncate(sourceJson, 3000),
                truncate(targetJson, 3000),
                diffResult.toPromptContext()
        );
    }

    /**
     * Generates a basic migration script without AI — used as fallback when
     * the API key is not configured or the API call fails.
     */
    public String generateFallbackScript(DiffResult diffResult, String collectionName) {
        StringBuilder sb = new StringBuilder();
        sb.append("// ============================================\n");
        sb.append("// MongoDB Migration Script (auto-generated)\n");
        sb.append(String.format("// Collection: %s%n", collectionName));
        sb.append(String.format("// Changes: %d%n", diffResult.totalChanges()));
        sb.append("// Generated by: mongo-migration-assistant\n");
        sb.append("// ============================================\n\n");

        sb.append(String.format("const collection = db.getCollection('%s');%n", collectionName));
        sb.append("const beforeCount = collection.countDocuments();\n");
        sb.append("print(`Documents before migration: ${beforeCount}`);\n\n");

        sb.append("// === FORWARD MIGRATION ===\n\n");

        for (DiffEntry entry : diffResult.getEntries()) {
            sb.append(String.format("// %s%n", entry.toSummary()));
            switch (entry.getChangeType()) {
                case ADDED -> {
                    String defaultVal = inferDefault(entry.getTargetType());
                    sb.append(String.format("collection.updateMany(%n"));
                    sb.append(String.format("  { '%s': { $exists: false } },%n", entry.getFieldPath()));
                    sb.append(String.format("  { $set: { '%s': %s } }%n", entry.getFieldPath(), defaultVal));
                    sb.append(");\n\n");
                }
                case REMOVED -> {
                    sb.append(String.format("collection.updateMany(%n"));
                    sb.append(String.format("  { '%s': { $exists: true } },%n", entry.getFieldPath()));
                    sb.append(String.format("  { $unset: { '%s': '' } }%n", entry.getFieldPath()));
                    sb.append(");\n\n");
                }
                case RENAMED -> {
                    sb.append(String.format("collection.updateMany(%n"));
                    sb.append(String.format("  { '%s': { $exists: true } },%n", entry.getSourceFieldName()));
                    sb.append(String.format("  { $rename: { '%s': '%s' } }%n",
                            entry.getSourceFieldName(), entry.getTargetFieldName()));
                    sb.append(");\n\n");
                }
                case TYPE_CHANGED -> {
                    sb.append(generateTypeConversion(entry, collectionName));
                    sb.append("\n");
                }
                case RESTRUCTURED -> {
                    sb.append("// TODO: Manual review required for structural changes\n");
                    sb.append(String.format("// Field: %s%n", entry.getFieldPath()));
                    sb.append(String.format("// Description: %s%n%n", entry.getDescription()));
                }
            }
        }

        sb.append("const afterCount = collection.countDocuments();\n");
        sb.append("print(`Documents after migration: ${afterCount}`);\n");
        sb.append("print(`Migration complete.`);\n\n");

        // Rollback section
        sb.append("// === ROLLBACK ===\n");
        sb.append("// Uncomment the section below to reverse this migration\n");
        sb.append("/*\n");
        for (DiffEntry entry : diffResult.getEntries()) {
            switch (entry.getChangeType()) {
                case ADDED -> sb.append(String.format(
                        "collection.updateMany({}, { $unset: { '%s': '' } });%n", entry.getFieldPath()));
                case REMOVED -> sb.append(String.format(
                        "// Cannot restore removed field '%s' — data is lost%n", entry.getFieldPath()));
                case RENAMED -> sb.append(String.format(
                        "collection.updateMany({ '%s': { $exists: true } }, { $rename: { '%s': '%s' } });%n",
                        entry.getTargetFieldName(), entry.getTargetFieldName(), entry.getSourceFieldName()));
                case TYPE_CHANGED -> sb.append(String.format(
                        "// TODO: Reverse type conversion for '%s' (%s -> %s)%n",
                        entry.getFieldPath(), entry.getTargetType(), entry.getSourceType()));
                default -> sb.append(String.format("// TODO: Reverse restructure for '%s'%n", entry.getFieldPath()));
            }
        }
        sb.append("*/\n");

        return sb.toString();
    }

    private String generateTypeConversion(DiffEntry entry, String collectionName) {
        String field = entry.getFieldPath();
        String targetType = entry.getTargetType();

        if (targetType != null && targetType.contains("Date")) {
            return String.format("""
                    collection.updateMany(
                      { '%s': { $exists: true, $type: 'string' } },
                      [{ $set: { '%s': { $toDate: '$%s' } } }]
                    );
                    """, field, field, field);
        } else if (targetType != null && targetType.contains("Decimal")) {
            return String.format("""
                    collection.updateMany(
                      { '%s': { $exists: true, $type: 'string' } },
                      [{ $set: { '%s': { $toDecimal: '$%s' } } }]
                    );
                    """, field, field, field);
        } else if (targetType != null && targetType.contains("Int")) {
            return String.format("""
                    collection.updateMany(
                      { '%s': { $exists: true, $type: 'string' } },
                      [{ $set: { '%s': { $toInt: '$%s' } } }]
                    );
                    """, field, field, field);
        } else {
            return String.format("// TODO: Type conversion for '%s': %s -> %s\n",
                    field, entry.getSourceType(), targetType);
        }
    }

    private String inferDefault(String type) {
        if (type == null) return "null";
        return switch (type) {
            case "String" -> "''";
            case "Integer", "Double" -> "0";
            case "Boolean" -> "false";
            case "Array" -> "[]";
            case "Object" -> "{}";
            case "Null" -> "null";
            default -> "null";
        };
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "\n// ... (truncated)" : text;
    }
}
