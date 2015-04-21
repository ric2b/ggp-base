package org.ggp.base.test;

import java.util.HashMap;
import java.util.LinkedList;

import org.ggp.base.player.gamer.statemachine.sancho.Sancho;
import org.ggp.base.player.request.factory.RequestFactory;
import org.ggp.base.util.game.CloudGameRepository;
import org.ggp.base.util.game.Game;
import org.ggp.base.util.game.GameRepository;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.implementation.prover.ProverStateMachine;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class StandfordGameTest extends Assert
{
  private static HashMap<String, Integer> MAX_SCORES = new HashMap<>();
  static
  {
    MAX_SCORES.put("hunter", 87);
    MAX_SCORES.put("multiplesukoshi", 0);
  }

  @Parameters(name="{0}")
  public static Iterable<? extends Object> data()
  {
    LinkedList<Object[]> lTests = new LinkedList<>();

    GameRepository lRepo = new CloudGameRepository("games.ggp.org/stanford");

    for (String lGameName : lRepo.getGameKeys())
    {
      Game lGame = lRepo.getGame(lGameName);

      // Check if this is a puzzle (which involves parsing the rules).
      StateMachine stateMachine = new ProverStateMachine();
      stateMachine.initialize(lGame.getRules());

      // if (stateMachine.getRoles().length == 1)
      {
        lTests.add(new Object[] {lGameName, lGame});
      }
    }

    return lTests;
  }

  private final String mName;
  private final Game mGame;
  private final String mID;
  private final RequestFactory mRequestFactory;
  private final Sancho mGamer;

  private boolean mStarted = false;

  /**
   * Create a test case for the specified game.
   *
   * @param xiName - the name of the game.
   * @param xiGame - the game.
   */
  public StandfordGameTest(String xiName, Game xiGame)
  {
    mName = xiName;
    mGame = xiGame;
    mID = mName + "." + System.currentTimeMillis();

    // Create an instance of Sancho.
    mGamer = new Sancho();
    mRequestFactory = new RequestFactory();
  }

  @Test
  public void testPuzzle() throws Exception
  {
    // Only run this test for puzzles.
    StateMachine stateMachine = new ProverStateMachine();
    stateMachine.initialize(mGame.getRules());
    org.junit.Assume.assumeTrue(mName + " is not a puzzle", stateMachine.getRoles().length == 1);

    // Ensure we clean up.
    mStarted = true;

    // Extract game information.
    String lRole = stateMachine.getRoles()[0].toString();
    String lRules = mGame.getRulesheet();
    int lStartClock = 60;
    int lPlayClock = 15;

    // Get Sancho to do meta-gaming.
    String lRequest = "(start " +
                      mID + " " +
                      lRole + " " +
                      lRules + " " +
                      lStartClock + " " +
                      lPlayClock + " )";
    assertEquals("ready", getResponse(lRequest));

    // Run the game through to the end.
    String lLastMove = "nil";
    long lIterations = 0;
    while (lIterations == 0 || !mGamer.utWillBeTerminal())
    {
      lRequest = "(play " + mID + " " + lLastMove + ")";
      lLastMove = "(" + getResponse(lRequest) + ")";

      // Check that Sancho didn't throw an exception.
      assertNotEquals(lLastMove, "(nil)");

      // Check that we haven't been running for too many turns.
      assertTrue("Game running for >500 turns!", lIterations++ < 500);
    }

    // Play the last move.
    lRequest = "(stop " + mID + " " + lLastMove + ")";
    assertEquals("done", getResponse(lRequest));
    mStarted = false;

    // For almost all puzzles, we ought to score 100.  There are a few exceptions through (where the puzzle doesn't
    // actually let us score 100).
    if (MAX_SCORES.containsKey(mName))
    {
      assertEquals(MAX_SCORES.get(mName), (Integer)mGamer.utGetFinalScore());
    }
    else
    {
      assertEquals(100, mGamer.utGetFinalScore());
    }
  }

  @After
  public void abortGame() throws Exception
  {
    // Abort the game (if running).
    if (mStarted)
    {
      getResponse("(abort " + mID + ")");
    }
  }

  public String getResponse(String xiRequest) throws Exception
  {
    return mRequestFactory.create(mGamer, xiRequest).process(System.currentTimeMillis());
  }
}
