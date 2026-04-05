package dev.mongomigrate.model;

import dev.mongomigrate.core.DiffResult;

public class MigrationContext {

    private String sourceJson;
    private String targetJson;
    private String collectionName = "myCollection";
    private DiffResult diffResult;
    private String generatedScript;

    public String getSourceJson() { return sourceJson; }
    public void setSourceJson(String json) { this.sourceJson = json; }

    public String getTargetJson() { return targetJson; }
    public void setTargetJson(String json) { this.targetJson = json; }

    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String name) { this.collectionName = name; }

    public DiffResult getDiffResult() { return diffResult; }
    public void setDiffResult(DiffResult result) { this.diffResult = result; }

    public String getGeneratedScript() { return generatedScript; }
    public void setGeneratedScript(String script) { this.generatedScript = script; }
}
