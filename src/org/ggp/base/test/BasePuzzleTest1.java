package org.ggp.base.test;

import org.ggp.base.util.game.Game;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test the 1st half of the puzzles in the Base repository.
 */
@RunWith(Parameterized.class)
public class BasePuzzleTest1 extends PuzzleBase
{
  @Parameters(name="{0}")
  public static Iterable<? extends Object> data()
  {
    return PuzzleBase.getTests(new GameFilter()
    {
      @Override
      public boolean allow(String xiRepoName, String xiGameName)
      {
        return xiRepoName.equals("base") && (xiGameName.charAt(0) <= 'j');
      }
    });
  }

  /**
   * Create a test case for the specified game.
   *
   * @param xiName - the name of the game.
   * @param xiGame - the game.
   */
  public BasePuzzleTest1(String xiName, Game xiGame)
  {
    super(xiName, xiGame);
  }
}
