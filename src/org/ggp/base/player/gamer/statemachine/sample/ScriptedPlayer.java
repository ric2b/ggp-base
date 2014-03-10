package org.ggp.base.player.gamer.statemachine.sample;

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
  private String mPlanString;
  private Queue<Move> mPlan = new LinkedList<>();

  @Override
  public void configure(int xiParamIndex, String xiParameter)
  {
    // ScriptedPlayer only takes one configuration item - it's the list of
    // moves to make.
    mPlanString = xiParameter;
  }

  @Override
  public void stateMachineMetaGame(long timeout)
  {
    // Clear any old plan that's lying around.
    mPlan.clear();

    // Convert the plan to Moves
    final String[] lPlanParts = mPlanString.split(",");
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

  @Override
  public Move stateMachineSelectMove(long xiTimeout)
  {
    // Simply return the next item in the plan.
    return mPlan.remove();
  }
}
