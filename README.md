# MongoDB Migration Assistant

<img width="1323" height="258" alt="image" src="https://github.com/user-attachments/assets/3275e419-07d2-4eae-af66-1d830fb326ee" />


AI-powered TUI tool for MongoDB schema diffing and migration script generation.

Built with **Java 17 + Lanterna + Anthropic Claude SDK**.

## Features

- **Schema Diff** — Compare two MongoDB document schemas (JSON files or pasted JSON)
- **Smart Detection** — Identifies added, removed, renamed fields, type changes, and structural changes
- **AI Script Generation** — Uses Claude API to generate production-ready `mongosh` migration scripts
- **Fallback Mode** — Generates template scripts even without an API key
- **Type Inference** — Detects strings that should be Dates, Decimals, ObjectIds, Integers
- **Rollback Scripts** — Every migration includes a reverse/rollback section
- **Sample Data** — Built-in insurance domain sample for quick demo

## Prerequisites

- Java 17+
- Maven 3.8+
- (Optional) Anthropic API key for AI-powered script generation

## Quick Start

```bash
# Clone / navigate to project
cd MongoSchemaMigrationAssistant

# Build
mvn clean package -q

# Set API key (optional, enables AI mode)
export ANTHROPIC_API_KEY=sk-ant-api03-...

# Run
java -jar target/mongo-migration-assistant-0.1.0-SNAPSHOT.jar

# Or run via Maven
mvn exec:java -q
```

## Usage

### 1. Schema Diff from Files

Prepare two JSON files representing your MongoDB documents:

**source.json** (current schema):
```json
{
  "name": "John Doe",
  "premium": "1250.50",
  "createdAt": "2024-01-15T00:00:00Z"
}
```

**target.json** (desired schema):
```json
{
  "fullName": "John Doe",
  "premium": 1250.50,
  "createdAt": "2024-01-15T00:00:00Z",
  "updatedAt": ""
}
```

Launch the app, select option 1, enter file paths, and run the diff.

### 2. Paste JSON Directly

Select "Paste JSON" to enter schemas directly in the TUI.

### 3. Try the Sample

Select "Use Sample" to see a full insurance domain migration example.

## Architecture

```
dev.mongomigrate/
├── App.java                 # Entry point
├── tui/
│   └── TuiManager.java      # Lanterna TUI screens & navigation
├── core/
│   ├── SchemaDiffer.java     # Schema comparison engine
│   ├── DiffEntry.java        # Single diff item model
│   └── DiffResult.java       # Complete diff result
├── ai/
│   └── ClaudeService.java    # Anthropic SDK integration
└── model/
    └── MigrationContext.java  # Shared state between screens
```

## Roadmap

- [ ] Direct MongoDB connection for live schema introspection
- [ ] Batch processing with progress bars for large collections
- [ ] Dry-run mode (apply to N sample docs and show results)
- [ ] Config file support (~/.mongomigrate.yaml)
- [ ] Export diff as HTML report
- [ ] Multi-collection migration workflows
- [ ] Index diff and migration
- [ ] Integration with mongosh for direct execution

## License

MIT
