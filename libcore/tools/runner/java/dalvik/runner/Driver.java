/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dalvik.runner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Compiles, installs, runs and reports tests.
 */
final class Driver {

    private static final Logger logger = Logger.getLogger(Driver.class.getName());

    private final File localTemp;
    private final Set<File> expectationFiles;
    private final List<CodeFinder> codeFinders;
    private final Vm vm;
    private final File xmlReportsDirectory;
    private final Map<String, ExpectedResult> expectedResults = new HashMap<String, ExpectedResult>();

    /**
     * The number of tests that weren't run because they aren't supported by
     * this runner.
     */
    private int unsupportedTests = 0;

    public Driver(File localTemp, Vm vm, Set<File> expectationFiles,
            File xmlReportsDirectory, List<CodeFinder> codeFinders) {
        this.localTemp = localTemp;
        this.expectationFiles = expectationFiles;
        this.vm = vm;
        this.xmlReportsDirectory = xmlReportsDirectory;
        this.codeFinders = codeFinders;
    }

    public void loadExpectations() throws IOException {
        for (File f : expectationFiles) {
            if (f.exists()) {
                expectedResults.putAll(ExpectedResult.parse(f));
            }
        }
    }

    /**
     * Builds and executes all tests in the test directory.
     */
    public void buildAndRunAllTests(Collection<File> testFiles) throws Exception {
        localTemp.mkdirs();

        final BlockingQueue<TestRun> readyToRun = new ArrayBlockingQueue<TestRun>(4);

        Set<TestRun> tests = new LinkedHashSet<TestRun>();
        for (File testFile : testFiles) {
            Set<TestRun> testsForFile = Collections.emptySet();

            for (CodeFinder codeFinder : codeFinders) {
                testsForFile = codeFinder.findTests(testFile);

                // break as soon as we find any match. We don't need multiple
                // matches for the same file, since that would run it twice.
                if (!testsForFile.isEmpty()) {
                    break;
                }
            }

            tests.addAll(testsForFile);
        }

        logger.info("Running " + tests.size() + " tests.");

        // build and install tests in a background thread. Using lots of
        // threads helps for packages that contain many unsupported tests
        ExecutorService builders = Threads.threadPerCpuExecutor();
        int t = 0;
        for (final TestRun testRun : tests) {
            final int runIndex = t++;
            builders.submit(new Runnable() {
                public void run() {
                    try {
                        ExpectedResult expectedResult = lookupExpectedResult(testRun);
                        testRun.setExpectedResult(expectedResult);

                        if (expectedResult.getResult() == Result.UNSUPPORTED) {
                            testRun.setResult(Result.UNSUPPORTED, Collections.<String>emptyList());
                            logger.fine("skipping test " + testRun
                                    + " because the expectations file says it is unsupported.");

                        } else {
                            vm.buildAndInstall(testRun);
                            logger.fine("installed test " + runIndex + "; "
                                    + readyToRun.size() + " are ready to run");
                        }

                        readyToRun.put(testRun);
                    } catch (Throwable throwable) {
                        testRun.setResult(Result.ERROR, throwable);
                    }
                }
            });
        }
        builders.shutdown();

        vm.prepare();

        List<TestRun> runs = new ArrayList<TestRun>(tests.size());
        for (int i = 0; i < tests.size(); i++) {
            logger.fine("executing test " + i + "; "
                    + readyToRun.size() + " are ready to run");

            // if it takes 5 minutes for build and install, something is broken
            TestRun testRun = readyToRun.poll(300, TimeUnit.SECONDS);
            if (testRun == null) {
                throw new IllegalStateException(
                        "Expected " + tests.size() + " tests but found only " + i);
            }

            runs.add(testRun);
            execute(testRun);
            vm.cleanup(testRun);
        }

        if (unsupportedTests > 0) {
            logger.info("Skipped " + unsupportedTests + " unsupported tests.");
        }

        if (xmlReportsDirectory != null) {
            logger.info("Printing XML Reports... ");
            int numFiles = new XmlReportPrinter().generateReports(xmlReportsDirectory, runs);
            logger.info(numFiles + " XML files written.");
        }
    }

    /**
     * Finds the expected result for the specified test run. This strips off
     * parts of the test's qualified name until it either finds a match or runs
     * out of name.
     */
    private ExpectedResult lookupExpectedResult(TestRun testRun) {
        String name = testRun.getQualifiedName();

        while (true) {
            ExpectedResult expectedResult = expectedResults.get(name);
            if (expectedResult != null) {
                return expectedResult;
            }

            int dot = name.lastIndexOf('.');
            if (dot == -1) {
                return ExpectedResult.SUCCESS;
            }

            name = name.substring(0, dot);
        }
    }

    /**
     * Executes a single test and then prints the result.
     */
    private void execute(TestRun testRun) {
        if (testRun.getResult() == Result.UNSUPPORTED) {
            logger.fine("skipping " + testRun.getQualifiedName());
            unsupportedTests++;
            return;
        }

        if (testRun.isRunnable()) {
            vm.runTest(testRun);
        }

        printResult(testRun);
    }

    private void printResult(TestRun testRun) {
        if (testRun.isExpectedResult()) {
            logger.info("OK " + testRun.getQualifiedName() + " (" + testRun.getResult() + ")");
            // In --verbose mode, show the output even on success.
            logger.fine("  " + testRun.getFailureMessage().replace("\n", "\n  "));
            return;
        }

        logger.info("FAIL " + testRun.getQualifiedName() + " (" + testRun.getResult() + ")");
        String description = testRun.getDescription();
        if (description != null) {
            logger.info("  \"" + description + "\"");
        }

        logger.info("  " + testRun.getFailureMessage().replace("\n", "\n  "));
    }
}
