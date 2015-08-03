
package org.ggp.base.test;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({FDRPStateMachineTests.class,
                     GameParsingTests.class,
                     GdlCleanerTests.class,
                     KnownGameTest.class,
                     NoTabsInRulesheetsTest.class,
                     ProverStateMachineTests.class,
                     SimpleSentenceFormTest.class,
                     StaticValidationTests.class})
public class FastTests
{

}
