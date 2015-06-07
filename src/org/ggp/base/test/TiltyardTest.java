package org.ggp.base.test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.base.Joiner;

import external.JSON.JSONArray;
import external.JSON.JSONObject;

/**
 * Test the regression cases generated via gen_tiltyard_case.pl.
 */
@RunWith(Parameterized.class)
public class TiltyardTest extends Assert
{
  /**
   * Create a list of tests to run.
   *
   * @return the tests to run.
   * @throws Exception
   */
  @Parameters(name="{0}")
  public static Iterable<? extends Object> data() throws Exception
  {
    LinkedList<Object[]> lTests = new LinkedList<>();

    File lSuites = new File("data/tests/suites");
    for (File lSuiteFile : lSuites.listFiles())
    {
      if (lSuiteFile.getName().endsWith(".json"))
      {
        // This is a test suite.  For now, just use the ones with a single case inside.
        JSONObject lSuite = new JSONObject(readFile(lSuiteFile));
        JSONArray  lCases = lSuite.getJSONArray("cases");
        if (lCases.length() == 1)
        {
          JSONObject lCase = lCases.getJSONObject(0);
          lTests.add(new Object[] {lCase.getString("case"), lCase});
        }
      }
    }

    return lTests;
  }

  private final String mName;
  private final JSONObject mCase;
  private final String mID;
  private final RequestFactory mRequestFactory;
  private final Sancho mGamer;

  private boolean mStarted = false;

  /**
   * Create a test case for the specified game.
   *
   * @param xiName - the name of the test case.
   * @param xiGame - the game.
   * @param xiCase - the JSON test case.
   */
  public TiltyardTest(String xiName, JSONObject xiCase) throws Exception
  {
    mName = xiName;
    mCase = xiCase;
    mID = xiCase.getString("game") + "." + System.currentTimeMillis();

    // Create an instance of Sancho.
    mGamer = new Sancho();
    mRequestFactory = new RequestFactory();

    // Prevent Sancho from using learned solutions.
    MachineSpecificConfiguration.utOverrideCfgVal(CfgItem.DISABLE_LEARNING, true);
  }

  /**
   * Test that we score full marks on puzzles.
   *
   * @throws Exception if there was a problem.
   */
  @Test
  public void regress() throws Exception
  {
    // Get the game.
    GameRepository lRepo = new CloudGameRepository(mCase.getString("repo"));
    Game lGame = lRepo.getGame(mCase.getString("game"));

    // Create a state machine for use by the server.
    StateMachine stateMachine = new ProverStateMachine();
    stateMachine.initialize(lGame.getRules());

    // Find who far we'll play the game for.
    int lLimit = mCase.getInt("limit");
    String[][] lMoves = new String[stateMachine.getRoles().length][];

    // Configure the players
    String lRole = null;
    int lSanchoIndex = -1;
    JSONArray lPlayers = mCase.getJSONArray("players");
    for (int lii = 0; lii < lPlayers.length(); lii++)
    {
      JSONObject lPlayer = lPlayers.getJSONObject(lii);
      if (lPlayer.getString("type").equals("Sancho"))
      {
        // This is the Sancho instance.
        assertNull("Can't have more than 1 Sancho instance", lRole);
        lRole = stateMachine.getRoles()[lii].toString();
        lSanchoIndex = lii;

        // Get the plan that Sancho is playing from.
        JSONArray lArgs = lPlayer.getJSONArray("args");
        assertEquals("Number of Sancho arguments", 1, lArgs.length());
        String lPlan = lArgs.getString(0);

        // Parse the partial plan so that we can check Sancho is playing from it.
        assertTrue("Test case arg doesn't start with plan=", lPlan.startsWith("plan="));
        lMoves[lii] = lPlan.substring(5).split(",");
        assertEquals("Number of moves for Sancho", lLimit - 1, lMoves[lii].length);

        // Configure Sancho (with the partial plan).
        mGamer.configure(0, lPlan);
      }
      else
      {
        // This is a scripted player instance.  Get the moves so that we can replay them.
        JSONArray lArgs = lPlayer.getJSONArray("args");
        assertEquals("Number of scripted player arguments", 1, lArgs.length());
        lMoves[lii] = lArgs.getString(0).split(",");
        assertEquals("Number of moves for scripted player", lLimit, lMoves[lii].length);
      }
    }
    assertNotNull("No Sancho instance found in test case", lRole);

    // Extract game information.
    String lRules = lGame.getRulesheet();
    int lStartClock = mCase.getInt("start");
    int lPlayClock = mCase.getInt("play");

    // Ensure we clean up.
    mStarted = true;

    // Get Sancho to do meta-gaming.
    String lRequest = "(start " +
                      mID + " " +
                      lRole + " " +
                      lRules + " " +
                      lStartClock + " " +
                      lPlayClock + " )";
    assertEquals("ready", getResponse(lRequest));

    // Run the pre-prepared moves at the start of the game, checking that Sancho is playing from the plan.
    for (int lii = 0; lii < lLimit; lii++)
    {
      String lLastMoves = (lii == 0) ? "nil" : getMovesString(lMoves, lii - 1);
      lRequest = "(play " + mID + " " + lLastMoves + ")";
      String lResponse = getResponse(lRequest);
      assertEquals("Wrong move played at turn " + (lii + 1), lMoves[lSanchoIndex][lii], lResponse);
    }

    // Play the last move.
    lRequest = "(stop " + mID + " " + getMovesString(lMoves, lLimit - 1) + ")";
    assertEquals("done", getResponse(lRequest));
    mStarted = false;

    // For almost all puzzles, we ought to score 100.  There are a few exceptions through where the puzzle doesn't
    // actually let us score 100 and some others where we're willing to accept a non-100 score in test runs.
    assertEquals(100, mGamer.utGetFinalScore());
  }

  private static String getMovesString(String[][] xiMoves, int lIndex)
  {
    String[] lCurrentMoves = new String[xiMoves.length];
    for (int ljj = 0; ljj < xiMoves.length; ljj++)
    {
      lCurrentMoves[ljj] = xiMoves[ljj][lIndex];
    }
    return "(" + Joiner.on(",").join(lCurrentMoves) + ")";
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

  private static String readFile(File xiFile) throws IOException
  {
    byte[] encoded = Files.readAllBytes(xiFile.toPath());
    return new String(encoded, StandardCharsets.UTF_8);
  }
}
