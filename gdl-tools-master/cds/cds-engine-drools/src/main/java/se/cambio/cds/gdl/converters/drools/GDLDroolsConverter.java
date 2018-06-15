package se.cambio.cds.gdl.converters.drools;

import org.openehr.rm.datatypes.text.CodePhrase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.cambio.cds.gdl.model.*;
import se.cambio.cds.gdl.model.expression.*;
import se.cambio.cds.model.instance.ArchetypeReference;
import se.cambio.cds.util.ExpressionUtil;
import se.cambio.cds.util.RefStat;
import se.cambio.cm.model.archetype.vo.ArchetypeElementVO;
import se.cambio.openehr.controller.session.data.ArchetypeManager;
import se.cambio.openehr.util.OpenEHRConst;
import se.cambio.openehr.util.OpenEHRDataValues;
import se.cambio.openehr.util.OpenEHRLanguageManager;
import java.util.*;

import static java.lang.String.format;

public class GDLDroolsConverter {

    private static final String RULE = "rule";
    private static final String WHEN = "when";
    private static final String THEN = "then";
    private static final String END = "end";
    private static final String DEFAULT_CONFIG = "no-loop true";
    private static final String SALIENCE = "salience";
    private static final String ARCHETYPE_REFERENCE_ID = "archetypeReference";
    private static final String TAB = "\t";
    private static final String DEFAULT_RULE_CODE = "default";
    private static Logger log = LoggerFactory.getLogger(GDLDroolsConverter.class);
    private final ArchetypeManager archetypeManager;
    private Guide guide;
    private Map<String, String> _gtElementToWholeDefinition = new HashMap<>();
    private Map<String, String> gtElementToDefinition = new HashMap<>();
    private Map<String, ArchetypeReference> archetypeReferenceMap;
    private Map<String, ArchetypeElementVO> elementMap;
    private Map<String, String> archetypeBindingGtCodeToDefinition;
    private Map<String, String> gtElementToArchetypeBindingGtCode;
    private Map<RefStat, Set<String>> preconditionStats;
    private StringBuilder sb;
    private int predicateCount;
    private int creationIndex;
    private String preconditionMVEL;

    public static final ArchetypeElementVO CURRENT_DATE_TIME =
            ArchetypeElementVO.builder()
                    .name(OpenEHRLanguageManager.getMessage("CurrentDateTime"))
                    .description(OpenEHRLanguageManager.getMessage("CurrentDateTime"))
                    .type(OpenEHRDataValues.DV_DATE_TIME)
                    .build();

    public GDLDroolsConverter(Guide guide, ArchetypeManager archetypeManager) {
        this.guide = guide;
        this.archetypeManager = archetypeManager;
        init();
    }

    static String getAttributeSettingStr(String gtCode, String rmName, String attributeName, String setStr) {
        return "setDataValue(DVUtil.createDV($" + gtCode + ",\"" + rmName + "\",\"" + attributeName + "\"," + setStr + "))";
    }

    private static String getEqualsString(String handle, String value, boolean inPredicate, boolean negated) {
        return "DVUtil.equalDV(" + inPredicate + ", " + handle + "," + value + getDataValueStrIfNeeded(value) + ", " + negated + ")";
    }

    private static String getComparisonString(String handle, String value) {
        return "DVUtil.compatibleComparison("
                + handle + getDataValueStrIfNeeded(handle)
                + ", $auxDV=" + value + getDataValueStrIfNeeded(value)
                + ") && " + "DVUtil.compareDVs(" + handle + ".getDataValue(), $auxDV)";
    }

    private static String getDataValueStrIfNeeded(String value) {
        if (value.startsWith("$")) {
            return ".getDataValue()";
        } else {
            return "";
        }
    }

    static boolean isString(String rmName, String attribute) {
        return (OpenEHRDataValues.DV_TEXT.equals(rmName) && OpenEHRDataValues.VALUE_ATT.equals(attribute))
                || (OpenEHRDataValues.DV_CODED_TEXT.equals(rmName) && OpenEHRDataValues.VALUE_ATT.equals(attribute))
                || OpenEHRDataValues.UNITS_ATT.equals(attribute)
                || OpenEHRDataValues.CODE_ATT.equals(attribute)
                || OpenEHRDataValues.TEMINOLOGYID_ATT.equals(attribute);
    }

    protected Guide getGuide() {
        return guide;
    }

    ArchetypeManager getArchetypeManager() {
        return archetypeManager;
    }

    int getPredicateCount() {
        return predicateCount;
    }

    void increasePredicateCount() {
        predicateCount++;
    }

    int getCreationIndex() {
        return creationIndex;
    }

    void increaseCreationIndex() {
        creationIndex++;
    }

    Map<String, ArchetypeElementVO> getElementMap() {
        if (elementMap == null) {
            elementMap = new HashMap<>();
        }
        return elementMap;
    }

    Map<String, ArchetypeReference> getArchetypeReferenceMap() {
        if (archetypeReferenceMap == null) {
            archetypeReferenceMap = new HashMap<>();
        }
        return archetypeReferenceMap;
    }

    private void init() {
        getElementMap().put(OpenEHRConst.CURRENT_DATE_TIME_ID, CURRENT_DATE_TIME);
        archetypeBindingGtCodeToDefinition = new HashMap<>();
        gtElementToArchetypeBindingGtCode = new HashMap<>();
        preconditionStats = initStats();
        predicateCount = 0;
        creationIndex = 0;
        sb = new StringBuilder();
    }

    public String convertToDrools() {
        createHeader();
        fillDefinitions();
        initPreconditions();
        insertRules();
        insertDefaultActionsRule();
        return sb.toString();
    }

    private void createHeader() {
        String guideHeader = getGuideHeader();
        sb.append(guideHeader);
    }

    private void initPreconditions() {
        List<ExpressionItem> preConditionExpressions = guide.getDefinition().getPreConditionExpressions();
        preconditionMVEL = convertExpressionsToMVEL(preConditionExpressions, preconditionStats);
    }

    private void insertDefaultActionsRule() {
        List<AssignmentExpression> defaultActions = guide.getDefinition().getDefaultActionExpressions();
        if (!defaultActions.isEmpty()) {
            Map<RefStat, Set<String>> ruleStats = initStats();
            String defaultActionsStr = convertAssignmentExpressionsToMVEL(defaultActions, ruleStats);
            String definition = getDefinitionForRule(ruleStats);
            printDefaultActionRule(ruleStats, defaultActionsStr, definition);
        }
    }

    private void printDefaultActionRule(Map<RefStat, Set<String>> ruleStats, String defaultActionsStr, String definition) {
        sb.append(RULE + " \"").append(guide.getId()).append("/").append(DEFAULT_RULE_CODE).append("\"\n");
        String guideSalienceId = getGuideSalienceId(guide.getId());
        sb.append(SALIENCE + " ").append(guideSalienceId).append(" + 9999\n");
        sb.append(DEFAULT_CONFIG + "\n");
        sb.append(WHEN + "\n");
        sb.append(definition);
        String functionExtraCode = getFunctionExtraCode(ruleStats);
        if (functionExtraCode != null) {
            sb.append(functionExtraCode);
        }
        sb.append(preconditionMVEL);
        appendFiredRuleCondition(sb, true, DEFAULT_RULE_CODE);
        sb.append(THEN + "\n");
        sb.append(defaultActionsStr);
        sb.append(getFiredRuleWMInsertion(DEFAULT_RULE_CODE));
        sb.append(END + "\n\n");
    }

    private String getGuideSalienceId(String guideId) {
        return "$" + guideId.replaceAll("[^a-zA-Z0-9]+", "") + "_salience";
    }

    private void insertRules() {
        String preconditionStr = preconditionMVEL;
        for (Rule rule : guide.getDefinition().getRules().values()) {
            Map<RefStat, Set<String>> ruleStats = initStats();
            String whenStr = convertExpressionsToMVEL(rule.getWhenStatements(), ruleStats);
            String thenStr = convertAssignmentExpressionsToMVEL(rule.getThenStatements(), ruleStats);
            String definition = getDefinitionForRule(ruleStats);
            ruleStats.get(RefStat.ATT_SET_REF).remove(OpenEHRConst.CURRENT_DATE_TIME_ID);
            String hasValueChecks =
                    getHasValueStr(ruleStats.get(RefStat.ATT_SET_REF));
            String functionExtraCode = getFunctionExtraCode(ruleStats);
            printRule(preconditionStr, rule, whenStr, thenStr, definition, hasValueChecks, functionExtraCode);
        }
    }

    private void printRule(
            String preconditionStr, Rule rule, String whenStr, String thenStr, String definition, String hasValueChecks, String functionExtraCode) {
        sb.append(RULE + " \"").append(guide.getId()).append("/").append(rule.getId()).append("\"\n");
        sb.append(DEFAULT_CONFIG + "\n");
        String guideSalienceId = getGuideSalienceId(guide.getId());
        int salienceModifier = guide.getDefinition().getRules().size() + 1 - rule.getPriority();
        sb.append(SALIENCE).append(" ").append(guideSalienceId).append(" - ").append(salienceModifier).append("\n");
        sb.append(WHEN + "\n");
        sb.append(definition);
        if (functionExtraCode != null) {
            sb.append(functionExtraCode);
        }
        if (hasValueChecks != null) {
            sb.append(hasValueChecks);
        }
        sb.append(preconditionStr);
        sb.append(whenStr);
        sb.append(THEN + "\n");
        sb.append(thenStr);
        sb.append(getFiredRuleWMInsertion(rule.getId()));
        sb.append(END + "\n\n");
    }

    private String getFunctionExtraCode(Map<RefStat, Set<String>> ruleStats) {
        Set<String> functionsRefs = new HashSet<>();
        functionsRefs.addAll(ruleStats.get(RefStat.ATT_FUNCTIONS));
        functionsRefs.addAll(preconditionStats.get(RefStat.ATT_FUNCTIONS));
        return getFunctionsExtraCode(functionsRefs);
    }

    private String getFiredRuleWMInsertion(String ruleGtCode) {
        return TAB + "insert(new FiredRuleReference(\"" + guide.getId() + "\", \"" + ruleGtCode + "\"));\n";
    }

    private void fillDefinitions() {
        for (ArchetypeBinding archetypeBinding : guide.getDefinition().getArchetypeBindings().values()) {
            StringBuilder archetypeBindingMVELSB = new StringBuilder();
            String gtCodeArchetypeReference = archetypeBinding.getId();
            archetypeBindingMVELSB.append(TAB);
            archetypeBindingMVELSB.append("$" + ARCHETYPE_REFERENCE_ID + "_").append(gtCodeArchetypeReference);
            String idDomain = archetypeBinding.getDomain();
            archetypeBindingMVELSB.append(":ArchetypeReference");
            archetypeBindingMVELSB.append("(");
            if (idDomain != null) {
                archetypeBindingMVELSB.append("idDomain==\"").append(idDomain).append("\", ");
            }
            String archetypeId = archetypeBinding.getArchetypeId();
            String templateId = archetypeBinding.getTemplateId();
            archetypeBindingMVELSB.append("idArchetype==\"").append(archetypeId).append("\"");
            archetypeBindingMVELSB.append(")\n");
            getArchetypeReferenceMap().put(gtCodeArchetypeReference, new ArchetypeReference(idDomain, archetypeId, templateId));
            processPredicates(archetypeBinding, archetypeBindingMVELSB);
            archetypeBindingGtCodeToDefinition.put(gtCodeArchetypeReference, archetypeBindingMVELSB.toString());
            processElementBindings(archetypeBinding.getId(), archetypeBinding);
        }
    }

    private void processElementBindings(String archetypeReferenceGTCode, ArchetypeBinding archetypeBinding) {
        Map<String, ElementBinding> elementBindingsMap = archetypeBinding.getElements();
        if (elementBindingsMap != null) {
            for (ElementBinding element : elementBindingsMap.values()) {
                StringBuilder elementDefinitionSB = new StringBuilder();
                String idElement = archetypeBinding.getArchetypeId() + element.getPath();
                ArchetypeElementVO value = archetypeManager.getArchetypeElements().getArchetypeElement(
                        archetypeBinding.getTemplateId(), idElement);
                getElementMap().put(element.getId(), value);
                elementDefinitionSB.append("ElementInstance(id==\"")
                        .append(idElement)
                        .append("\", archetypeReference==$")
                        .append(ARCHETYPE_REFERENCE_ID)
                        .append("_")
                        .append(archetypeReferenceGTCode)
                        .append(")");
                gtElementToDefinition.put(element.getId(), elementDefinitionSB.toString());
                gtElementToArchetypeBindingGtCode.put(element.getId(), archetypeReferenceGTCode);
            }
        }
    }

    private void processPredicates(ArchetypeBinding archetypeBinding, StringBuilder archetypeBindingMVELSB) {
        GdlDroolsPredicateProcessor gdlDroolsPredicateProcessor = new GdlDroolsPredicateProcessor(this, archetypeBinding);
        String predicateDefinition = gdlDroolsPredicateProcessor.process();
        archetypeBindingMVELSB.append(predicateDefinition);
    }

    private String getDefinitionForRule(Map<RefStat, Set<String>> ruleStats) {
        Set<String> gtCodesRef = new HashSet<>();
        gtCodesRef.addAll(ruleStats.get(RefStat.REFERENCE));
        gtCodesRef.addAll(preconditionStats.get(RefStat.REFERENCE));
        gtCodesRef.remove(OpenEHRConst.CURRENT_DATE_TIME_ID);
        Map<String, StringBuilder> archetypeDefinitions = new HashMap<>();
        for (String elementGtCode : gtCodesRef) {
            String gtCodeArchetypeBinding = gtElementToArchetypeBindingGtCode.get(elementGtCode);
            if (gtCodeArchetypeBinding != null) {
                StringBuilder definition = archetypeDefinitions.get(gtCodeArchetypeBinding);
                if (definition == null) {
                    definition = new StringBuilder();
                    definition.append(archetypeBindingGtCodeToDefinition.get(gtCodeArchetypeBinding));
                    archetypeDefinitions.put(gtCodeArchetypeBinding, definition);
                }
                definition.append(TAB);
                definition.append("$").append(elementGtCode).append(":").append(gtElementToDefinition.get(elementGtCode)).append("\n");
            }
        }

        StringBuilder resultSB = new StringBuilder();
        for (StringBuilder definition : archetypeDefinitions.values()) {
            resultSB.append(definition.toString());
        }

        for (String elementGtCode : gtCodesRef) {
            String gtCodeArchetypeBinding = gtElementToArchetypeBindingGtCode.get(elementGtCode);
            if (gtCodeArchetypeBinding != null) {
                String archetypeDefinition = archetypeDefinitions.get(gtCodeArchetypeBinding).toString();
                _gtElementToWholeDefinition.put(elementGtCode, archetypeDefinition);
            } else {
                String firedRuleDefinition = "$" + elementGtCode + ":FiredRuleReference(guideId==\"" + guide.getId() + "\", gtCode==\"" + elementGtCode + "\")";
                _gtElementToWholeDefinition.put(elementGtCode, firedRuleDefinition);
            }
        }

        return resultSB.toString();
    }

    Map<RefStat, Set<String>> initStats() {
        Map<RefStat, Set<String>> stats = new HashMap<>();
        for (RefStat refStat : RefStat.values()) {
            stats.put(refStat, new HashSet<>());
        }
        return stats;
    }

    private String convertExpressionsToMVEL(
            Collection<ExpressionItem> expressionItems,
            Map<RefStat, Set<String>> stats) {
        StringBuilder sb = new StringBuilder();
        if (expressionItems != null) {
            for (ExpressionItem expressionItem : expressionItems) {
                sb.append(TAB);
                processExpressionItem(sb, expressionItem, stats);
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String convertAssignmentExpressionsToMVEL(
            Collection<AssignmentExpression> expressionItems,
            Map<RefStat, Set<String>> stats) {
        StringBuilder sb = new StringBuilder();
        if (expressionItems != null) {
            for (ExpressionItem expressionItem : expressionItems) {
                sb.append(TAB);
                processExpressionItem(sb, expressionItem, stats);
                sb.append("\n");
            }
            for (String gtCodes : stats.get(RefStat.SET)) {
                sb.append(TAB);
                sb.append("modify($").append(gtCodes).append("){};\n");
            }
        }
        return sb.toString();
    }

    private void processExpressionItem(StringBuilder sb,
                                       ExpressionItem expressionItem,
                                       Map<RefStat, Set<String>> stats) {
        if (expressionItem instanceof AssignmentExpression) {
            processAssignmentExpression(sb, (AssignmentExpression) expressionItem, stats);
        } else if (expressionItem instanceof BinaryExpression) {
            processBinaryExpression(sb, (BinaryExpression) expressionItem, stats);
        } else if (expressionItem instanceof UnaryExpression) {
            processUnaryExpression(sb, (UnaryExpression) expressionItem, stats);
        } else {
            throw new RuntimeException(format("Unknown expression '%s'", expressionItem.getClass().getName()));
        }
    }

    private void processAssignmentExpression(StringBuilder sb,
                                             AssignmentExpression assignmentExpression,
                                             Map<RefStat, Set<String>> stats) {
        GdlDroolsAssignmentExpressionProcessor gdlDroolsAssignmentExpressionProcessor = new GdlDroolsAssignmentExpressionProcessor(this, assignmentExpression, stats);
        sb.append(gdlDroolsAssignmentExpressionProcessor.process());
    }

    private void processBinaryExpression(StringBuilder sb,
                                         BinaryExpression binaryExpression,
                                         Map<RefStat, Set<String>> stats) {
        if (OperatorKind.OR.equals(binaryExpression.getOperator())) {
            sb.append("(");
            processExpressionItem(sb, binaryExpression.getLeft(), stats);
            sb.append(" or ");
            processExpressionItem(sb, binaryExpression.getRight(), stats);
            sb.append(")");
        } else if (OperatorKind.AND.equals(binaryExpression.getOperator())) {
            sb.append("(");
            processExpressionItem(sb, binaryExpression.getLeft(), stats);
            sb.append(" and ");
            processExpressionItem(sb, binaryExpression.getRight(), stats);
            sb.append(")");
        } else if (OperatorKind.EQUALITY.equals(binaryExpression.getOperator())
                || OperatorKind.INEQUAL.equals(binaryExpression.getOperator())
                || OperatorKind.IS_A.equals(binaryExpression.getOperator())
                || OperatorKind.IS_NOT_A.equals(binaryExpression.getOperator())
                || OperatorKind.GREATER_THAN.equals(binaryExpression.getOperator())
                || OperatorKind.GREATER_THAN_OR_EQUAL.equals(binaryExpression.getOperator())
                || OperatorKind.LESS_THAN.equals(binaryExpression.getOperator())
                || OperatorKind.LESS_THAN_OR_EQUAL.equals(binaryExpression.getOperator())) {
            processComparisonExpression(sb, binaryExpression, stats);
        } else {
            throw new RuntimeException(format("Unknown operator '%s'", binaryExpression.getOperator()));
        }

    }

    private void processUnaryExpression(StringBuilder sb,
                                        UnaryExpression unaryExpression,
                                        Map<RefStat, Set<String>> stats) {
        if (OperatorKind.NOT.equals(unaryExpression.getOperator())) {
            sb.append("not(");
            processExpressionItem(sb, unaryExpression.getOperand(), stats);
            sb.append(")");
        } else if (OperatorKind.FOR_ALL.equals(unaryExpression.getOperator())) {
            sb.append("forall(");
            processExpressionItem(sb, unaryExpression.getOperand(), stats);
            sb.append(")");
        } else if (OperatorKind.FIRED.equals(unaryExpression.getOperator())
                || OperatorKind.NOT_FIRED.equals(unaryExpression.getOperator())) {
            if (!(unaryExpression.getOperand() instanceof Variable)) {
                throw new RuntimeException(format("Expected variable inside fired() operation. Instead got '%s'", unaryExpression.getOperand().getClass().getSimpleName()));
            }
            boolean negated = OperatorKind.NOT_FIRED.equals(unaryExpression.getOperator());
            String gtCode = ((Variable) unaryExpression.getOperand()).getCode();
            appendFiredRuleCondition(sb, negated, gtCode);
        } else {
            throw new RuntimeException(format("Unknown operator '%s'", unaryExpression.getOperator()));
        }
    }

    private void appendFiredRuleCondition(StringBuilder sb, boolean negated, String gtCode) {
        sb.append(TAB);
        if (negated) {
            sb.append("not(");
        }
        sb.append("FiredRuleReference(guideId == \"");
        sb.append(guide.getId());
        sb.append("\", gtCode == \"");
        sb.append(gtCode);
        sb.append("\")");
        if (negated) {
            sb.append(")");
        }
        sb.append("\n");
    }

    private void processComparisonExpression(StringBuilder sb,
                                             BinaryExpression binaryExpression,
                                             Map<RefStat, Set<String>> stats) {
        GdlDroolsBinaryComparisonExpressionProcessor gdlDroolsBinaryComparisonExpressionProcessor = new GdlDroolsBinaryComparisonExpressionProcessor(this, binaryExpression, stats);
        sb.append(gdlDroolsBinaryComparisonExpressionProcessor.process());
    }

    private String getHasValueStr(Collection<String> gtCodes) {
        if (!gtCodes.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("   eval(");
            int count = 0;
            for (String gtCode : gtCodes) {
                sb.append("$").append(gtCode).append(".hasValue()");
                if (++count < gtCodes.size()) {
                    sb.append(" && ");
                }
            }
            sb.append(")\n");
            return sb.toString();
        } else {
            return null;
        }
    }

    private String getFunctionsExtraCode(Collection<String> gtCodesWithFunctions) {
        StringBuilder sb = new StringBuilder();
        for (String gtCodesWithFunction : gtCodesWithFunctions) {
            String[] codeSplit = gtCodesWithFunction.split(ExpressionUtil.CODE_FUNCTION_SEPARATOR);
            String code = codeSplit[0];
            String att = codeSplit[1];
            if (ExpressionUtil.isFunction(att)) {
                if (OpenEHRDataValues.FUNCTION_COUNT.equals(att)) {
                    //TODO HACK - Should be done in a proper way...
                    String definition = _gtElementToWholeDefinition.get(code);
                    definition = getDefinitionsWithAnds(definition);
                    String defAux = definition
                            .replace("$bindingMap", "#bindingMap")
                            .replace("$", "$count_")
                            .replace("#bindingMap", "$bindingMap")
                            .replace("eval(DVUtil.equalDV(true, $count_predicate", "eval(DVUtil.equalDV(false, $count_predicate")
                            .replace("eval(DVUtil.isSubClassOf(true, $count_predicate", "eval(DVUtil.isSubClassOf(false, $count_predicate")
                            .replace("$count_" + code + ":ElementInstance(", "$count_" + code + ":ElementInstance(!predicate, dataValue!=null, ")
                            .replace("$count_" + OpenEHRConst.CURRENT_DATE_TIME_ID, "$" + OpenEHRConst.CURRENT_DATE_TIME_ID);
                    /*if (defAux.length()>5){
                        //Remove last ' and\n'+TAB
                        defAux = defAux.substring(0, defAux.length() - 5 - TAB.length());
                    }*/
                    sb.append(TAB)
                            .append("Number($")
                            .append(code)
                            .append(att)
                            .append(":intValue) from accumulate (\n")
                            .append(TAB)
                            .append(defAux)
                            .append(",\n")
                            .append(TAB)
                            .append(TAB)
                            .append("count($count_")
                            .append(code)
                            .append("))\n");
                }
            }
        }
        String str = sb.toString();
        return str.isEmpty() ? null : str;
    }

    private String getDefinitionsWithAnds(String definition) {
        String[] definitionLines = definition.split("\n");
        String prefix = "";
        StringBuilder definitionAuxSB = new StringBuilder();
        for (String definitionLine : definitionLines) {
            definitionAuxSB.append(prefix);
            definitionAuxSB.append(definitionLine);
            prefix = " and\n" + TAB;
        }
        definition = definitionAuxSB.toString();
        return definition;
    }

    String getOperatorMVELLine(String handle, OperatorKind ok, String value) {
        return getOperatorMVELLine(handle, ok, value, false);
    }

    String getOperatorMVELLine(
            String handle, OperatorKind ok,
            String value, boolean inPredicate) {
        if (OperatorKind.EQUALITY.equals(ok)) {
            return getEqualsString(handle, value, inPredicate, false);
        } else if (OperatorKind.INEQUAL.equals(ok)) {
            return getEqualsString(handle, value, inPredicate, true);
        } else if (OperatorKind.IS_A.equals(ok)) {
            String code = parseCode(value);
            return "DVUtil.isSubClassOf(" + inPredicate + ", " + handle + ", $bindingMap, \"" + getGuide().getId() + "/" + code + "\", " + getTermBindings(value) + ")";
        } else if (OperatorKind.IS_NOT_A.equals(ok)) {
            String code = parseCode(value);
            return "DVUtil.isNotSubClassOf(" + inPredicate + ", " + handle + ", $bindingMap, \"" + getGuide().getId() + "/" + code + "\", " + getTermBindings(value) + ")";
        } else if (OperatorKind.GREATER_THAN.equals(ok)) {
            return getComparisonString(handle, value) + ">0";
        } else if (OperatorKind.GREATER_THAN_OR_EQUAL.equals(ok)) {
            return getComparisonString(handle, value) + ">=0";
        } else if (OperatorKind.LESS_THAN.equals(ok)) {
            return getComparisonString(handle, value) + "<0";
        } else if (OperatorKind.LESS_THAN_OR_EQUAL.equals(ok)) {
            return getComparisonString(handle, value) + "<=0";
        } else {
            return null;
        }
    }

    String getAttributeOperatorMVELLine(
            String handle,
            OperatorKind ok,
            String value) {
        if (OperatorKind.EQUALITY.equals(ok)) {
            return handle + ".equals(" + value + ")";
        } else if (OperatorKind.INEQUAL.equals(ok)) {
            return "!" + handle + ".equals(" + value + ")";
        } else {
            String guideId = guide.getId();
            throw new RuntimeException(format("Guide=%s, Illegal operator '%s' used in handle '%s'.", guideId, ok.getSymbol(), handle));
        }
    }

    String parseCode(String value) {
        int index = value.indexOf("local");
        log.debug("value after IS_A: " + value);
        if (index < 0) {
            return value;
        }
        String code;
        if (value.contains("DvCodedText")) {
            code = value.substring(index + 8, value.length() - 2);
        } else if (value.contains("'")) { // due to a logic somewhere in gdl-editor introducing single quotation to code_phrase
            code = value.substring(index + 7, value.length() - 3);
        } else {
            code = value.substring(index + 7, value.length() - 2);
        }
        int index2 = code.indexOf("|");
        if (index2 > 0) {
            code = code.substring(0, index2);
        }
        log.debug("code parsed from value: " + code);
        return code;
    }

    private String getTermBindings(String value) {
        if (value.startsWith("$")) {
            LoggerFactory.getLogger(GDLDroolsConverter.class).warn("Guide=" + guide.getId() + ", Subclass comparison between elements is not supported.");
            //TODO Give support to subclass comparison between elements
            return "null";
        }
        Map<String, TermBinding> termBindings = guide.getOntology().getTermBindings();
        // TODO log.warn if gt code is unbound to terminologies
        if (termBindings == null) {
            //LoggerFactory.getLogger(GDLDroolsConverter.class).warn("Guide="+guide.getId()+", Needed terminology binding not found on guide.");
            return value;
        }
        String code = parseCode(value);
        StringBuilder buf = new StringBuilder("new DvCodedText[] {");
        boolean first = true;
        for (Map.Entry<String, TermBinding> terminologyEntrySet : termBindings.entrySet()) {
            String terminology = terminologyEntrySet.getKey();
            log.debug("terminology: " + terminology);
            TermBinding termBinding = terminologyEntrySet.getValue();
            Map<String, Binding> bindings = termBinding.getBindings();
            log.debug("bindings: " + bindings);
            if (bindings.containsKey(code)) {
                log.debug("hasCode: " + code);
                Binding binding = bindings.get(code);
                if (binding.getCodes() != null) {
                    for (CodePhrase cp : binding.getCodes()) {
                        if (first) {
                            first = false;
                        } else {
                            buf.append(",");
                        }
                        buf.append("new DvCodedText(\"text\",\"");
                        buf.append(terminology);
                        buf.append("\",\"");
                        buf.append(cp.getCodeString());
                        buf.append("\")");
                    }
                }
            }
        }
        buf.append("}");
        return buf.toString();
    }

    private String getGuideHeader() {
        return "package se.cambio.cds;\n"
                + "import se.cambio.cds.model.instance.ArchetypeReference;\n"
                + "import se.cambio.cds.model.instance.ElementInstance;\n"
                + "import se.cambio.cds.model.instance.ContainerInstance;\n"
                + "import se.cambio.cds.model.instance.FiredRuleReference;\n"
                + "import se.cambio.cds.util.DVUtil;\n"
                + "import org.openehr.rm.datatypes.quantity.DvOrdered;\n"
                + "import org.openehr.rm.datatypes.quantity.DvCount;\n"
                + "import org.openehr.rm.datatypes.quantity.DvOrdinal;\n"
                + "import org.openehr.rm.datatypes.quantity.DvQuantity;\n"
                + "import org.openehr.rm.datatypes.quantity.datetime.DvDate;\n"
                + "import org.openehr.rm.datatypes.quantity.datetime.DvDateTime;\n"
                + "import org.openehr.rm.datatypes.quantity.datetime.DvDuration;\n"
                + "import org.openehr.rm.datatypes.quantity.datetime.DvTime;\n"
                + "import org.openehr.rm.datatypes.quantity.DvProportion;\n"
                + "import org.openehr.rm.datatypes.quantity.ProportionKind;\n"
                + "import org.openehr.rm.datatypes.basic.DvBoolean;\n"
                + "import org.openehr.rm.datatypes.text.DvCodedText;\n"
                + "import org.openehr.rm.datatypes.text.DvText;\n"
                + "global se.cambio.cds.util.ExecutionLogger $executionLogger;\n"
                + "global org.openehr.rm.datatypes.basic.DataValue $auxDV;\n"
                + "global org.openehr.rm.datatypes.quantity.datetime.DvDateTime $" + OpenEHRConst.CURRENT_DATE_TIME_ID + ";\n"
                + "global java.util.Map<se.cambio.cds.model.instance.ElementInstance, java.util.Map<String, Boolean>> $bindingMap;\n"
                + "global java.lang.Integer " + getGuideSalienceId(guide.getId()) + ";\n"
                + "\n";
    }
}
/*
 *  ***** BEGIN LICENSE BLOCK *****
 *  Version: MPL 2.0/GPL 2.0/LGPL 2.1
 *
 *  The contents of this file are subject to the Mozilla Public License Version
 *  2.0 (the 'License'); you may not use this file except in compliance with
 *  the License. You may obtain a copy of the License at
 *  http://www.mozilla.org/MPL/
 *
 *  Software distributed under the License is distributed on an 'AS IS' basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 *  for the specific language governing rights and limitations under the
 *  License.
 *
 *
 *  The Initial Developers of the Original Code are Iago Corbal and Rong Chen.
 *  Portions created by the Initial Developer are Copyright (C) 2012-2013
 *  the Initial Developer. All Rights Reserved.
 *
 *  Contributor(s):
 *
 * Software distributed under the License is distributed on an 'AS IS' basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 *  ***** END LICENSE BLOCK *****
 */