package org.ggp.base.player.gamer.statemachine.learner;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

public class LearningGamer extends StateMachineGamer
{
  private static final Logger LOGGER = LogManager.getLogger();
  private static final long SAFETY_MARGIN = 2500;

  private ForwardDeadReckonPropnetStateMachine  mUnderlyingStateMachine = null;
  private int                                   mTurn = 0;

  @Override
  public StateMachine getInitialStateMachine()
  {
    mUnderlyingStateMachine = new ForwardDeadReckonPropnetStateMachine(ThreadControl.CPU_INTENSIVE_THREADS,
                                                                      getMetaGamingTimeout(),
                                                                      getRole(),
                                                                      mGameCharacteristics);

    System.gc();

    return mUnderlyingStateMachine;
  }

  @Override
  public void stateMachineMetaGame(long xiTimeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    mUnderlyingStateMachine.enableGreedyRollouts(false, true);
    mTurn = 0;

    // Create and initialise a heuristic evaluation function.

    // Define the loss function.

    // Use TreeStrap to improve the evaluation function.

  }

  @Override
  public Move stateMachineSelectMove(long xiTimeout)
  {
    mTurn++;
    LOGGER.info("> > > > > Starting turn " + mTurn + " < < < < <");

    //  Convert to internal rep
    ForwardDeadReckonInternalMachineState currentState = mUnderlyingStateMachine.createInternalState(getCurrentState());

    // Read off the best move according to the learned weights.
    // !! ARR ...

    Move bestMove = null;
    System.out.println("Playing: " + bestMove);
    return bestMove;
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
  public void preview(Game xiGame, long xiTimeout) throws GamePreviewException
  {
    // TODO Auto-generated method stub

  }

  @Override
  public String getName()
  {
    return "Learner";
  }
}
