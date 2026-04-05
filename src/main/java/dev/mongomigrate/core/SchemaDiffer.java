package dev.mongomigrate.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.*;

public class SchemaDiffer {

    private static final ObjectMapper mapper = new ObjectMapper();

    public DiffResult diff(String sourceJson, String targetJson) throws IOException {
        JsonNode source = mapper.readTree(sourceJson);
        JsonNode target = mapper.readTree(targetJson);
        DiffResult result = new DiffResult();
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

        Set<String> common = new LinkedHashSet<>(sourceFields);
        common.retainAll(targetFields);

        Set<String> removed = new LinkedHashSet<>(sourceFields);
        removed.removeAll(targetFields);

        Set<String> added = new LinkedHashSet<>(targetFields);
        added.removeAll(sourceFields);

        Map<String, String> renames = detectRenames(source, target, removed, added);

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

        for (String field : removed) {
            String fullPath = buildPath(basePath, field);
            result.addEntry(DiffEntry.builder()
                    .fieldPath(fullPath)
                    .changeType(DiffEntry.ChangeType.REMOVED)
                    .sourceType(describeType(source.get(field)))
                    .description("Field removed")
                    .build());
        }

        for (String field : added) {
            String fullPath = buildPath(basePath, field);
            result.addEntry(DiffEntry.builder()
                    .fieldPath(fullPath)
                    .changeType(DiffEntry.ChangeType.ADDED)
                    .targetType(describeType(target.get(field)))
                    .description("Field added")
                    .build());
        }

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
        }
    }

    private void compareArrays(ArrayNode source, ArrayNode target, String path, DiffResult result) {
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

        JsonNode sourceElem = source.get(0);
        JsonNode targetElem = target.get(0);
        compareNodes(sourceElem, targetElem, path + "[]", result);
    }

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

    private String inferBsonType(String value) {
        if (value == null || value.isEmpty()) return "String";

        if (value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*")) return "String(Date?)";
        if (value.matches("[0-9a-fA-F]{24}")) return "String(ObjectId?)";
        if (value.matches("-?\\d+\\.\\d+")) return "String(Decimal?)";
        if (value.matches("-?\\d+")) return "String(Integer?)";

        return "String";
    }

    private String buildPath(String basePath, String field) {
        return basePath.isEmpty() ? field : basePath + "." + field;
    }
}
