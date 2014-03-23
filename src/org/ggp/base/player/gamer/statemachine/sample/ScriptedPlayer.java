package org.ggp.base.player.gamer.statemachine.sample;

import java.util.Queue;

import org.ggp.base.util.statemachine.Move;

/**
 * Player which selects moves according to a script.
 */
public class ScriptedPlayer extends SampleGamer
{
  private String mPlanString;
  private Queue<Move> mPlan;

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
    // Convert the plan string to a list of moves to play.
    mPlan = convertPlanString(mPlanString);
  }

  @Override
  public Move stateMachineSelectMove(long xiTimeout)
  {
    // Simply return the next item in the plan.
    return mPlan.remove();
  }
}
