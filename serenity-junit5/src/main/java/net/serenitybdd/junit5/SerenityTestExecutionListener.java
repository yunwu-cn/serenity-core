package net.serenitybdd.junit5;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.thucydides.core.configuration.SystemPropertiesConfiguration;
import net.thucydides.core.model.DataTable;
import net.thucydides.core.model.TestOutcome;
import net.thucydides.core.model.TestResult;
import net.thucydides.core.pages.Pages;
import net.thucydides.core.reports.ReportService;
import net.thucydides.core.steps.*;
import net.thucydides.core.util.SystemEnvironmentVariables;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.commons.PreconditionViolationException;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ClassUtils;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.thucydides.core.reports.ReportService.getDefaultReporters;
import static net.thucydides.core.steps.TestSourceType.TEST_SOURCE_JUNIT5;

public class SerenityTestExecutionListener implements TestExecutionListener {

    private static List<Class> expectedExceptions = Collections.synchronizedList(new ArrayList<>());

    static {
        ByteBuddyAgent.install();
        new ByteBuddy()
                .rebase(Assertions.class)
                .visit(Advice.to(AssertThrowsAdvice.class).on(named("assertThrows")))
                .make().load(Assertions.class.getClassLoader(), ClassReloadingStrategy.fromInstalledAgent());
    }

    private final Logger logger = LoggerFactory.getLogger(SerenityTestExecutionListener.class);

    private ReportService reportService;

    private SerenityTestExecutionSummary summary;

    private Pages pages;

    //key-> "ClassName.MethodName"
    //entries-> DataTable associated with method
    private Map<String, DataTable> dataTables = new HashMap<>();

    private int parameterSetNumber = 0;

    private BaseStepListener baseStepListener;

    private Class<?> testClass;

    private boolean isDataDrivenTest = false;

    private boolean isSerenityTest = false;

    public SerenityTestExecutionListener() {
        File outputDirectory = getOutputDirectory();
        baseStepListener = Listeners.getBaseStepListener().withOutputDirectory(outputDirectory);
        StepEventBus.getEventBus().registerListener(baseStepListener);
    }

    private File getOutputDirectory() {
        SystemPropertiesConfiguration systemPropertiesConfiguration = new SystemPropertiesConfiguration(new SystemEnvironmentVariables());
        return systemPropertiesConfiguration.getOutputDirectory();
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        this.summary = new SerenityTestExecutionSummary(testPlan);
        testPlan.getRoots().forEach(
                root -> {
                    Set<TestIdentifier> children = testPlan.getChildren(root.getUniqueId());
                    children.stream()
                            .filter(this::isClassSource)
                            .filter(this::isASerenityTest)
                            .forEach(this::configureParameterizedTestDataFor);
                }
        );
    }

    private boolean isASerenityTest(TestIdentifier child) {
        return isSerenityTestClass(((ClassSource) child.getSource().get()).getJavaClass());
    }

    private void configureParameterizedTestDataFor(TestIdentifier serenityTest) {
        Class<?> javaClass = ((ClassSource) serenityTest.getSource().get()).getJavaClass();
        Map<String, DataTable> parameterTablesForClass = JUnit5DataDrivenAnnotations.forClass(javaClass).getParameterTables();
        if (!parameterTablesForClass.isEmpty()) {
            dataTables.putAll(parameterTablesForClass);
        }
    }
//
//    private void configureParameterizedTestDataFor(Class<?> javaClass) {
//        Map<String, DataTable> parameterTablesForClass = JUnit5DataDrivenAnnotations.forClass(javaClass).getParameterTables();
//        if (!parameterTablesForClass.isEmpty()) {
//            dataTables.putAll(parameterTablesForClass);
//        }
//    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (!isSerenityTest) return;
        logger.debug("->TestPlanExecutionFinished " + testPlan);
    }

    private void generateReportsForTest() {
        if (isDataDrivenTest) {
            generateReportsForParameterizedTest();
            isDataDrivenTest = false;
        } else {
            generateReports();
        }
    }

    @Override
    public void dynamicTestRegistered(TestIdentifier testIdentifier) {
    }

    @Override
    public void executionSkipped(TestIdentifier testIdentifier, String reason) {
        if (!isSerenityTest) return;
        processTestMethodAnnotationsFor(testIdentifier);
    }

    private void processTestMethodAnnotationsFor(TestIdentifier testIdentifier) {
        Optional<TestSource> testSource = testIdentifier.getSource();
        if (testSource.isPresent() && (testSource.get() instanceof MethodSource)) {
            MethodSource methodTestSource = ((MethodSource) testIdentifier.getSource().get());
            String className = methodTestSource.getClassName();
            String methodName = methodTestSource.getMethodName();
            //method parameter types are class names as strings comma separated : java.langString,java.lang.Integer
            String methodParameterTypes = methodTestSource.getMethodParameterTypes();
            List<Class> methodParameterClasses = null;

            if (methodParameterTypes != null) {
                methodParameterClasses = Arrays.asList(methodParameterTypes.split(",")).stream().map(parameterClassName -> {
                    try {
                        //ClassUtils handles also simple data type like int, char..
                        return ClassUtils.forName(parameterClassName.trim(), this.getClass().getClassLoader());
                    } catch (ClassNotFoundException e) {
                        logger.error("Problem when getting parameter classes ", e);
                        return null;
                    }
                }).collect(Collectors.toList());
            }
            try {
                if (isIgnored(getProcessedMethod(className, methodName, methodParameterClasses))) {
                    startTestAtEventBus(testIdentifier);
                    StepEventBus.getEventBus().testIgnored();
                    StepEventBus.getEventBus().testFinished();
                }
            } catch (ClassNotFoundException | NoSuchMethodException exception) {
                logger.error("Exception when processing method annotations", exception);
            }
        }
    }

    
    private Method getProcessedMethod(String className, String methodName, List<Class> methodParameterClasses) throws NoSuchMethodException, ClassNotFoundException {
        if (methodParameterClasses != null) {
            Class[] classesArray = new Class[methodParameterClasses.size()];
            return Class.forName(className).getMethod(methodName, methodParameterClasses.toArray(classesArray));
        } else {
            return Class.forName(className).getMethod(methodName);
        }
    }

    private boolean isIgnored(Method child) {
        return child.getAnnotation(Disabled.class) != null;
    }


    private void startTestAtEventBus(TestIdentifier testIdentifier) {
        StepEventBus.getEventBus().setTestSource(TestSourceType.TEST_SOURCE_JUNIT5.getValue());
        String displayName = removeEndBracketsFromDisplayName(testIdentifier.getDisplayName());
        if (isMethodSource(testIdentifier)) {
            String className = ((MethodSource) testIdentifier.getSource().get()).getClassName();
            try {
                StepEventBus.getEventBus().testStarted(
                        Optional.ofNullable(displayName).orElse("Initialisation"),
                        Class.forName(className));
            } catch (ClassNotFoundException exception) {
                logger.error("Exception when starting test at event bus ", exception);
            }
        }
    }

    private String removeEndBracketsFromDisplayName(String displayName) {
        if (displayName != null && displayName.endsWith("()")) {
            displayName = displayName.substring(0, displayName.length() - 2);
        }
        return displayName;
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        if (!testIdentifier.getSource().isPresent()) {
            logger.trace("No action done at executionStarted because testIdentifier is null");
            return;
        }
        if (isTestContainer(testIdentifier) && isClassSource(testIdentifier)) {
            testClass = ((ClassSource) testIdentifier.getSource().get()).getJavaClass();
            isSerenityTest = isSerenityTestClass(testClass);
            if (!isSerenityTest) {
                logger.trace("-->Execution started but no SerenityClass " + testClass);
                return;
            }
            logger.trace("-->Execution started " + testIdentifier.getDisplayName() + "--" + testIdentifier.getType() + "--" + testIdentifier.getSource());
            logger.trace("-->TestSuiteStarted " + testClass);
            baseStepListener.clearTestOutcomes();
            StepEventBus.getEventBus().testSuiteStarted(testClass);
        }

        if (isMethodSource(testIdentifier)) {
            MethodSource methodSource = ((MethodSource) testIdentifier.getSource().get());
            if (isSimpleTest(testIdentifier)) {
                testStarted(methodSource, testIdentifier);
            }
            String sourceMethod = methodSource.getClassName() + "." + methodSource.getMethodName();
            DataTable dataTable = dataTables.get(sourceMethod);
            if (dataTable != null) {
                logger.trace("FoundDataTable " + dataTable + " " + dataTable.getRows());
                isDataDrivenTest = true;
                if (isTestContainer(testIdentifier)) {
                    parameterSetNumber = 0;
                } else if (isSimpleTest(testIdentifier)) {
                    StepEventBus.getEventBus().useExamplesFrom(dataTable);
                    logger.trace("-->EventBus.useExamplesFrom" + dataTable);
                    logger.trace("-->EventBus.exampleStarted " + parameterSetNumber + "--" + dataTable.row(parameterSetNumber).toStringMap());
                    StepEventBus.getEventBus().exampleStarted(dataTable.row(parameterSetNumber).toStringMap());
                    //StepEventBus.getEventBus().exampleStarted(dataTable.row(parameterSetNumber).toStringMap(),"Example #" + parameterSetNumber);
                }
            }
        }
    }

    private boolean isTestContainer(TestIdentifier testIdentifier) {
        return TestDescriptor.Type.CONTAINER == testIdentifier.getType();
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        if (!isSerenityTest) return;
        logger.trace("-->Execution finished " + testIdentifier.getDisplayName() + "--" + testIdentifier.getType() + "--" + testIdentifier.getSource() + " with result " + testExecutionResult.getStatus());
        if (!testIdentifier.getSource().isPresent()) {
            logger.info("No action done at executionFinished because testIdentifier is null");
            return;
        }
        if (isTestContainer(testIdentifier) && isClassSource(testIdentifier)) {
            logger.trace("-->EventBus.TestSuiteFinished " + ((ClassSource) testIdentifier.getSource().get()).getJavaClass());
            StepEventBus.getEventBus().testSuiteFinished();
            generateReportsForTest();
        }
        if (isSimpleTest(testIdentifier)) {
            if (isMethodSource(testIdentifier)) {
                MethodSource methodSource = ((MethodSource) testIdentifier.getSource().get());
                String sourceMethod = methodSource.getClassName() + "." + methodSource.getMethodName();
                testFinished(testIdentifier, methodSource, testExecutionResult);
                DataTable dataTable = dataTables.get(sourceMethod);
                if (dataTable != null) {
                    logger.trace("-->EventBus.exampleFinished " + parameterSetNumber + "--" + dataTable.row(parameterSetNumber).toStringMap());
                    StepEventBus.getEventBus().exampleFinished();
                    parameterSetNumber++;
                }
            }
        }
        recordSummaryData(testIdentifier, testExecutionResult);
    }

    private void recordSummaryData(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        try {
            switch (testExecutionResult.getStatus()) {

                case SUCCESSFUL: {
                    if (testIdentifier.isContainer()) {
                        this.summary.containersSucceeded.incrementAndGet();
                    }
                    if (testIdentifier.isTest()) {
                        this.summary.testsSucceeded.incrementAndGet();
                    }
                    break;
                }

                case ABORTED: {
                    if (testIdentifier.isContainer()) {
                        this.summary.containersAborted.incrementAndGet();
                    }
                    if (testIdentifier.isTest()) {
                        this.summary.testsAborted.incrementAndGet();
                    }
                    break;
                }

                case FAILED: {
                    if (testIdentifier.isContainer()) {
                        this.summary.containersFailed.incrementAndGet();
                    }
                    if (testIdentifier.isTest()) {
                        this.summary.testsFailed.incrementAndGet();
                    }
                    testExecutionResult.getThrowable().ifPresent(throwable -> this.summary.addFailure(testIdentifier, throwable));
                    StepEventBus.getEventBus().testFailed(testExecutionResult.getThrowable().get());
                    break;
                }
                default:
                    throw new PreconditionViolationException(
                            "Unsupported execution status:" + testExecutionResult.getStatus());
            }
        } finally {
            expectedExceptions.clear();
        }
    }

    private void testFinished(TestIdentifier testIdentifier, MethodSource methodSource, TestExecutionResult testExecutionResult) {
        updateResultsUsingTestAnnotations(methodSource);
        TestResult result = StepEventBus.getEventBus().getBaseStepListener().getCurrentTestOutcome().getResult();
        if (testExecutionResult.getStatus() == TestExecutionResult.Status.ABORTED && result == TestResult.SUCCESS) {
            updateResultsUsingTestExecutionResult(testExecutionResult);
        } else if (testExecutionResult.getStatus() == TestExecutionResult.Status.FAILED && result.isLessSevereThan(TestResult.FAILURE)) {
            updateResultsUsingTestExecutionResult(testExecutionResult);
        }
        StepEventBus.getEventBus().testFinished();
        StepEventBus.getEventBus().setTestSource(TEST_SOURCE_JUNIT5.getValue());
    }

    private void updateResultsUsingTestExecutionResult(TestExecutionResult testExecutionResult) {
        testExecutionResult.getThrowable().ifPresent(
                cause -> StepEventBus.getEventBus().getBaseStepListener().updateCurrentStepFailureCause(cause)
        );
        if (testExecutionResult.getStatus() == TestExecutionResult.Status.ABORTED) {
            StepEventBus.getEventBus().getBaseStepListener().overrideResultTo(TestResult.ABORTED);
        }
    }

    private void updateResultsUsingTestAnnotations(MethodSource methodSource) {

        if (TestMethodConfiguration.forMethod(methodSource.getJavaMethod()).isManual()) {
            setToManual(methodSource);
        }
        expectedExceptions.stream().forEach(this::updateResultsForExpectedException);
    }

    private void setToManual(MethodSource methodSource) {
        StepEventBus.getEventBus().testIsManual();
        TestResult result = TestMethodConfiguration.forMethod(methodSource.getJavaMethod()).getManualResult();
        StepEventBus.getEventBus().getBaseStepListener().recordManualTestResult(result);
    }

    private void updateResultsForExpectedException(Class<? extends Throwable> expected) {
        StepEventBus.getEventBus().exceptionExpected(expected);
    }

    private boolean isSimpleTest(TestIdentifier testIdentifier) {
        return testIdentifier.getType() == TestDescriptor.Type.TEST;
    }


    @Override
    public void reportingEntryPublished(TestIdentifier testIdentifier, ReportEntry entry) {
        if (!isSerenityTest) return;
        logger.trace("-->ReportingEntryPublished " + testIdentifier.getDisplayName() + "--" + testIdentifier.getType() + "--" + testIdentifier.getSource());
    }

    private boolean isClassSource(TestIdentifier testId) {
        return testId.getSource().isPresent() && (testId.getSource().get() instanceof ClassSource);
    }

    private boolean isMethodSource(TestIdentifier testId) {
        return testId.getSource().isPresent() && (testId.getSource().get() instanceof MethodSource);
    }

    /**
     * Called when a test starts. We also need to start the test suite the first
     * time, as the testRunStarted() method is not invoked for some reason.
     */
    private void testStarted(MethodSource methodSource, TestIdentifier testIdentifier/*final Description description*/) {
        if (testingThisTest(testIdentifier)) {
            startTestSuiteForFirstTest(testIdentifier);
            logger.debug(Thread.currentThread() + " Test started " + testIdentifier);
            StepEventBus.getEventBus().clear();
            StepEventBus.getEventBus().setTestSource(TEST_SOURCE_JUNIT5.getValue());
            String testName = methodSource.getMethodName();
            try {
                Method javaMethod = methodSource.getJavaMethod();
                if (JUnit5TestMethodAnnotations.forTest(javaMethod).getDisplayName().isPresent()) {
                    testName = JUnit5TestMethodAnnotations.forTest(javaMethod).getDisplayName().get();
                }
            } catch (Exception e) {
                //ignore org.junit.platform.commons.PreconditionViolationException: Could not find method with name
            }

            StepEventBus.getEventBus().testStarted(Optional.ofNullable(testName).orElse("Initialisation"), methodSource.getJavaClass());

            //
            // Check for @Pending tests
            //
            if (TestMethodConfiguration.forMethod(methodSource.getJavaMethod()).isPending()) {
                StepEventBus.getEventBus().testPending();
            }
        }
    }

    private boolean testingThisTest(TestIdentifier testIdentifier) {
        if (isMethodSource(testIdentifier)) {
            MethodSource methodSource = (MethodSource) testIdentifier.getSource().get();
            if (testClass.equals(methodSource.getJavaClass())) {
                return true;
            }
        }
        return false;
    }


    private void startTestSuiteForFirstTest(TestIdentifier testIdentifier) {
        if (isMethodSource(testIdentifier)) {
            logger.trace("-->TestSuiteStarted " + ((MethodSource) testIdentifier.getSource().get()).getJavaClass());
            StepEventBus.getEventBus().testSuiteStarted(((MethodSource) testIdentifier.getSource().get()).getJavaClass());
        }
    }


    /**
     * Find the current set of test outcomes produced by the test execution.
     *
     * @return the current list of test outcomes
     */
    public List<TestOutcome> getTestOutcomes() {
        return baseStepListener.getTestOutcomes();
    }


    private void generateReports() {
        generateReportsFor(getTestOutcomes());
    }

    private void generateReportsForParameterizedTest() {
        ParameterizedTestsOutcomeAggregator parameterizedTestsOutcomeAggregator = new ParameterizedTestsOutcomeAggregator();
        generateReportsFor(parameterizedTestsOutcomeAggregator.aggregateTestOutcomesByTestMethods());
    }


    /**
     * A test runner can generate reports via Reporter instances that subscribe
     * to the test runner. The test runner tells the reporter what directory to
     * place the reports in. Then, at the end of the test, the test runner
     * notifies these reporters of the test outcomes. The reporter's job is to
     * process each test run outcome and do whatever is appropriate.
     *
     * @param testOutcomeResults the test results from the previous test run.
     */
    private void generateReportsFor(final List<TestOutcome> testOutcomeResults) {
        getReportService().generateReportsFor(testOutcomeResults);
        getReportService().generateConfigurationsReport();
    }

    private ReportService getReportService() {
        if (reportService == null) {
            reportService = new ReportService(getOutputDirectory(), getDefaultReporters());
        }
        return reportService;
    }

    /**
     * Instantiates the @ManagedPages-annotated Pages instance using current WebDriver.
     *
     * @param testCase A Serenity-annotated test class
     */
    protected void injectAnnotatedPagesObjectInto(final Object testCase) {
        StepAnnotations.injector().injectAnnotatedPagesObjectInto(testCase, pages);
    }

    public static void addExpectedException(Class exceptionClass) {
        expectedExceptions.add(exceptionClass);
    }

    static boolean isSerenityTestClass(Class<?> testClass) {
        return classNestStructure(testClass).stream()
                .filter(clazz -> clazz.getAnnotation(ExtendWith.class) != null)
                .map(clazz -> clazz.getAnnotation(ExtendWith.class))
                .anyMatch(annotation -> Arrays.asList(annotation.value()).contains(SerenityJUnit5Extension.class));
    }

    static private List<Class<?>> classNestStructure(Class<?> testClass) {
        List<Class<?>> nestedStructure = new ArrayList<>();
        nestedStructure.add(testClass);
        Class<?> declaringClass = testClass.getDeclaringClass();
        while (declaringClass != null) {
            nestedStructure.add(declaringClass);
            declaringClass = declaringClass.getDeclaringClass();
        }
        return nestedStructure;
    }
}
