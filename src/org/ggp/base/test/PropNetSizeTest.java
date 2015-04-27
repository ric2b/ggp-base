
package org.ggp.base.test;

import java.util.LinkedList;
import java.util.List;

import org.ggp.base.util.game.CloudGameRepository;
import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test that we're generating PropNets of the expected size - i.e. that optimization is working as expected.
 */
@RunWith(Parameterized.class)
public class PropNetSizeTest extends Assert
{
  @Parameters(name="{1}")
  public static Iterable<? extends Object> data()
  {
    LinkedList<Object[]> lTests = new LinkedList<>();

    lTests.add(new Object[] {"base", "ticTacToe",      244,   168,   168,    57});
    lTests.add(new Object[] {"base", "connectFour",    765,   619,   619,   299});
    lTests.add(new Object[] {"base", "breakthrough",  1844,  1203,  1203,   142});
    lTests.add(new Object[] {"base", "sudokuGrade1",  5483,  4446,  4399,  1722});
    lTests.add(new Object[] {"base", "checkers",      8079,  4462,  4462,  1571});
    lTests.add(new Object[] {"base", "reversi",      17795,  3997,  3997, 15195});
    lTests.add(new Object[] {"base", "speedChess",   35416, 15710, 15710,  9512});
    lTests.add(new Object[] {"base", "skirmishNew",  35541, 15705, 15705,  9567});
    lTests.add(new Object[] {"base", "skirmish",     54143, 39649, 39649,  8897});
    lTests.add(new Object[] {"base", "hex",          81507, 61164, 61164,  3250});
    lTests.add(new Object[] {"base", "hexPie",       83023, 82342, 82335,  3300});

    return lTests;
  }

  @Parameter(value = 0) public String mRepo;
  @Parameter(value = 1) public String mGame;
  @Parameter(value = 2) public int mFullSize;
  @Parameter(value = 3) public int mXSize;
  @Parameter(value = 4) public int mOSize;
  @Parameter(value = 5) public int mGoalSize;

  @Test
  public void testPropNetSizes() throws Exception
  {
    List<Gdl> lGDL = new CloudGameRepository("games.ggp.org/" + mRepo).getGame(mGame).getRules();
    ForwardDeadReckonPropnetStateMachine lStateMachine = new ForwardDeadReckonPropnetStateMachine();
    lStateMachine.initialize(lGDL);
    assertEquals(mFullSize, lStateMachine.getFullPropNet().getComponents().size());
    int lXSize = lStateMachine.getXPropNet().getComponents().size();
    int lOSize = lStateMachine.getOPropNet().getComponents().size();
    assertEquals(mXSize, Math.max(lXSize, lOSize));
    assertEquals(mOSize, Math.min(lXSize, lOSize));
    assertEquals(mGoalSize, lStateMachine.getGoalPropNet().getComponents().size());
  }
}
