package org.ggp.base.player.gamer.statemachine.sample;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.statemachine.Move;

/**
 * Player which selects moves according to a script.
 */
public class ScriptedPlayer extends SampleGamer
{
  private static final String PLAN_DIR = "data\\ScriptedPlayer";

  private Queue<Move> mPlan = new LinkedList<>();

  @Override
  public void stateMachineMetaGame(long timeout)
  {
    // Clear any old plan that's lying around.
    mPlan.clear();

    // Load the script of moves that we're to play
    String lFilename = "plan." + getRole() + ".txt";
    File lPlanFile = new File(PLAN_DIR, lFilename);
    System.out.println("Loading plan from " + lPlanFile.getAbsolutePath());

    final String lOldPlanFlat = readStringFromFile(lPlanFile);
    final String[] lPlanParts = lOldPlanFlat.split(",");

    // Convert the plan to Moves
    for (final String lPlanPart : lPlanParts)
    {
      final String[] lMoveParts = lPlanPart.split(" ");
      final GdlConstant lHead = GdlPool.getConstant(lMoveParts[0]);
      final List<GdlTerm> lBody = new LinkedList<>();
      for (int lii = 1; lii < lMoveParts.length; lii++)
      {
        lBody.add(GdlPool.getConstant(lMoveParts[lii]));
      }

      if (lBody.size() == 0)
      {
        mPlan.add(getStateMachine().getMoveFromTerm(lHead));
      }
      else
      {
        mPlan.add(getStateMachine().getMoveFromTerm(
                                           GdlPool.getFunction(lHead, lBody)));
      }
    }
  }

  /**
   * Read the contents of a file into a String.
   *
   * @return the contents of the specified file, or null on error.
   *
   * @param xiFile - the file.
   */
  private static String readStringFromFile(File xiFile)
  {
    String lResult = null;
    try (final BufferedReader lReader = new BufferedReader(
                                                       new FileReader(xiFile)))
    {
      lResult = lReader.readLine();
    }
    catch (final IOException lEx)
    {
      // Never mind, we'll just return null.
    }

    return lResult;
  }

  @Override
  public Move stateMachineSelectMove(long xiTimeout)
  {
    // Simply return the next item in the plan.
    return mPlan.remove();
  }
}
