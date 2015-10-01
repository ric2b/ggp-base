package org.ggp.base.test;

import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;

import org.ggp.base.player.gamer.statemachine.sancho.MachineSpecificConfiguration;
import org.ggp.base.player.gamer.statemachine.sancho.MachineSpecificConfiguration.CfgItem;
import org.ggp.base.player.gamer.statemachine.sancho.Sancho;
import org.ggp.base.player.request.factory.RequestFactory;
import org.ggp.base.player.request.factory.exceptions.RequestFormatException;
import org.ggp.base.util.game.CloudGameRepository;
import org.ggp.base.util.game.Game;
import org.ggp.base.util.game.GameRepository;
import org.ggp.base.util.http.HttpReader.GGPRequest;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.implementation.prover.ProverStateMachine;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test puzzles.
 */
public abstract class PuzzleBase extends Assert
{
  protected interface GameFilter
  {
    public boolean allow(String xiRepoName, String xiGameName);
  }

  public static final HashMap<String, String> REPO_URL = new HashMap<>();
  static
  {
    REPO_URL.put("base", "games.ggp.org/base");
    REPO_URL.put("stanford", "gamemaster.stanford.edu/gamemaster");
  }

  private static HashMap<String, Integer> EXPECTED_SCORES = new HashMap<>();
  static
  {
    // Puzzles where 100 is not achievable.  These are nothing to do with our play quality - it's the way the GDL is
    // written.  Nobody can possibly score better than the values stated here.
    EXPECTED_SCORES.put("stanford.hunter", 87);
    EXPECTED_SCORES.put("stanford.multiplehunter", 87);
    EXPECTED_SCORES.put("stanford.multiplesukoshi", 0);
    EXPECTED_SCORES.put("stanford.pearls", 90);
    EXPECTED_SCORES.put("stanford.untwistycomplex2", 0);
    EXPECTED_SCORES.put("base.mineclearingsmall", 84); // !! ARR Wild guess.  SPD to change to best possible score.

    // 31x31 legal-guided queens is deliberately at our limit an doesn't reliably pass on CI.  Not likely that there's
    // anything we can do that will assist with this.
    EXPECTED_SCORES.put("queens31lg", 0);

    // Puzzles where we ought to get 100, but don't (or don't reliably).  Covered by issue 260.
    EXPECTED_SCORES.put("base.tpeg", 90);
    EXPECTED_SCORES.put("base.peg", 90);
    EXPECTED_SCORES.put("stanford.knightstourbig", 95);

    // Can't solve some of the queens puzzles yet (or not reliably on CI).  Covered by #369.
    EXPECTED_SCORES.put("queens06ug", 0);
    EXPECTED_SCORES.put("queens08ug", 0);

    // Can't solve Futoshiki 6x6 yet.  Covered by #372.
    EXPECTED_SCORES.put("base.futoshiki6", 0);
  }

  private static HashMap<String, Integer> EXTRA_TIME = new HashMap<>();
  static
  {
    // EXTRA_TIME.put("stanford.multiplehamilton", 60);
  }

  private static HashSet<String> SKIP = new HashSet<>();
  static
  {
    // Puzzles with broken GDL.
    SKIP.add("stanford.arithmetic");

    // Games which we fail.  Covered by issue #260.
    SKIP.add("base.asteroidsParallel");
    SKIP.add("base.brain_teaser_extended");
    SKIP.add("base.factoringGeorgeForman");
    SKIP.add("base.factoringImpossibleTurtleBrain");
    SKIP.add("base.factoringMediumTurtleBrain");
    SKIP.add("base.factoringMutuallyAssuredDestruction");
    SKIP.add("base.god");
    SKIP.add("base.lightsOut");
    SKIP.add("base.mummymaze1p");
    SKIP.add("base.pancakes6");
    SKIP.add("base.pancakes88");
    SKIP.add("base.pearls");
    SKIP.add("base.queens");
    SKIP.add("base.ruleDepthExponential");
    SKIP.add("base.slidingpieces");
    SKIP.add("base.stateSpaceLarge");
    SKIP.add("base.sudoku");
    SKIP.add("base.wargame01");
    SKIP.add("base.wargame02");
    SKIP.add("base.wargame03");
    SKIP.add("base.knightmove");
  }

  /**
   * Create a list of tests to run (1 per game) from the Stanford repository.
   *
   * @return the tests to run.
   */
  public static Iterable<? extends Object> getTests(GameFilter xiFilter)
  {
    LinkedList<Object[]> lTests = new LinkedList<>();

    for (String lRepoName : new String[] {"base", "stanford"})
    {
      // Get all the games in the repository.
      GameRepository lRepo = new CloudGameRepository(REPO_URL.get(lRepoName));

      // Filter them.
      for (String lGameName : lRepo.getGameKeys())
      {
        if (xiFilter.allow(lRepoName, lGameName))
        {
          lTests.add(new Object[] {lRepoName + "." + lGameName, lRepo.getGame(lGameName)});
        }
      }
    }

    // Sort the tests
    Collections.sort(lTests, new Comparator<Object[]>()
                     {
                       @Override
                       public int compare(Object[] xiA, Object[] xiB)
                       {
                         return Collator.getInstance().compare((String)(xiA[0]), (String)(xiB[0]));
                       }
                     });

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
  public PuzzleBase(String xiName, Game xiGame)
  {
    mName = xiName;
    mGame = xiGame;
    mID = mName + "." + System.currentTimeMillis();

    // Create an instance of Sancho.
    mGamer = new Sancho();
    mRequestFactory = new RequestFactory();

    // Prevent Sancho from using learned solutions.  We want to test that we haven't regressed the function for solving
    // puzzles.
    MachineSpecificConfiguration.utOverrideCfgVal(CfgItem.DISABLE_LEARNING, true);
  }

  /**
   * Test that we score full marks on puzzles.
   *
   * @throws Exception if there was a problem.
   */
  @Test
  public void testPuzzle() throws Exception
  {
    // Only run this test for puzzles.
    StateMachine stateMachine = new ProverStateMachine();
    stateMachine.initialize(mGame.getRules());
    org.junit.Assume.assumeTrue(mName + " is not a puzzle", stateMachine.getRoles().length == 1);

    // Skip puzzles that we know we can't solve (and have an issue to cover).
    org.junit.Assume.assumeFalse("We can't solve " + mName, SKIP.contains(mName));

    // Ensure we clean up.
    mStarted = true;

    // Extract game information.
    String lRole = stateMachine.getRoles()[0].toString();
    String lRules = mGame.getRulesheet();
    int lStartClock = 60;
    int lPlayClock = 10;

    // Some games need a little extra time (but better to do it this way, because it reduces the running time of the
    // while suite.
    if (EXTRA_TIME.containsKey(mName))
    {
      int lExtra = EXTRA_TIME.get(mName);
      lStartClock += lExtra;
      lPlayClock += lExtra;
    }

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

    // For almost all puzzles, we ought to score 100.  There are a few exceptions through where the puzzle doesn't
    // actually let us score 100 and some others where we're willing to accept a non-100 score in test runs.
    if (EXPECTED_SCORES.containsKey(mName))
    {
      assertTrue(mGamer.utGetFinalScore() >= EXPECTED_SCORES.get(mName));
    }
    else
    {
      assertEquals(100, mGamer.utGetFinalScore());
    }
  }

  /**
   * Abort any running game (which will happen if a test fails).
   *
   * @throws Exception
   */
  @After
  public void abortGame() throws Exception
  {
    // Abort the game (if running).
    if (mStarted)
    {
      getResponse("(abort " + mID + ")");
    }
  }

  /**
   * Send a request to the player and get the response.
   *
   * @param xiRequest - the request to send
   * @return the response from the player.
   *
   * @throws RequestFormatException if the request was malformed.
   */
  private String getResponse(String xiRequest) throws RequestFormatException
  {
    GGPRequest lRequest = new GGPRequest();
    lRequest.mRequest = xiRequest;
    return mRequestFactory.create(mGamer, lRequest).process(System.currentTimeMillis());
  }
}
