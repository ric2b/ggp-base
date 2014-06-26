
package org.ggp.base.player.gamer.statemachine.sample;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.ggp.base.apps.player.detail.DetailPanel;
import org.ggp.base.apps.player.detail.SimpleDetailPanel;
import org.ggp.base.player.gamer.statemachine.StateMachineGamer;
import org.ggp.base.util.game.Game;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.cache.CachedStateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.prover.ProverStateMachine;

/**
 * SampleGamer is a simplified version of the StateMachineGamer, dropping some
 * advanced functionality so the example gamers can be presented concisely.
 * This class implements 7 of the 8 core functions that need to be implemented
 * for any gamer. If you want to quickly create a gamer of your own, extend
 * this class and add the last core function : public Move
 * stateMachineSelectMove(long timeout)
 */

public abstract class SampleGamer extends StateMachineGamer
{
  @Override
  public void stateMachineMetaGame(long timeout)
      throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
  {
    // Sample gamers do no metagaming at the beginning of the match.
  }


  /**
   * This will currently return "SampleGamer" If you are working on : public
   * abstract class MyGamer extends SampleGamer Then this function would return
   * "MyGamer"
   */
  @Override
  public String getName()
  {
    return getClass().getSimpleName();
  }

  // This is the default State Machine
  @Override
  public StateMachine getInitialStateMachine()
  {
    return new CachedStateMachine(new ProverStateMachine());
  }

  // This is the default Sample Panel
  @Override
  public DetailPanel getDetailPanel()
  {
    return new SimpleDetailPanel();
  }


  @Override
  public void stateMachineStop()
  {
    // Sample gamers do no special cleanup when the match ends normally.
  }

  @Override
  public void stateMachineAbort()
  {
    // Sample gamers do no special cleanup when the match ends abruptly.
  }

  @Override
  public void preview(Game g, long timeout)
  {
    // Sample gamers do no game previewing.
  }

  /**
   * Utility method to convert a plan, in string representation into a list of
   * Move objects.
   *
   * @param xiPlanString - the plan string.
   *
   * @return the corresponding list of moves.
   */
  public final Queue<Move> convertPlanString(String xiPlanString)
  {
    Queue<Move> lPlan = new LinkedList<>();

    // Convert the plan to Moves
    final String[] lPlanParts = xiPlanString.split(",");
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
        lPlan.add(getStateMachine().getMoveFromTerm(lHead));
      }
      else
      {
        lPlan.add(getStateMachine().getMoveFromTerm(
                                           GdlPool.getFunction(lHead, lBody)));
      }
    }

    return lPlan;
  }
}