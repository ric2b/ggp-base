package org.ggp.base.test;

import org.ggp.base.util.game.Game;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test all the Stanford puzzles.
 */
@RunWith(Parameterized.class)
public class StanfordPuzzleTest extends PuzzleBase
{
  @Parameters(name="{0}")
  public static Iterable<? extends Object> data()
  {
    return PuzzleBase.getTests(new GameFilter()
    {
      @Override
      public boolean allow(String xiRepoName, String xiGameName)
      {
        return xiRepoName.equals("stanford");
      }
    });
  }

  /**
   * Create a test case for the specified game.
   *
   * @param xiName - the name of the game.
   * @param xiGame - the game.
   */
  public StanfordPuzzleTest(String xiName, Game xiGame)
  {
    super(xiName, xiGame);
  }
}
