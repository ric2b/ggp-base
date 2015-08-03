
package org.ggp.base.test;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({FDRPStateMachineTests.class,
                     PropNetSizeTest.class,
                     GameParsingTests.class,
                     GdlCleanerTests.class,
                     NoTabsInRulesheetsTest.class,
                     ProverStateMachineTests.class,
                     SimpleSentenceFormTest.class,
                     BasePuzzleTest1.class,
                     BasePuzzleTest2.class,
                     StanfordPuzzleTest.class,
                     TiltyardTest.class,
                     StaticValidationTests.class})
public class AllTests
{

}
