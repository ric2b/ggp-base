package org.ggp.base.player.gamer.statemachine.mctsref;

import org.ggp.base.player.gamer.exception.GamePreviewException;
import org.ggp.base.player.gamer.statemachine.StateMachineGamer;
import org.ggp.base.player.gamer.statemachine.sancho.ThreadControl;
import org.ggp.base.util.game.Game;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class MctsRefGamer extends StateMachineGamer
{
  private final long                            SAFETY_MARGIN = 2500;

  private ForwardDeadReckonPropnetStateMachine  underlyingStateMachine = null;
  private SearchTree                            tree = null;

  @Override
  public StateMachine getInitialStateMachine()
  {
    underlyingStateMachine = new ForwardDeadReckonPropnetStateMachine(ThreadControl.CPU_INTENSIVE_THREADS,
                                                                      getMetaGamingTimeout(),
                                                                      getRole(),
                                                                      mGameCharacteristics);

    System.gc();

    return underlyingStateMachine;
  }

  @Override
  public void stateMachineMetaGame(long xiTimeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    //underlyingStateMachine.disableGreedyRollouts();
    tree = new BasicMCTSSearchTree(underlyingStateMachine);
  }

  @Override
  public Move stateMachineSelectMove(long xiTimeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    long finishBy = xiTimeout - SAFETY_MARGIN;// + 100000000;
    int iterations = 0;

   //  Convert to internal rep
    ForwardDeadReckonInternalMachineState currentState = underlyingStateMachine.createInternalState(getCurrentState());

    //  For now reset the tree every turn
    tree.clear(currentState);

    while(System.currentTimeMillis() < finishBy && !tree.isSolved())
    {
      iterations++;
      tree.grow();
    }

    System.out.println("Processed " + iterations + " iterations");
    return tree.getBestMove();
  }

  @Override
  public void stateMachineStop()
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void stateMachineAbort()
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void preview(Game xiG, long xiTimeout) throws GamePreviewException
  {
    // TODO Auto-generated method stub

  }

  @Override
  public String getName()
  {
    return "MCTSRefPlayer";
  }
}
