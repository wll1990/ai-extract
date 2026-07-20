package com.aiextract.config;

import java.util.List;
import java.util.Map;

/**
 * 领域配置 — 对应 domain/{domain}.yml 的完整结构。
 *
 * <p>包含准入标准、预检规则、萃取参数、Chat 配置等所有领域相关设置。
 * 由 {@link DomainConfigLoader} 从 YAML 文件加载。</p>
  * @author AI Extract Team
 */
public class DomainConfig {

    private String domainExtends;
    /** YAML 字段: "extends" */
    private DomainInfo domain;
    private AcceptanceConfig acceptance;
    private PreCheckConfig precheck;
    private PipelineConfig pipeline;
    private ChatConfig chat;

    public String getExtends() { return domainExtends; }
    public void setExtends(String domainExtends) { this.domainExtends = domainExtends; }

    /** ── inner records for type-safe YAML mapping ── */

    public static class DomainInfo {
        private String id;
        private String name;
        private String roleLabel;
        private String counterpartyLabel;
        private String skillLabel;
        private String knowledgeUnit;
        private String knowledgeUnitPlural;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getRoleLabel() { return roleLabel; }
        public void setRoleLabel(String roleLabel) { this.roleLabel = roleLabel; }
        public String getCounterpartyLabel() { return counterpartyLabel; }
        public void setCounterpartyLabel(String counterpartyLabel) { this.counterpartyLabel = counterpartyLabel; }
        public String getSkillLabel() { return skillLabel; }
        public void setSkillLabel(String skillLabel) { this.skillLabel = skillLabel; }
        public String getKnowledgeUnit() { return knowledgeUnit; }
        public void setKnowledgeUnit(String knowledgeUnit) { this.knowledgeUnit = knowledgeUnit; }
        public String getKnowledgeUnitPlural() { return knowledgeUnitPlural; }
        public void setKnowledgeUnitPlural(String knowledgeUnitPlural) { this.knowledgeUnitPlural = knowledgeUnitPlural; }
    }

    public static class AcceptanceConfig {
        private int minTextLength = 50;
        private double chineseRatioMin = 0.70;
        private double salesKeywordDensityMin = 0.005;
        private Double firstPersonRatioMin;
        private double theoreticalRatioMax = 0.30;
        private double marketingRatioMax = 0.15;
        private double duplicateSimilarityMax = 0.90;
        private List<String> salesKeywords;
        private List<String> businessWhitelist;
        private List<String> marketingSignals;
        private List<String> aiSignals;

        public int getMinTextLength() { return minTextLength; }
        public void setMinTextLength(int minTextLength) { this.minTextLength = minTextLength; }
        public double getChineseRatioMin() { return chineseRatioMin; }
        public void setChineseRatioMin(double chineseRatioMin) { this.chineseRatioMin = chineseRatioMin; }
        public double getSalesKeywordDensityMin() { return salesKeywordDensityMin; }
        public void setSalesKeywordDensityMin(double salesKeywordDensityMin) { this.salesKeywordDensityMin = salesKeywordDensityMin; }
        public Double getFirstPersonRatioMin() { return firstPersonRatioMin; }
        public void setFirstPersonRatioMin(Double firstPersonRatioMin) { this.firstPersonRatioMin = firstPersonRatioMin; }
        public double getTheoreticalRatioMax() { return theoreticalRatioMax; }
        public void setTheoreticalRatioMax(double theoreticalRatioMax) { this.theoreticalRatioMax = theoreticalRatioMax; }
        public double getMarketingRatioMax() { return marketingRatioMax; }
        public void setMarketingRatioMax(double marketingRatioMax) { this.marketingRatioMax = marketingRatioMax; }
        public double getDuplicateSimilarityMax() { return duplicateSimilarityMax; }
        public void setDuplicateSimilarityMax(double duplicateSimilarityMax) { this.duplicateSimilarityMax = duplicateSimilarityMax; }
        public List<String> getSalesKeywords() { return salesKeywords; }
        public void setSalesKeywords(List<String> salesKeywords) { this.salesKeywords = salesKeywords; }
        public List<String> getMarketingSignals() { return marketingSignals; }
        public void setMarketingSignals(List<String> marketingSignals) { this.marketingSignals = marketingSignals; }
        public List<String> getBusinessWhitelist() { return businessWhitelist; }
        public void setBusinessWhitelist(List<String> businessWhitelist) { this.businessWhitelist = businessWhitelist; }
        public List<String> getAiSignals() { return aiSignals; }
        public void setAiSignals(List<String> aiSignals) { this.aiSignals = aiSignals; }
    }

    public static class PreCheckConfig {
        private int minDialogueTurns = 3;
        private double minCustomerRatio = 0.20;
        private double businessDensityThreshold = 0.02;
        private double noiseRatioMax = 0.50;
        private double chineseRatioWarn = 0.80;
        private double duplicateSimilarityWarn = 0.70;
        private double ragHighThreshold = 0.50;
        private double ragRefThreshold = 0.30;
        private List<KeywordGroup> keywordGroups;
        private List<ObjectionPattern> objectionPatterns;
        private NoisePatterns noisePatterns;
        private List<SceneMapping> sceneMapping;

        public int getMinDialogueTurns() { return minDialogueTurns; }
        public void setMinDialogueTurns(int minDialogueTurns) { this.minDialogueTurns = minDialogueTurns; }
        public double getMinCustomerRatio() { return minCustomerRatio; }
        public void setMinCustomerRatio(double minCustomerRatio) { this.minCustomerRatio = minCustomerRatio; }
        public double getBusinessDensityThreshold() { return businessDensityThreshold; }
        public void setBusinessDensityThreshold(double businessDensityThreshold) { this.businessDensityThreshold = businessDensityThreshold; }
        public double getNoiseRatioMax() { return noiseRatioMax; }
        public void setNoiseRatioMax(double noiseRatioMax) { this.noiseRatioMax = noiseRatioMax; }
        public double getChineseRatioWarn() { return chineseRatioWarn; }
        public void setChineseRatioWarn(double chineseRatioWarn) { this.chineseRatioWarn = chineseRatioWarn; }
        public double getDuplicateSimilarityWarn() { return duplicateSimilarityWarn; }
        public void setDuplicateSimilarityWarn(double duplicateSimilarityWarn) { this.duplicateSimilarityWarn = duplicateSimilarityWarn; }
        public double getRagHighThreshold() { return ragHighThreshold; }
        public void setRagHighThreshold(double ragHighThreshold) { this.ragHighThreshold = ragHighThreshold; }
        public double getRagRefThreshold() { return ragRefThreshold; }
        public void setRagRefThreshold(double ragRefThreshold) { this.ragRefThreshold = ragRefThreshold; }
        private Map<String, String> labels;
        public Map<String, String> getLabels() { return labels; }
        public void setLabels(Map<String, String> labels) { this.labels = labels; }
        public List<KeywordGroup> getKeywordGroups() { return keywordGroups; }
        public void setKeywordGroups(List<KeywordGroup> keywordGroups) { this.keywordGroups = keywordGroups; }
        public List<ObjectionPattern> getObjectionPatterns() { return objectionPatterns; }
        public void setObjectionPatterns(List<ObjectionPattern> objectionPatterns) { this.objectionPatterns = objectionPatterns; }
        public NoisePatterns getNoisePatterns() { return noisePatterns; }
        public void setNoisePatterns(NoisePatterns noisePatterns) { this.noisePatterns = noisePatterns; }
        public List<SceneMapping> getSceneMapping() { return sceneMapping; }
        public void setSceneMapping(List<SceneMapping> sceneMapping) { this.sceneMapping = sceneMapping; }
    }

    public static class KeywordGroup {
        private String name;
        private int weight;
        private List<String> keywords;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getWeight() { return weight; }
        public void setWeight(int weight) { this.weight = weight; }
        public List<String> getKeywords() { return keywords; }
        public void setKeywords(List<String> keywords) { this.keywords = keywords; }
    }

    public static class ObjectionPattern {
        private String pattern;
        private String signal;
        private String label;
        public String getPattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }
        public String getSignal() { return signal; }
        public void setSignal(String signal) { this.signal = signal; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }

    public static class NoisePatterns {
        private List<String> greeting;
        private List<String> chitchat;
        private List<String> echo;
        private List<String> closing;
        private List<String> filler;
        public List<String> getGreeting() { return greeting; }
        public void setGreeting(List<String> greeting) { this.greeting = greeting; }
        public List<String> getChitchat() { return chitchat; }
        public void setChitchat(List<String> chitchat) { this.chitchat = chitchat; }
        public List<String> getEcho() { return echo; }
        public void setEcho(List<String> echo) { this.echo = echo; }
        public List<String> getClosing() { return closing; }
        public void setClosing(List<String> closing) { this.closing = closing; }
        public List<String> getFiller() { return filler; }
        public void setFiller(List<String> filler) { this.filler = filler; }
    }

    public static class SceneMapping {
        private String scene;
        private List<String> keywords;
        public String getScene() { return scene; }
        public void setScene(String scene) { this.scene = scene; }
        public List<String> getKeywords() { return keywords; }
        public void setKeywords(List<String> keywords) { this.keywords = keywords; }
    }

    public static class PipelineConfig {
        private List<Map<String, Object>> contextDimensions;
        private List<String> sceneTaxonomy;
        private String extractionRole;
        private String extractionTask;
        private List<Map<String, Object>> extractionOutputFields;
        private Map<String, String> materialTypeLabels;
        public List<Map<String, Object>> getContextDimensions() { return contextDimensions; }
        public void setContextDimensions(List<Map<String, Object>> contextDimensions) { this.contextDimensions = contextDimensions; }
        public List<String> getSceneTaxonomy() { return sceneTaxonomy; }
        public void setSceneTaxonomy(List<String> sceneTaxonomy) { this.sceneTaxonomy = sceneTaxonomy; }
        public String getExtractionRole() { return extractionRole; }
        public void setExtractionRole(String extractionRole) { this.extractionRole = extractionRole; }
        public String getExtractionTask() { return extractionTask; }
        public void setExtractionTask(String extractionTask) { this.extractionTask = extractionTask; }
        public List<Map<String, Object>> getExtractionOutputFields() { return extractionOutputFields; }
        public void setExtractionOutputFields(List<Map<String, Object>> extractionOutputFields) { this.extractionOutputFields = extractionOutputFields; }
        public Map<String, String> getMaterialTypeLabels() { return materialTypeLabels; }
        public void setMaterialTypeLabels(Map<String, String> materialTypeLabels) { this.materialTypeLabels = materialTypeLabels; }
    }

    public static class ChatConfig {
        private Map<String, Map<String, String>> modes;
        private List<Map<String, String>> practiceIntents;
        private List<String> personaFields;
        public Map<String, Map<String, String>> getModes() { return modes; }
        public void setModes(Map<String, Map<String, String>> modes) { this.modes = modes; }
        public List<Map<String, String>> getPracticeIntents() { return practiceIntents; }
        public void setPracticeIntents(List<Map<String, String>> practiceIntents) { this.practiceIntents = practiceIntents; }
        public List<String> getPersonaFields() { return personaFields; }
        public void setPersonaFields(List<String> personaFields) { this.personaFields = personaFields; }
    }

    /** ── getters/setters ── */

    public DomainInfo getDomain() { return domain; }
    public void setDomain(DomainInfo domain) { this.domain = domain; }
    public AcceptanceConfig getAcceptance() { return acceptance; }
    public void setAcceptance(AcceptanceConfig acceptance) { this.acceptance = acceptance; }
    public PreCheckConfig getPrecheck() { return precheck; }
    public void setPrecheck(PreCheckConfig precheck) { this.precheck = precheck; }
    public PipelineConfig getPipeline() { return pipeline; }
    public void setPipeline(PipelineConfig pipeline) { this.pipeline = pipeline; }
    public ChatConfig getChat() { return chat; }
    public void setChat(ChatConfig chat) { this.chat = chat; }
}
