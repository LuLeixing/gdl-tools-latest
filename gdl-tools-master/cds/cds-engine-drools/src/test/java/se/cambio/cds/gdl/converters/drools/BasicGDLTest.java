package se.cambio.cds.gdl.converters.drools;

import com.google.gson.Gson;
import org.joda.time.DateTime;
import org.openehr.rm.datatypes.basic.DataValue;
import org.openehr.rm.datatypes.quantity.DvCount;
import org.openehr.rm.datatypes.quantity.DvOrdinal;
import org.openehr.rm.datatypes.quantity.DvQuantity;
import org.openehr.rm.datatypes.quantity.datetime.DvDateTime;
import org.openehr.rm.datatypes.text.DvCodedText;
import org.springframework.beans.factory.annotation.Autowired;
import org.testng.annotations.Test;
import se.cambio.cds.controller.cds.CdsDataManager;
import se.cambio.cds.controller.guide.GuideManager;
import se.cambio.cds.gdl.model.expression.OperatorKind;
import se.cambio.cds.model.facade.execution.vo.PredicateGeneratedElementInstance;
import se.cambio.cds.model.facade.execution.vo.RuleExecutionResult;
import se.cambio.cds.model.facade.execution.vo.RuleReference;
import se.cambio.cds.model.instance.ArchetypeReference;
import se.cambio.cds.model.instance.ElementInstance;
import se.cambio.cds.util.Domains;
import se.cambio.cds.util.EhrDataFilterManager;
import se.cambio.cds.util.export.CdsGsonBuilderFactory;
import se.cambio.openehr.util.exceptions.InstanceNotFoundException;
import se.cambio.openehr.util.exceptions.InternalErrorException;
import se.cambio.openehr.util.exceptions.PatientNotFoundException;

import java.util.*;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

public class BasicGDLTest extends GDLTestCase {

    @Autowired
    private EhrDataFilterManager ehrDataFilterManager;

    @Autowired
    private CdsDataManager cdsDataManager;

    public BasicGDLTest() {
        super();
    }

    @Test
    public void shouldCountMedications() {
        Collection<ArchetypeReference> ars = new ArrayList<>();
        ars.add(generateOngoingMedicationArchetypeReference("A10BX03"));
        ars.add(generateOngoingMedicationArchetypeReference("N02AX02"));
        Collection<ElementInstance> elementInstances = getElementInstances(ars);
        RuleExecutionResult rer = executeGuides(Collections.singletonList("count_test.v1"), elementInstances);

        assertEquals(1, rer.getArchetypeReferences().size());
        ArchetypeReference arResult = rer.getArchetypeReferences().iterator().next();
        assertEquals(1, arResult.getElementInstancesMap().size());
        ElementInstance ei = arResult.getElementInstancesMap().get("openEHR-EHR-OBSERVATION.chadsvas_score.v1/data[at0002]/events[at0003]/data[at0001]/items[at0099]");
        assertNotNull(ei);
        assertTrue(ei.getDataValue() instanceof DvCount);
        assertEquals(2, ((DvCount) ei.getDataValue()).getMagnitude().intValue());
    }

    @Test
    public void shouldFindThatElementDoesNotExist() {
        ArchetypeReference ar = generateOngoingMedicationArchetypeReference("A10BX03");
        Collection<ElementInstance> elementInstances = getElementInstances(Collections.singleton(ar));
        RuleExecutionResult rer = executeGuides(Collections.singletonList("not_exists_test.v1"), elementInstances);
        assertEquals(2, rer.getFiredRules().size());
        assertTrue(rer.getFiredRules().contains(new RuleReference("not_exists_test.v1", "gt0045")));
        assertTrue(rer.getFiredRules().contains(new RuleReference("not_exists_test.v1", "gt0039")));
    }

    @Test
    public void shouldAllowToDefineOrsWithPredicates() {
        ArchetypeReference ar = generateOngoingMedicationArchetypeReference("A01AA01");
        Collection<ElementInstance> elementInstances = getElementInstances(Collections.singleton(ar));
        RuleExecutionResult rer = executeGuides(Collections.singletonList("test_or_predicates.v1"), elementInstances);
        assertEquals(4, rer.getFiredRules().size());
        assertTrue(rer.getFiredRules().contains(new RuleReference("test_or_predicates.v1", "gt0002")));
        assertTrue(rer.getFiredRules().contains(new RuleReference("test_or_predicates.v1", "gt0012")));
        assertTrue(rer.getFiredRules().contains(new RuleReference("test_or_predicates.v1", "gt0013")));
        assertTrue(rer.getFiredRules().contains(new RuleReference("test_or_predicates.v1", "gt0014")));
    }

    @Test
    public void shouldTestFilteringWithOnePredicate() throws InstanceNotFoundException, InternalErrorException {
        Collection<ArchetypeReference> ehrArs = new ArrayList<>();
        ArchetypeReference ar;

        ar = generateOngoingMedicationArchetypeReference("B01AE07");
        ehrArs.add(ar);
        ar = generateOngoingMedicationArchetypeReference("A01AA02");
        ehrArs.add(ar);

        Set<String> guideIds = Collections.singleton("Stroke_prevention_compliance_checking_in_AF.v2");
        GuideManager guideManager = generateGuideManager(guideIds);
        Collection<ArchetypeReference> filteredEhrArs = ehrDataFilterManager.filterEHRDataByGuides("testEhrId", new DateTime(), guideManager.getAllGuides(), ehrArs);
        assertThat(filteredEhrArs.size(), equalTo(1));
    }


    @Test
    public void shouldTestFilteringWithSeveralPredicates() throws InstanceNotFoundException, InternalErrorException {
        Collection<ArchetypeReference> ehrArs = new ArrayList<>();
        ArchetypeReference ar;

        ar = generateICD10DiagnosisArchetypeReference("I48");
        ehrArs.add(ar);
        ar = generateICD10DiagnosisArchetypeReference("I481");
        ehrArs.add(ar);

        Set<String> guideIds = Collections.singleton("diagnosis_predicate_test");
        GuideManager guideManager = generateGuideManager(guideIds);
        Collection<ArchetypeReference> filteredEhrArs = ehrDataFilterManager.filterEHRDataByGuides("testEhrId", new DateTime(), guideManager.getAllGuides(), ehrArs);
        assertThat(filteredEhrArs.size(), equalTo(1));
    }

    @Test
    public void shouldTestFilteringWithSeveralPredicatesInSeveralGuidelines() throws InstanceNotFoundException, InternalErrorException {
        Collection<ArchetypeReference> ehrArs = new ArrayList<>();
        ArchetypeReference ar;

        ar = generateICD10DiagnosisArchetypeReference("I48");
        ehrArs.add(ar);
        ar = generateICD10DiagnosisArchetypeReference("I481");
        ehrArs.add(ar);

        Collection<String> guideIds = Arrays.asList("diagnosis_predicate_test", "diagnosis_no_max_predicate_test");
        GuideManager guideManager = generateGuideManager(guideIds);
        Collection<ArchetypeReference> filteredEhrArs = ehrDataFilterManager.filterEHRDataByGuides("testEhrId", new DateTime(), guideManager.getAllGuides(), ehrArs);
        assertThat(filteredEhrArs.size(), equalTo(2));
    }

    @Test
    public void shouldTestPriorityWithSeveralGuidelines() {
        Collection<ElementInstance> elementInstances = new ArrayList<>();
        List<String> guideIds = new ArrayList<>();
        guideIds.add("test_multiple_guidelines_priority1");
        guideIds.add("test_multiple_guidelines_priority2");
        RuleExecutionResult rer = executeGuides(guideIds, elementInstances);
        assertEquals(3, rer.getFiredRules().size());
        assertEquals(new RuleReference("test_multiple_guidelines_priority1", "gt0002"), rer.getFiredRules().get(0));
        assertEquals(new RuleReference("test_multiple_guidelines_priority1", "gt0005"), rer.getFiredRules().get(1));
        assertEquals(new RuleReference("test_multiple_guidelines_priority2", "gt0006"), rer.getFiredRules().get(2));
    }

    @Test
    public void shouldCreateSeveralElements() {
        ArchetypeReference ar = generateOngoingMedicationArchetypeReference("A01AA01");
        Collection<ElementInstance> elementInstances = getElementInstances(Collections.singleton(ar));
        List<String> guideIds = new ArrayList<>();
        guideIds.add("test_creation_and_order_1");
        guideIds.add("test_creation_and_order_2");
        RuleExecutionResult rer = executeGuides(guideIds, elementInstances);
        assertEquals(4, rer.getFiredRules().size());
        assertEquals(new RuleReference("test_creation_and_order_2", "gt0002"), rer.getFiredRules().get(0));
        assertEquals(new RuleReference("test_creation_and_order_1", "gt0005"), rer.getFiredRules().get(1));
        assertEquals(new RuleReference("test_creation_and_order_2", "gt0005"), rer.getFiredRules().get(2));
        assertEquals(new RuleReference("test_creation_and_order_1", "gt0002"), rer.getFiredRules().get(3));
    }

    @Test
    public void shouldCountCDSElements() {
        Collection<ArchetypeReference> ars = new ArrayList<>();
        ars.add(generateOngoingMedicationArchetypeReference("A10BX03"));
        ars.add(generateOngoingMedicationArchetypeReference("A10BX02"));
        ars.add(generateOngoingMedicationArchetypeReference("N02AX02"));
        ars.add(generateContactArchetypeReference(new DateTime().plus(-100000)));
        ars.add(generateContactArchetypeReference(new DateTime().plus(100000)));
        ars.add(generateContactArchetypeReference(new DateTime().plus(-200000)));
        Collection<ElementInstance> elementInstances = getElementInstances(ars);
        List<String> guideIds = new ArrayList<>();
        guideIds.add("cds_count");
        RuleExecutionResult rer = executeGuides(guideIds, elementInstances);
        assertEquals(4, rer.getFiredRules().size());
        assertTrue(rer.getFiredRules().get(0).equals(new RuleReference("cds_count", "gt0006")));
        assertTrue(rer.getFiredRules().get(1).equals(new RuleReference("cds_count", "gt0006")));
        assertTrue(rer.getFiredRules().get(2).equals(new RuleReference("cds_count", "gt0006")));
        assertTrue(rer.getFiredRules().get(3).equals(new RuleReference("cds_count", "gt0011")));
    }


    @Test
    public void shouldFindMissingElements() throws InstanceNotFoundException, InternalErrorException {
        Collection<ArchetypeReference> ars = new ArrayList<>();
        ArchetypeReference ar = generateOngoingMedicationArchetypeReference("A10BX03");
        ar.getElementInstancesMap().remove(GDLTestCase.MEDICATION_DATE_END_ELEMENT_ID); //Remove end elements
        ars.add(ar);
        ar = generateOngoingMedicationArchetypeReference("A10BX02");
        ar.getElementInstancesMap().remove(GDLTestCase.MEDICATION_DATE_END_ELEMENT_ID);
        ars.add(ar);
        ar = generateOngoingMedicationArchetypeReference("N02AX02");
        ar.getElementInstancesMap().remove(GDLTestCase.MEDICATION_DATE_END_ELEMENT_ID);
        ars.add(ar);
        Collection<String> guideIds = new ArrayList<>();
        guideIds.add("test_med_definition");
        GuideManager guideManager = generateGuideManager(guideIds);
        try {
            Collection<ArchetypeReference> archetypeReferences =
                    cdsDataManager.getArchetypeReferences(null, guideIds, ars, guideManager, Calendar.getInstance());
            int elementInstanceSize = 0;
            for (ArchetypeReference archetypeReference : archetypeReferences) {
                elementInstanceSize += archetypeReference.getElementInstancesMap().size();
            }
            assertEquals(9, elementInstanceSize);
        } catch (PatientNotFoundException | InternalErrorException ex) {
            ex.printStackTrace();
        }
    }

    @Test
    public void shouldAllowToDefinePredicates() throws InstanceNotFoundException, InternalErrorException {
        Collection<ArchetypeReference> ars = new ArrayList<>();
        ArchetypeReference ar = generateOngoingMedicationArchetypeReference("A10BX03");
        ar.getElementInstancesMap().remove(GDLTestCase.MEDICATION_DATE_END_ELEMENT_ID); //Remove end elements
        ars.add(ar);
        ar = generateOngoingMedicationArchetypeReference("A01AB06");
        ar.getElementInstancesMap().remove(GDLTestCase.MEDICATION_DATE_END_ELEMENT_ID);
        ars.add(ar);
        ar = generateOngoingMedicationArchetypeReference("N02AX02");
        ar.getElementInstancesMap().remove(GDLTestCase.MEDICATION_DATE_END_ELEMENT_ID);
        ars.add(ar);
        Collection<String> guideIds = new ArrayList<>();
        guideIds.add("test_med_definition_with_predicates1");
        guideIds.add("test_med_definition_with_predicates2");
        GuideManager guideManager = generateGuideManager(guideIds);
        try {
            Collection<ArchetypeReference> archetypeReferences =
                    cdsDataManager.getArchetypeReferences(null, guideIds, ars, guideManager, Calendar.getInstance());
            Collection<ElementInstance> elementInstances = new ArrayList<>();
            for (ArchetypeReference archetypeReference : archetypeReferences) {
                elementInstances.addAll(archetypeReference.getElementInstancesMap().values());
            }
            assertEquals(18, elementInstances.size());
            boolean predicateForBValuesExists = false;
            boolean predicateForGenericEqualsNullValuesExists = false;
            boolean predicateForMinLastAdministrationExists = false;
            for (ElementInstance elementInstance : elementInstances) {
                if (elementInstance instanceof PredicateGeneratedElementInstance) {
                    PredicateGeneratedElementInstance pgei = (PredicateGeneratedElementInstance) elementInstance;
                    if (MEDICATION_CODE_ELEMENT_ID.equals(pgei.getId())) {
                        if (OperatorKind.INEQUAL.equals(pgei.getOperatorKind())) {
                            if (pgei.getDataValue() == null) {
                                fail("Predicate medication generic name != null should not be generated!");
                            }
                        } else if (OperatorKind.EQUALITY.equals(pgei.getOperatorKind())) {
                            if (pgei.getDataValue() == null) {
                                predicateForGenericEqualsNullValuesExists = true;
                            }
                        } else if (OperatorKind.IS_A.equals(pgei.getOperatorKind())) {
                            if (pgei.getDataValue() instanceof DvCodedText) {
                                DvCodedText dvCodedText = (DvCodedText) pgei.getDataValue();
                                String code = dvCodedText.getCode();
                                if (code.equals("A01AB06")) {
                                    fail("Predicate medication generic name is_a 'A01AB06' should not be generated!");
                                } else if (code.startsWith("B01")) {
                                    predicateForBValuesExists = true;
                                }
                            }
                        }
                    } else if (MEDICATION_DATE_INIT_ELEMENT_ID.equals(pgei.getId())) {
                        if (OperatorKind.MAX.equals(pgei.getOperatorKind())) {
                            fail("Predicate medication generic name!=null should not be generated!");
                        }

                    } else if (MEDICATION_DATE_END_ELEMENT_ID.equals(pgei.getId())) {
                        if (OperatorKind.MIN.equals(pgei.getOperatorKind())) {
                            predicateForMinLastAdministrationExists = true;
                        }
                    }
                }
            }
            assertTrue(predicateForBValuesExists);
            assertTrue(predicateForGenericEqualsNullValuesExists);
            assertTrue(predicateForMinLastAdministrationExists);
        } catch (PatientNotFoundException | InternalErrorException ex) {
            ex.printStackTrace();
        }
    }

    @Test
    public void shouldAllowMultipleResults() {
        Collection<ArchetypeReference> ars = new ArrayList<>();
        ArchetypeReference ar = generateOngoingMedicationArchetypeReference("A10BX03");
        ar.getElementInstancesMap().remove(GDLTestCase.MEDICATION_DATE_END_ELEMENT_ID); //Remove end elements
        ars.add(ar);
        ar = generateOngoingMedicationArchetypeReference("A10BX02");
        ar.getElementInstancesMap().remove(GDLTestCase.MEDICATION_DATE_END_ELEMENT_ID);
        ars.add(ar);
        ar = generateOngoingMedicationArchetypeReference("N02AX02");
        ars.add(ar);
        ar.getElementInstancesMap().remove(GDLTestCase.MEDICATION_DATE_END_ELEMENT_ID);
        ars.add(generateContactArchetypeReference(new DateTime().plus(100000)));
        Collection<ElementInstance> elementInstances = getElementInstances(ars);
        List<String> guideIds = new ArrayList<>();
        guideIds.add("multiple_results_test");
        RuleExecutionResult rer = executeGuides(guideIds, elementInstances);
        assertEquals(7, rer.getArchetypeReferences().size());
        assertEquals(4, rer.getFiredRules().size());
        assertEquals(4, ars.size());
        for (ArchetypeReference arAux : ars) {
            if (GDLTestCase.MEDICATION_ARCHETYPE_ID.equals(arAux.getIdArchetype())) {
                assertEquals(3, arAux.getElementInstancesMap().size()); //End date is generated in CdsDataManager
            }
        }
    }

    @Test
    public void shouldAllowCDSInitialization() {
        RuleExecutionResult rer = executeRuleEngineInit();
        assertEquals(1, rer.getArchetypeReferences().size());
    }

    private RuleExecutionResult executeRuleEngineInit() {
        Collection<ArchetypeReference> ars = new ArrayList<>();
        Collection<ElementInstance> elementInstances = getElementInstances(ars);
        List<String> guideIds = new ArrayList<>();
        guideIds.add("test_cds_init1");
        guideIds.add("test_cds_init2");
        RuleExecutionResult ruleExecutionResult = executeGuides(guideIds, elementInstances);
        return executeGuides(guideIds, elementInstances);
    }

    @Test
    public void shouldTestDateOperations() {
        Collection<ArchetypeReference> ars = new ArrayList<>();
        Collection<ElementInstance> elementInstances = getElementInstances(ars);
        List<String> guideIds = new ArrayList<>();
        guideIds.add("test_date_operation");
        RuleExecutionResult rer = executeGuides(guideIds, elementInstances);
        assertEquals(1, rer.getArchetypeReferences().size());
        assertEquals(1, rer.getFiredRules().size());
        ArchetypeReference ar = rer.getArchetypeReferences().iterator().next();
        assertEquals(1, ar.getElementInstancesMap().size());
        ElementInstance contactEndElement = ar.getElementInstancesMap().get(CONTACT_DATE_END_ELEMENT_ID);
        assertNotNull(contactEndElement);
        DataValue dv = contactEndElement.getDataValue();
        assertTrue(dv instanceof DvDateTime);
        assertEquals(Calendar.getInstance().get(Calendar.YEAR) - 1, ((DvDateTime) dv).getYear());
    }

    @Test
    public void shouldPerformCDSLinking() {
        Collection<ArchetypeReference> ars = new ArrayList<>();
        Calendar birthdate = Calendar.getInstance();
        birthdate.add(Calendar.YEAR, -76);
        ars.add(generateBasicDemographicsArchetypeReference(birthdate, Gender.FEMALE));
        ars.add(generateICD10DiagnosisArchetypeReference("I48"));
        Collection<ElementInstance> elementInstances = getElementInstances(ars);
        List<String> guideIds = new ArrayList<>();
        guideIds.add("CHA2DS2VASc_Score_calculation.v1.1");
        guideIds.add("Stroke_risks.v2");
        guideIds.add("CHA2DS2VASc_diagnosis_review.v1");
        guideIds.add("Stroke_prevention_compliance_checking_in_AF.v2");
        guideIds.add("Stroke_prevention_alert.v1.1");
        guideIds.add("Stroke_prevention_medication_recommendation.v1");
        int medicationCount = 0;
        RuleExecutionResult rer = executeGuides(guideIds, elementInstances);
        assertEquals(9, rer.getArchetypeReferences().size());
        assertEquals(11, rer.getFiredRules().size());
        boolean strokeARFound = false;
        for (ArchetypeReference ar : rer.getArchetypeReferences()) {
            if (ar.getIdArchetype().equals("openEHR-EHR-OBSERVATION.stroke_risk.v1")) {
                strokeARFound = true;
                assertEquals(1, ar.getElementInstancesMap().size());
                ElementInstance strokeRiskElement = ar.getElementInstancesMap().get("openEHR-EHR-OBSERVATION.stroke_risk.v1/data[at0001]/events[at0002]/data[at0003]/items[at0004]");
                assertNotNull(strokeRiskElement);
                DataValue dv = strokeRiskElement.getDataValue();
                assertTrue(dv instanceof DvOrdinal);
                assertEquals(3, ((DvOrdinal) dv).getValue());
            } else if (ar.getIdArchetype().equals("openEHR-EHR-INSTRUCTION.medication.v1")) {
                medicationCount++;
            }
        }
        assertTrue(strokeARFound);
        assertEquals(4, medicationCount);
    }

    @Test
    public void shouldPerformRoundtripJSONSerializationOfRuleExecutionResults() {
        Gson gson = new CdsGsonBuilderFactory().getGsonBuilder().create();
        RuleExecutionResult rer = executeRuleEngineInit();
        String json = gson.toJson(rer);
        RuleExecutionResult auxRer = gson.fromJson(json, RuleExecutionResult.class);
        String jsonAux = gson.toJson(auxRer);
        assertThat(json, equalTo(jsonAux));
    }

    @Test
    public void shouldAllowFiredRulesConditions() {
        Collection<ElementInstance> elementInstances = getElementInstances(new ArrayList<>());
        List<String> guideIds = new ArrayList<>();
        guideIds.add("fired_rule_test");
        RuleExecutionResult rer = executeGuides(guideIds, elementInstances);
        assertEquals(4, rer.getFiredRules().size());
        assertEquals(new RuleReference("fired_rule_test", "gt0003"), rer.getFiredRules().get(0));
        assertEquals(new RuleReference("fired_rule_test", "gt0004"), rer.getFiredRules().get(1));
        assertEquals(new RuleReference("fired_rule_test", "gt0002"), rer.getFiredRules().get(2));
        assertEquals(new RuleReference("fired_rule_test", "gt0004"), rer.getFiredRules().get(3));
    }

    @Test
    public void shouldRunCountOnFiredRule() {
        Collection<ArchetypeReference> ars = new ArrayList<>();
        Collection<ElementInstance> elementInstances = getElementInstances(ars);
        List<String> guideIds = new ArrayList<>();
        guideIds.add("test_fired_rule_count");
        RuleExecutionResult rer = executeGuides(guideIds, elementInstances);
        assertEquals(2, rer.getFiredRules().size());
        assertTrue(rer.getFiredRules().get(0).equals(new RuleReference("test_fired_rule_count", "gt0002")));
        assertTrue(rer.getFiredRules().get(1).equals(new RuleReference("test_fired_rule_count", "gt0003")));
    }

    @Test
    public void shouldRunCountOnFiredRule2() {
        Collection<ArchetypeReference> ars = new ArrayList<>();
        Collection<ElementInstance> elementInstances = getElementInstances(ars);
        List<String> guideIds = new ArrayList<>();
        guideIds.add("test_fired_rule_count_2");
        RuleExecutionResult rer = executeGuides(guideIds, elementInstances);
        assertEquals(4, rer.getFiredRules().size());
        assertEquals(new RuleReference("test_fired_rule_count_2", "gt0003"), rer.getFiredRules().get(0));
        assertEquals(new RuleReference("test_fired_rule_count_2", "gt0003"), rer.getFiredRules().get(1));
        assertEquals(new RuleReference("test_fired_rule_count_2", "gt0002"), rer.getFiredRules().get(2));
        assertEquals(new RuleReference("test_fired_rule_count_2", "gt0003"), rer.getFiredRules().get(3));
    }

    @Test
    public void shouldTestSimplePatternMatching() throws InstanceNotFoundException, InternalErrorException {
        Collection<ArchetypeReference> ehrArs = new ArrayList<>();
        ArchetypeReference ar;

        Calendar date = Calendar.getInstance();
        ar = generateWeightArchetypeReference(date, 40.0);
        ehrArs.add(ar);
        date = Calendar.getInstance();
        date.add(Calendar.WEEK_OF_YEAR, -1);
        ar = generateWeightArchetypeReference(date, 81.0);
        ehrArs.add(ar);

        List<String> guideIds = Collections.singletonList("simple_pattern_matching");
        RuleExecutionResult rer = executeGuides(guideIds, getElementInstances(ehrArs));
        assertThat(rer.getFiredRules().size(), equalTo(1));
    }

    @Test
    public void shouldTestPredicateComparisonUsingAttributeAndConstant() throws InstanceNotFoundException, InternalErrorException {
        Collection<ArchetypeReference> ehrArs = new ArrayList<>();
        Calendar date = new DateTime("2016-01-01T12:00:00").toGregorianCalendar();
        ArchetypeReference ar = generateBasicDemographicsArchetypeReference(date, Gender.FEMALE);
        ehrArs.add(ar);

        List<String> guideIds = Collections.singletonList("test_attribute_predicate");
        RuleExecutionResult rer = executeGuides(guideIds, getElementInstances(ehrArs));
        assertThat(rer.getFiredRules().size(), equalTo(1));

        date.add(Calendar.YEAR, -1);
        ar = generateBasicDemographicsArchetypeReference(date, Gender.FEMALE);
        ehrArs = new ArrayList<>();
        ehrArs.add(ar);
        rer = executeGuides(guideIds, getElementInstances(ehrArs));
        assertThat(rer.getFiredRules().size(), equalTo(0));
    }

    @Test
    public void shouldAllowPredicatesWithUnits() {
        Calendar date = Calendar.getInstance();
        ArchetypeReference ar = new ArchetypeReference(Domains.EHR_ID, GDLTestCase.WEIGHT_ARCHETYPE_ID, null);
        DataValue dataValue = new DvQuantity("kg", 90.0, 2);
        ElementInstance elementInstance = new ElementInstance(WEIGHT_ELEMENT_ID, dataValue, ar, null, null);
        dataValue = new DvDateTime(new DateTime(date.getTimeInMillis()).toString());
        new ElementInstance(WEIGHT_EVENT_TIME_ELEMENT_ID, dataValue, ar, null, null);
        Collection<ElementInstance> elementInstances = getElementInstances(Collections.singleton(ar));
        RuleExecutionResult rer = executeGuides(Collections.singletonList("test_predicate_expression_with_units"), elementInstances);
        assertEquals(1, rer.getFiredRules().size());
        elementInstance.setDataValue(new DvQuantity("lb", 90.0, 2));
        rer = executeGuides(Collections.singletonList("test_predicate_expression_with_units"), elementInstances);
        assertEquals(0, rer.getFiredRules().size());
    }

    @Test
    public void shouldWorkWithComplexPredicates() {
        ArchetypeReference ar1 = generateContactArchetypeReference(new DateTime("2016-01-01T00:00:00"), new DateTime(), true, true, false);
        ArchetypeReference ar2 = generateContactArchetypeReference(new DateTime("2016-02-01T00:00:00"), new DateTime(), true, true, false);
        ArchetypeReference ar3 = generateContactArchetypeReference(new DateTime("2016-03-01T00:00:00"), new DateTime(), true, true, true);
        Collection<ElementInstance> elementInstances = getElementInstances(Arrays.asList(ar1, ar2, ar3));
        RuleExecutionResult rer = executeGuides(Collections.singletonList("Stroke_prevention_doctor_visit.v1.0.0"), elementInstances);
        assertEquals(1, rer.getFiredRules().size());
        assertThat(rer.getArchetypeReferences().size(), is(1));
        ArchetypeReference archetypeReference = rer.getArchetypeReferences().iterator().next();
        ElementInstance elementInstance = archetypeReference.getElementInstancesMap().get("openEHR-EHR-EVALUATION.stroke_prevention_dashboard_utility.v1/data[at0001]/items[at0002]");
        assertThat(elementInstance, notNullValue());
        DataValue dataValue = elementInstance.getDataValue();
        assertThat(dataValue, instanceOf(DvDateTime.class));
        assertThat(((DvDateTime) dataValue).getValue(), startsWith("2016-02-01T00:00:00"));
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