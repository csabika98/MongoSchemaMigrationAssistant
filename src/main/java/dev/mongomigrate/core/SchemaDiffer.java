package dev.mongomigrate.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Compares two MongoDB document schemas (as JSON) and produces a DiffResult.
 * <p>
 * Handles nested objects, arrays, type detection, and field renames.
 */
public class SchemaDiffer {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Diff two JSON files representing MongoDB document schemas.
     */
    public DiffResult diff(File sourceFile, File targetFile) throws IOException {
        JsonNode source = mapper.readTree(sourceFile);
        JsonNode target = mapper.readTree(targetFile);
        return diff(source, target, sourceFile.getName(), targetFile.getName());
    }

    /**
     * Diff two JSON strings representing MongoDB document schemas.
     */
    public DiffResult diff(String sourceJson, String targetJson,
                           String sourceLabel, String targetLabel) throws IOException {
        JsonNode source = mapper.readTree(sourceJson);
        JsonNode target = mapper.readTree(targetJson);
        return diff(source, target, sourceLabel, targetLabel);
    }

    /**
     * Core diff logic operating on parsed JSON nodes.
     */
    public DiffResult diff(JsonNode source, JsonNode target,
                           String sourceLabel, String targetLabel) {
        DiffResult result = new DiffResult(sourceLabel, targetLabel);
        compareNodes(source, target, "", result);
        return result;
    }

    private void compareNodes(JsonNode source, JsonNode target, String path, DiffResult result) {
        if (source.isObject() && target.isObject()) {
            compareObjects((ObjectNode) source, (ObjectNode) target, path, result);
        } else if (source.isArray() && target.isArray()) {
            compareArrays((ArrayNode) source, (ArrayNode) target, path, result);
        } else if (!source.getNodeType().equals(target.getNodeType())) {
            result.addEntry(DiffEntry.builder()
                    .fieldPath(path.isEmpty() ? "(root)" : path)
                    .changeType(DiffEntry.ChangeType.TYPE_CHANGED)
                    .sourceType(describeType(source))
                    .targetType(describeType(target))
                    .description("Node type changed")
                    .build());
        }
    }

    private void compareObjects(ObjectNode source, ObjectNode target, String basePath, DiffResult result) {
        Set<String> sourceFields = new LinkedHashSet<>();
        Set<String> targetFields = new LinkedHashSet<>();
        source.fieldNames().forEachRemaining(sourceFields::add);
        target.fieldNames().forEachRemaining(targetFields::add);

        // Fields in both
        Set<String> common = new LinkedHashSet<>(sourceFields);
        common.retainAll(targetFields);

        // Fields only in source (removed)
        Set<String> removed = new LinkedHashSet<>(sourceFields);
        removed.removeAll(targetFields);

        // Fields only in target (added)
        Set<String> added = new LinkedHashSet<>(targetFields);
        added.removeAll(sourceFields);

        // Check for potential renames: removed field with similar structure to added field
        Map<String, String> renames = detectRenames(source, target, removed, added);

        // Process renames
        for (Map.Entry<String, String> rename : renames.entrySet()) {
            String oldName = rename.getKey();
            String newName = rename.getValue();
            String fullPath = buildPath(basePath, oldName);
            result.addEntry(DiffEntry.builder()
                    .fieldPath(fullPath)
                    .changeType(DiffEntry.ChangeType.RENAMED)
                    .sourceFieldName(oldName)
                    .targetFieldName(newName)
                    .sourceType(describeType(source.get(oldName)))
                    .targetType(describeType(target.get(newName)))
                    .description(String.format("Field renamed: %s -> %s", oldName, newName))
                    .build());
            removed.remove(oldName);
            added.remove(newName);
        }

        // Process removed fields
        for (String field : removed) {
            String fullPath = buildPath(basePath, field);
            result.addEntry(DiffEntry.builder()
                    .fieldPath(fullPath)
                    .changeType(DiffEntry.ChangeType.REMOVED)
                    .sourceType(describeType(source.get(field)))
                    .description("Field removed")
                    .build());
        }

        // Process added fields
        for (String field : added) {
            String fullPath = buildPath(basePath, field);
            result.addEntry(DiffEntry.builder()
                    .fieldPath(fullPath)
                    .changeType(DiffEntry.ChangeType.ADDED)
                    .targetType(describeType(target.get(field)))
                    .description("Field added")
                    .build());
        }

        // Recurse into common fields
        for (String field : common) {
            JsonNode sourceChild = source.get(field);
            JsonNode targetChild = target.get(field);
            String fullPath = buildPath(basePath, field);

            if (!sourceChild.getNodeType().equals(targetChild.getNodeType())) {
                result.addEntry(DiffEntry.builder()
                        .fieldPath(fullPath)
                        .changeType(DiffEntry.ChangeType.TYPE_CHANGED)
                        .sourceType(describeType(sourceChild))
                        .targetType(describeType(targetChild))
                        .description("Field type changed")
                        .build());
            } else if (sourceChild.isObject() && targetChild.isObject()) {
                compareNodes(sourceChild, targetChild, fullPath, result);
            } else if (sourceChild.isArray() && targetChild.isArray()) {
                compareArrays((ArrayNode) sourceChild, (ArrayNode) targetChild, fullPath, result);
            }
            // Primitive values with same type — no structural diff needed
        }
    }

    private void compareArrays(ArrayNode source, ArrayNode target, String path, DiffResult result) {
        // For schema diffing, we compare the structure of array elements
        // Take the first element of each as representative
        if (source.isEmpty() && target.isEmpty()) return;

        if (source.isEmpty() || target.isEmpty()) {
            result.addEntry(DiffEntry.builder()
                    .fieldPath(path + "[]")
                    .changeType(DiffEntry.ChangeType.RESTRUCTURED)
                    .sourceType(source.isEmpty() ? "empty array" : describeType(source.get(0)))
                    .targetType(target.isEmpty() ? "empty array" : describeType(target.get(0)))
                    .description("Array content changed")
                    .build());
            return;
        }

        // Compare first elements as schema representatives
        JsonNode sourceElem = source.get(0);
        JsonNode targetElem = target.get(0);
        compareNodes(sourceElem, targetElem, path + "[]", result);
    }

    /**
     * Detect potential field renames by comparing the structure of removed and added fields.
     * If a removed field's value structure closely matches an added field's, it's likely a rename.
     */
    private Map<String, String> detectRenames(ObjectNode source, ObjectNode target,
                                               Set<String> removed, Set<String> added) {
        Map<String, String> renames = new LinkedHashMap<>();
        if (removed.isEmpty() || added.isEmpty()) return renames;

        Set<String> matchedAdded = new HashSet<>();

        for (String removedField : new ArrayList<>(removed)) {
            JsonNode removedNode = source.get(removedField);
            String removedStructure = getStructureFingerprint(removedNode);

            for (String addedField : added) {
                if (matchedAdded.contains(addedField)) continue;

                JsonNode addedNode = target.get(addedField);
                String addedStructure = getStructureFingerprint(addedNode);

                if (removedStructure.equals(addedStructure) && !removedStructure.equals("primitive")) {
                    renames.put(removedField, addedField);
                    matchedAdded.add(addedField);
                    break;
                }
            }
        }

        return renames;
    }

    /**
     * Creates a structural fingerprint of a JSON node for rename detection.
     * Only considers structure (field names + types), not values.
     */
    private String getStructureFingerprint(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, String> fields = new TreeMap<>();
            node.fieldNames().forEachRemaining(f -> fields.put(f, describeType(node.get(f))));
            return "object:" + fields;
        } else if (node.isArray() && !node.isEmpty()) {
            return "array:" + getStructureFingerprint(node.get(0));
        }
        return "primitive";
    }

    /**
     * Describes the BSON-like type of a JSON node for display purposes.
     */
    private String describeType(JsonNode node) {
        if (node == null) return "null";
        return switch (node.getNodeType()) {
            case OBJECT -> "Object";
            case ARRAY -> "Array";
            case STRING -> inferBsonType(node.asText());
            case NUMBER -> node.isInt() || node.isLong() ? "Integer" : "Double";
            case BOOLEAN -> "Boolean";
            case NULL -> "Null";
            default -> node.getNodeType().toString();
        };
    }

    /**
     * Tries to infer BSON types from string values (e.g., dates, ObjectIds, decimals).
     */
    private String inferBsonType(String value) {
        if (value == null || value.isEmpty()) return "String";

        // ISO date pattern
        if (value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*")) return "String(Date?)";

        // ObjectId pattern (24 hex chars)
        if (value.matches("[0-9a-fA-F]{24}")) return "String(ObjectId?)";

        // Decimal pattern
        if (value.matches("-?\\d+\\.\\d+")) return "String(Decimal?)";

        // Integer stored as string
        if (value.matches("-?\\d+")) return "String(Integer?)";

        return "String";
    }

    private String buildPath(String basePath, String field) {
        return basePath.isEmpty() ? field : basePath + "." + field;
    }
}
